package com.pluginfence.engine

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.pluginfence.baseline.BaselineEngine
import com.pluginfence.classify.SecretEnvClassifier
import com.pluginfence.classify.SensitivePathClassifier
import com.pluginfence.correlation.CorrelationEngine
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.Decision
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceStats
import com.pluginfence.model.Incident
import com.pluginfence.model.OperationRequest
import com.pluginfence.model.PluginInfo
import com.pluginfence.model.PolicyDecision
import com.pluginfence.notifications.FenceNotifier
import com.pluginfence.persistence.FenceHistory
import com.pluginfence.persistence.FenceState
import com.pluginfence.persistence.toBean
import com.pluginfence.persistence.toModel
import com.pluginfence.policy.Evaluation
import com.pluginfence.policy.GrantStore
import com.pluginfence.policy.PolicyEngine
import com.pluginfence.policy.PolicyStore
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The control plane. One application-level service that
 *
 *  1. answers the agent's policy questions synchronously (in-memory, on the intercepted thread),
 *  2. turns intercepted requests into redacted [FenceEvent]s on a single background thread,
 *  3. feeds baselines, drift detection, correlation, notifications and persistence, and
 *  4. serves snapshots to the tool window.
 */
@Service(Service.Level.APP)
class FenceEngine : Disposable {

    private val log = Logger.getInstance(FenceEngine::class.java)

    private val state = FenceState.getInstance()
    private val history = FenceHistory.getInstance()

    val scope = ScopeTracker()
    val sensitivePaths = SensitivePathClassifier()
    private val secretEnv = SecretEnvClassifier()

    val policies = PolicyStore(state.state.policies.map { it.toModel() }) { persistPolicies() }
    val grants = GrantStore()
    val baselines = BaselineEngine(
        state.state.profiles.map { it.toModel() },
        state.state.drifts.map { it.toModel() },
    ) { baselinesDirty.set(true) }
    val correlation = CorrelationEngine()
    private val policyEngine = PolicyEngine(sensitivePaths, secretEnv, { scope.pathScope() }, policies, grants, baselines, correlation)
    private val notifier = FenceNotifier(this)

    private val pendingEvaluation = ThreadLocal<Pair<Long, Evaluation>>()

    private val eventIds = AtomicLong(history.state.lastEventId)
    private val events = ArrayDeque<FenceEvent>()
    private val incidents = LinkedHashMap<String, Incident>()
    private val queue = ConcurrentLinkedQueue<FenceEvent>()
    private val processing = AtomicBoolean(false)
    private val baselinesDirty = AtomicBoolean(false)
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("PluginFence", 1)
    private val started = AtomicBoolean(false)
    private val observedPlugins = ConcurrentHashMap.newKeySet<String>()

    /** Present only when the agent's bootstrap classes are on the boot class path. */
    private val bridge: AgentBridge? = if (AgentProbe.available) AgentBridge(this) else null

    @Volatile
    var enforcementEnabled: Boolean = state.state.settings.enforcementEnabled
        private set

    init {
        synchronized(events) {
            history.state.events.mapNotNull { it.toModel() }.forEach { events.addLast(it); observedPlugins.add(it.pluginId) }
            history.state.incidents.mapNotNull { it.toModel() }.forEach { incidents[it.id] = it }
        }
    }

    // --- lifecycle --------------------------------------------------------------------------------

    /** Idempotent; called from the app lifecycle listener, the project activity and the tool window. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.refreshProjects()
        val b = bridge
        if (b == null) {
            log.warn("PluginFence agent is not active (bootstrap classes not on the boot class path); enforcement unavailable")
            return
        }
        val replayed = b.register(enforcementEnabled)
        log.info("PluginFence control plane registered (${b.agentInfo()}); $replayed buffered events replayed")
    }

    override fun dispose() {
        bridge?.unregister()
        flushHistory()
        flushBaselines()
    }

    fun agentAvailable(): Boolean = bridge != null

    fun agentInstalled(): Boolean = bridge?.isInstalled() == true

    // --- decision path (runs on the plugin's thread; must stay fast) ---------------------------

    fun decide(request: OperationRequest): Decision {
        val trusted = scope.isTrusted(request.pluginId, request.bundled, request.vendor)
        val evaluation = policyEngine.evaluate(request, trusted)
        pendingEvaluation.set(request.eventId to evaluation)
        return evaluation.decision
    }

    fun record(request: OperationRequest, decision: Decision) {
        val pending = pendingEvaluation.get()
        pendingEvaluation.remove()
        val evaluation = if (pending != null && pending.first == request.eventId) pending.second else {
            // Buffered before registration: classify without consuming grants.
            policyEngine.evaluate(request, scope.isTrusted(request.pluginId, request.bundled, request.vendor), consumeGrants = false)
        }
        val event = buildEvent(request, decision, evaluation)
        correlation.noteSensitiveAccess(event.pluginId, event)
        enqueue(event)
    }

    private fun buildEvent(request: OperationRequest, decision: Decision, evaluation: Evaluation): FenceEvent {
        val score = if (decision.ruleId == Decision.MONITOR_RULE) evaluation.decision.riskScore else decision.riskScore
        return FenceEvent(
            id = eventIds.incrementAndGet(),
            timestamp = request.timestamp,
            pluginId = request.pluginId,
            pluginName = request.pluginName,
            pluginVersion = request.pluginVersion,
            pluginKnown = request.pluginKnown,
            sourceClass = request.sourceClass,
            operation = request.operation,
            capability = evaluation.capability,
            target = request.target,
            approvalTarget = evaluation.approvalTarget,
            api = request.api,
            verdict = decision.verdict,
            reason = decision.reason,
            ruleId = decision.ruleId,
            riskScore = score,
            riskLevel = FenceRisk.fromScore(score),
            riskFactors = evaluation.riskFactors,
            sensitiveCategory = evaluation.sensitiveMatch?.category,
            pathRelation = evaluation.pathRelation,
            metadata = request.metadata,
            agentMode = bridge?.isProviderRegistered() == true,
        )
    }

    private fun enqueue(event: FenceEvent) {
        queue.add(event)
        if (processing.compareAndSet(false, true)) {
            executor.execute { drainQueue() }
        }
    }

    // --- background processing --------------------------------------------------------------------------

    private fun drainQueue() {
        try {
            guarded {
                var batch = pollBatch()
                while (batch.isNotEmpty()) {
                    process(batch)
                    batch = pollBatch()
                }
            }
        } catch (t: Throwable) {
            log.warn("PluginFence event processing failed", t)
        } finally {
            processing.set(false)
            if (queue.isNotEmpty() && processing.compareAndSet(false, true)) {
                executor.execute { drainQueue() }
            }
        }
    }

    private fun <T> guarded(block: () -> T): T = bridge?.guarded(block) ?: block()

    private fun pollBatch(): List<FenceEvent> {
        val batch = ArrayList<FenceEvent>()
        while (batch.size < 64) {
            batch += queue.poll() ?: break
        }
        return batch
    }

    private fun process(batch: List<FenceEvent>) {
        val maxEvents = state.state.settings.maxEvents
        synchronized(events) {
            for (event in batch) {
                events.addLast(event)
                while (events.size > maxEvents) events.removeFirst()
                observedPlugins.add(event.pluginId)
            }
        }
        val publisher = ApplicationManager.getApplication().messageBus.syncPublisher(FenceListener.TOPIC)
        for (event in batch) {
            val observation = runCatching { baselines.observe(event) }.onFailure { log.warn("baseline update failed", it) }.getOrNull()
            observation?.drift?.let { drift ->
                if (observation.driftChanged) {
                    publisher.driftUpdated(drift)
                    if (observation.becameHighRisk) notifier.notifyDrift(drift)
                }
            }
            val incident = runCatching { correlation.observe(event) }.onFailure { log.warn("correlation failed", it) }.getOrNull()
            if (incident != null) {
                upsertIncident(incident)
                publisher.incidentUpdated(incident)
                notifier.notifyIncident(incident, event)
            } else {
                notifier.notifyEvent(event)
            }
        }
        publisher.eventsRecorded(batch)
        flushHistory()
        if (baselinesDirty.getAndSet(false)) flushBaselines()
    }

    private fun upsertIncident(incident: Incident) {
        synchronized(events) {
            incidents[incident.id] = incident
            val max = state.state.settings.maxIncidents
            while (incidents.size > max) {
                incidents.remove(incidents.keys.first())
            }
        }
    }

    // --- persistence -------------------------------------------------------------------------------------

    private fun persistPolicies() {
        state.state.policies = policies.all().map { it.toBean() }.toMutableList()
        publishStateChanged()
    }

    private fun flushBaselines() {
        state.state.profiles = baselines.allProfiles().map { it.toBean() }.toMutableList()
        state.state.drifts = baselines.allDrifts().map { it.toBean() }.toMutableList()
    }

    private fun flushHistory() {
        synchronized(events) {
            history.state.events = events.map { it.toBean() }.toMutableList()
            history.state.incidents = incidents.values.map { it.toBean() }.toMutableList()
            history.state.lastEventId = eventIds.get()
        }
    }

    private fun publishStateChanged() {
        runCatching { ApplicationManager.getApplication().messageBus.syncPublisher(FenceListener.TOPIC).stateChanged() }
    }

    // --- queries for the UI ---------------------------------------------------------------------------------

    fun events(): List<FenceEvent> = synchronized(events) { events.toList().asReversed() }

    fun incidents(): List<Incident> = synchronized(events) { incidents.values.sortedByDescending { it.timestamp } }

    fun incident(id: String): Incident? = synchronized(events) { incidents[id] }

    fun drifts(): List<BehaviorDrift> = baselines.allDrifts()

    fun profiles(pluginId: String): List<BehaviorProfile> = baselines.profiles(pluginId)

    fun stats(): FenceStats {
        val (eventCount, blocked, critical) = synchronized(events) {
            Triple(events.size.toLong(), events.count { it.prevented }.toLong(), incidents.values.count { it.riskLevel == FenceRisk.CRITICAL })
        }
        val governed = governedPlugins()
        val monitored = (governed.map { it.pluginId } + observedPlugins).toSet().size
        val b = bridge
        return FenceStats(
            agentInstalled = b?.isInstalled() == true,
            providerRegistered = b?.isProviderRegistered() == true,
            enforcementEnabled = enforcementEnabled,
            pluginsMonitored = monitored,
            eventsRecorded = eventCount,
            blockedAttempts = blocked,
            behaviorChanges = baselines.allDrifts().count { it.hasChanges },
            criticalIncidents = critical,
            agentInfo = b?.agentInfo() ?: "",
            agentDiagnostics = b?.diagnostics() ?: emptyMap(),
        )
    }

    /** Third-party plugins subject to policy: installed non-bundled plugins plus anything already observed. */
    fun governedPlugins(): List<PluginInfo> {
        val installed = scope.installedPlugins()
        val byId = installed.associateBy { it.pluginId }.toMutableMap()
        synchronized(events) {
            for (event in events) {
                if (event.pluginKnown && event.pluginId !in byId) {
                    byId[event.pluginId] = PluginInfo(event.pluginId, event.pluginName, event.pluginVersion, "", false, false, true)
                }
            }
        }
        return byId.values
            .filter { it.pluginId != ScopeTracker.OWN_PLUGIN_ID && (!it.bundled || it.pluginId in observedPlugins) }
            .sortedWith(compareBy({ it.trusted }, { it.name.lowercase() }))
    }

    // --- user actions -----------------------------------------------------------------------------------------

    fun setEnforcement(enabled: Boolean) {
        enforcementEnabled = enabled
        state.state.settings.enforcementEnabled = enabled
        bridge?.setEnforcement(enabled)
        publishStateChanged()
    }

    fun setPolicy(pluginId: String, capability: Capability, decision: PolicyDecision?) = policies.setDecision(pluginId, capability, decision)

    fun allowOnce(event: FenceEvent) {
        val capability = event.capability ?: return
        grants.grantOnce(event.pluginId, capability, event.approvalTarget)
        publishStateChanged()
    }

    fun alwaysAllow(event: FenceEvent) {
        val capability = event.capability ?: return
        policies.approve(event.pluginId, capability, event.approvalTarget)
    }

    fun keepBlocking(event: FenceEvent) = notifier.mute(event)

    fun clearHistory() {
        synchronized(events) {
            events.clear()
            incidents.clear()
        }
        correlation.reset()
        flushHistory()
        publishStateChanged()
    }

    fun resetBaselines() {
        baselines.reset()
        flushBaselines()
        publishStateChanged()
    }

    companion object {
        fun getInstance(): FenceEngine = ApplicationManager.getApplication().getService(FenceEngine::class.java)
    }
}
