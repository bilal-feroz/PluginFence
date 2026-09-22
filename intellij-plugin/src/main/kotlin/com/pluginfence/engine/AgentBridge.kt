package com.pluginfence.engine

import com.pluginfence.bootstrap.DecisionProvider
import com.pluginfence.bootstrap.GuardBridge
import com.pluginfence.bootstrap.GuardHooks
import com.pluginfence.bootstrap.OperationType
import com.pluginfence.bootstrap.SecurityDecision
import com.pluginfence.bootstrap.SecurityRequest
import com.pluginfence.bootstrap.Verdict
import com.pluginfence.model.Decision
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.OperationRequest

/**
 * The only class in the plugin that touches bootstrap types. It is instantiated solely when
 * [AgentProbe.available] is true, so the plugin loads and works (in "agent not active" mode)
 * without the agent.
 */
class AgentBridge(private val engine: FenceEngine) {

    private val provider = object : DecisionProvider {
        override fun decide(request: SecurityRequest): SecurityDecision =
            engine.decide(toModel(request)).toBootstrap()

        override fun record(request: SecurityRequest, decision: SecurityDecision) =
            engine.record(toModel(request), decision.toModel())
    }

    fun register(enforcement: Boolean): Int {
        GuardBridge.setEnforcementEnabled(enforcement)
        GuardBridge.setProvider(provider)
        val pending = GuardBridge.drainPending()
        pending.forEach { provider.record(it.request(), it.decision()) }
        return pending.size
    }

    fun unregister() = GuardBridge.clearProvider(provider)

    fun setEnforcement(enabled: Boolean) = GuardBridge.setEnforcementEnabled(enabled)

    fun isInstalled(): Boolean = GuardBridge.isAgentInstalled()

    fun isProviderRegistered(): Boolean = GuardBridge.provider() === provider

    fun agentInfo(): String = GuardBridge.agentInfo()

    fun diagnostics(): Map<String, String> = GuardBridge.diagnostics()

    /** Every plugin whose class loader the agent has resolved so far (descriptor-derived metadata). */
    fun knownPlugins(): List<com.pluginfence.model.PluginInfo> = GuardBridge.identities()
        .filter { it.isKnown }
        .map { id ->
            com.pluginfence.model.PluginInfo(
                pluginId = id.pluginId(),
                name = id.pluginName(),
                version = id.pluginVersion() ?: "",
                vendor = id.vendor() ?: "",
                bundled = id.isBundled,
                trusted = engine.scope.isTrusted(id.pluginId(), id.isBundled, id.vendor()),
                enabled = true,
            )
        }

    /** Class loader of a plugin the agent has seen (for reading its own descriptor); null if unknown or unloaded. */
    fun pluginClassLoader(pluginId: String): ClassLoader? =
        GuardBridge.identities().firstOrNull { it.isKnown && it.pluginId() == pluginId }?.loader()

    /** Runs [block] with the hook re-entrancy guard held so PluginFence's own I/O is never intercepted. */
    fun <T> guarded(block: () -> T): T = GuardHooks.withGuard(block)

    // --- conversions --------------------------------------------------------------------------

    private fun toModel(request: SecurityRequest): OperationRequest {
        val identity = request.identity()
        return OperationRequest(
            eventId = request.eventId(),
            timestamp = request.timestamp(),
            pluginId = identity.key(),
            pluginName = identity.pluginName(),
            pluginVersion = identity.pluginVersion() ?: "",
            pluginKnown = identity.isKnown,
            bundled = identity.isBundled,
            vendor = identity.vendor(),
            sourceClass = request.sourceClass(),
            operation = when (request.operation()) {
                OperationType.FILE_READ -> FenceOperation.FILE_READ
                OperationType.FILE_WRITE -> FenceOperation.FILE_WRITE
                OperationType.ENV_READ -> FenceOperation.ENV_READ
                OperationType.ENV_ENUMERATE -> FenceOperation.ENV_ENUMERATE
                OperationType.PROCESS_EXEC -> FenceOperation.PROCESS_EXEC
                OperationType.NETWORK_CONNECT -> FenceOperation.NETWORK_CONNECT
            },
            target = request.target(),
            api = request.api(),
            metadata = request.metadata(),
        )
    }

    private fun Decision.toBootstrap(): SecurityDecision = SecurityDecision(
        when (verdict) {
            FenceVerdict.ALLOW -> Verdict.ALLOW
            FenceVerdict.MONITOR -> Verdict.MONITOR
            FenceVerdict.ASK -> Verdict.ASK
            FenceVerdict.BLOCK -> Verdict.BLOCK
        },
        reason, ruleId, riskScore,
    )

    private fun SecurityDecision.toModel(): Decision = Decision(
        when (verdict()) {
            Verdict.ALLOW -> FenceVerdict.ALLOW
            Verdict.MONITOR -> FenceVerdict.MONITOR
            Verdict.ASK -> FenceVerdict.ASK
            Verdict.BLOCK -> FenceVerdict.BLOCK
        },
        reason(), ruleId(), riskScore(),
    )
}

/** Probes once whether the bootstrap classes are reachable (i.e. the agent's Boot-Class-Path is set). */
object AgentProbe {
    val available: Boolean by lazy {
        try {
            val c = Class.forName("com.pluginfence.bootstrap.GuardBridge", false, null)
            c.classLoader == null
        } catch (_: Throwable) {
            false
        }
    }
}
