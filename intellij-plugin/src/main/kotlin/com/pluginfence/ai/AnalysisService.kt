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
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("PluginFence AI", 2)
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

    private fun execute(task: AnalysisTask) {
        val s = settings
        val engine = FenceEngine.getInstance()
        val result = try {
            if (!s.configured) {
                AnalysisResult.failure(task, s.model, startedAt[task.key] ?: System.currentTimeMillis(), emptyList(),
                    "AI analyst is not configured. Settings > Tools > PluginFence: enable it and add an API key (or point it at a local model).")
            } else {
                val client = OpenAiCompatibleClient(s.endpoint, s.apiKey, s.model)
                val analyst = Analyst(client, EngineToolBackend(engine), s.model, s.maxSteps) { step ->
                    running[task.key]?.add(step)
                    publish(task.key)
                }
                analyst.run(task)
            }
        } catch (t: Throwable) {
            log.warn("PluginFence AI analysis failed", t)
            AnalysisResult.failure(task, s.model, startedAt[task.key] ?: System.currentTimeMillis(), running[task.key]?.toList() ?: emptyList(), t.message ?: t.javaClass.simpleName)
        }
        results[task.key] = result
        running.remove(task.key)
        log.info(
            "PluginFence AI analysis complete: task=${task.key} model=${result.model} verdict=${result.verdict} " +
                "steps=${result.trace.count { it.kind == TraceStep.Kind.TOOL }} recommendations=${result.recommendations.size} " +
                "duration=${result.durationMs}ms" + (result.error?.let { " error=$it" } ?: ""),
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

    private fun maybeAutoAnalyse(incident: Incident) {
        if (!settings.autoAnalyseCritical || !settings.configured) return
        if (incident.riskLevel != FenceRisk.CRITICAL) return
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
