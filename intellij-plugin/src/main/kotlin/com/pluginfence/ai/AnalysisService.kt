package com.pluginfence.ai

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.pluginfence.engine.FenceEngine
import com.pluginfence.engine.FenceListener
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.Incident
import com.pluginfence.policy.DefaultPolicies
import com.pluginfence.ui.FenceToolWindowFactory
import java.util.concurrent.ConcurrentHashMap

/** UI subscribers get a key, re-read [AnalysisService.status] and redraw. Called on any thread. */
interface AnalysisListener {
    fun changed(taskKey: String)

    companion object {
        @JvmField
        val TOPIC: Topic<AnalysisListener> = Topic.create("PluginFence AI analysis", AnalysisListener::class.java)
    }
}

/**
 * Runs analyses off the EDT, remembers their results, and applies recommendations when the user
 * says so. Optionally analyses CRITICAL incidents as they happen.
 *
 * The service is the only place the model is ever called from. Nothing here runs on the
 * intercepted thread, and a failing or slow model can never delay a policy decision.
 */
@Service(Service.Level.APP)
class AnalysisService : Disposable {

    sealed class Status {
        object Idle : Status()
        data class Running(val startedAt: Long, val trace: List<TraceStep>) : Status()
        data class Done(val result: AnalysisResult) : Status()
    }

    private val log = Logger.getInstance(AnalysisService::class.java)
    private val running = ConcurrentHashMap<String, MutableList<TraceStep>>()
    private val results = ConcurrentHashMap<String, AnalysisResult>()
    private val startedAt = ConcurrentHashMap<String, Long>()
    private val autoAnalysedPlugins = ConcurrentHashMap.newKeySet<String>()
    /**
     * One analysis at a time. Several incidents can land in the same second during an attack, and
     * firing an investigation for each in parallel is the fastest way to exhaust a provider's
     * tokens-per-minute budget - which turns a clean demo into a wall of rate-limit retries.
     * Serialised, each analysis gets the whole budget and finishes in one pass.
     */
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("PluginFence AI", 1)
    private val settings get() = AiSettings.getInstance()

    init {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            FenceListener.TOPIC,
            object : FenceListener {
                override fun incidentUpdated(incident: Incident) = maybeAutoAnalyse(incident)
            },
        )
    }

    fun isConfigured(): Boolean = settings.configured

    fun status(task: AnalysisTask): Status {
        running[task.key]?.let { return Status.Running(startedAt[task.key] ?: System.currentTimeMillis(), it.toList()) }
        results[task.key]?.let { return Status.Done(it) }
        return Status.Idle
    }

    fun result(task: AnalysisTask): AnalysisResult? = results[task.key]

    /** Starts an analysis unless one is already running for the same task. */
    fun analyse(task: AnalysisTask, force: Boolean = false) {
        if (!force && results.containsKey(task.key)) return
        if (running.putIfAbsent(task.key, java.util.Collections.synchronizedList(ArrayList())) != null) return
        startedAt[task.key] = System.currentTimeMillis()
        publish(task.key)
        executor.execute { execute(task) }
    }

    /**
     * Runs the analysis through a failover chain so the user is never shown a dead end:
     * the configured model, then any backup models, and finally PluginFence's own deterministic
     * rules, which need no network at all. A rate limit or an outage degrades the *prose*, never
     * the answer - the evidence behind it is local either way.
     */
    private fun execute(task: AnalysisTask) {
        val s = settings
        val engine = FenceEngine.getInstance()
        val began = startedAt[task.key] ?: System.currentTimeMillis()
        val onStep: (TraceStep) -> Unit = { step ->
            running[task.key]?.add(step)
            publish(task.key)
        }

        var result: AnalysisResult? = null
        if (s.configured) {
            val chain = s.modelChain()
            for ((index, candidate) in chain.withIndex()) {
                if (index > 0) {
                    onStep(TraceStep(TraceStep.Kind.MESSAGE, "Switching to $candidate", "the previous model was unavailable or rate limited"))
                }
                val attempt = try {
                    val client = OpenAiCompatibleClient(s.endpoint, s.apiKey, candidate) { n, wait, status ->
                        val why = if (status == 429) "Provider is rate limiting" else "Provider error $status"
                        onStep(TraceStep(TraceStep.Kind.MESSAGE, "$why - retrying in ${wait.toMillis() / 1000.0}s", "attempt $n"))
                    }
                    Analyst(client, EngineToolBackend(engine), candidate, s.maxSteps, onStep).run(task)
                } catch (t: Throwable) {
                    log.warn("PluginFence AI analysis threw on $candidate", t)
                    AnalysisResult.failure(task, candidate, began, running[task.key]?.toList() ?: emptyList(), t.message ?: t.javaClass.simpleName)
                }
                if (!attempt.failed) {
                    result = attempt.copy(fallbackReason = if (index == 0) null else "${chain[0]} was unavailable")
                    break
                }
                result = attempt
            }
        }

        // Nothing configured, or every model failed: answer from the evidence PluginFence already has.
        if (result == null || result.failed) {
            val why = result?.error ?: "no model configured"
            onStep(TraceStep(TraceStep.Kind.MESSAGE, "Falling back to PluginFence's own rules", why))
            result = DeterministicAnalyst.analyse(engine, task, began, why)
                .copy(trace = (running[task.key]?.toList() ?: emptyList()) + TraceStep(TraceStep.Kind.FINAL, "Analysis complete", "derived deterministically, no model involved"),
                    fallbackReason = why)
        }
        results[task.key] = result
        running.remove(task.key)
        log.info(
            "PluginFence AI analysis complete: task=${task.key} model=${result.model} verdict=${result.verdict} " +
                "steps=${result.toolCallCount} recommendations=${result.recommendations.size} " +
                "duration=${result.durationMs}ms deterministic=${result.deterministic}" +
                (result.fallbackReason?.let { " fallback=$it" } ?: "") + (result.error?.let { " error=$it" } ?: ""),
        )
        publish(task.key)
        if (task is AnalysisTask.Incident && !result.failed && result.verdict <= AnalystVerdict.SUSPICIOUS) notify(result)
    }

    /** Applies every recommendation and target change. Returns how many settings were touched. */
    fun apply(result: AnalysisResult): Int {
        val engine = FenceEngine.getInstance()
        var applied = 0
        for (rec in result.recommendations) {
            val current = engine.policies.effective(result.task.pluginId, rec.capability)
            if (current == rec.decision) continue
            engine.setPolicy(result.task.pluginId, rec.capability, if (rec.decision == DefaultPolicies.of(rec.capability)) null else rec.decision)
            applied++
        }
        for (change in result.targetChanges) {
            if (change.approve) engine.policies.approve(result.task.pluginId, change.capability, change.target)
            else engine.policies.revoke(result.task.pluginId, change.capability, change.target)
            applied++
        }
        log.info("PluginFence AI recommendations applied: task=${result.task.key} changes=$applied")
        return applied
    }

    /**
     * Auto-analysis is a convenience, not a crawler: one attack sequence produces several correlated
     * CRITICAL incidents within seconds, and analysing every one of them says almost the same thing
     * several times while burning the provider's rate limit. So it analyses the first CRITICAL
     * incident seen for a plugin and then leaves the rest to the user's own "Analyse with AI" click.
     */
    private fun maybeAutoAnalyse(incident: Incident) {
        if (!settings.autoAnalyseCritical || !settings.configured) return
        if (incident.riskLevel != FenceRisk.CRITICAL) return
        if (!autoAnalysedPlugins.add(incident.pluginId)) return
        val task = AnalysisTask.Incident(incident.id, incident.pluginId, incident.pluginName)
        if (results.containsKey(task.key) || running.containsKey(task.key)) return
        analyse(task)
    }

    private fun notify(result: AnalysisResult) {
        val task = result.task as? AnalysisTask.Incident ?: return
        ApplicationManager.getApplication().invokeLater {
            val project = ProjectManager.getInstance().openProjects.firstOrNull()
            val n = NotificationGroupManager.getInstance().getNotificationGroup("PluginFence Incidents")
                .createNotification("AI analyst: ${result.verdict.label} - ${task.pluginName}", result.headline, NotificationType.WARNING)
            n.addAction(NotificationAction.createSimpleExpiring("View analysis") {
                (project ?: ProjectManager.getInstance().openProjects.firstOrNull())?.let { FenceToolWindowFactory.show(it, FenceToolWindowFactory.TAB_OVERVIEW) }
            })
            n.notify(project)
        }
    }

    private fun publish(key: String) {
        runCatching { ApplicationManager.getApplication().messageBus.syncPublisher(AnalysisListener.TOPIC).changed(key) }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): AnalysisService = ApplicationManager.getApplication().getService(AnalysisService::class.java)
    }
}
