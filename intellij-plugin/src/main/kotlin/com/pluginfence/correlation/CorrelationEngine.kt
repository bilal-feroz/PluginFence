package com.pluginfence.correlation

import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import com.pluginfence.model.IncidentKind
import com.pluginfence.policy.CORRELATION_WINDOW_MS
import com.pluginfence.policy.CorrelationLookup
import com.pluginfence.policy.RiskWeights
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a short rolling window of events per plugin and turns dangerous *sequences* into
 * incidents:
 *
 *  - sensitive file / secret env access  ->  network connection within 10 s   => POTENTIAL_SECRET_EXFILTRATION
 *  - sensitive file / secret env access  ->  process execution within 10 s    => SECRET_ACCESS_THEN_PROCESS
 *  - blocked credential-store access on its own                               => SENSITIVE_ACCESS_BLOCKED
 *
 * [recentSensitiveAccess] is consulted synchronously by the policy engine so the *network* leg
 * of an exfiltration attempt can be blocked outright, not just reported afterwards.
 */
class CorrelationEngine(private val windowMs: Long = CORRELATION_WINDOW_MS) : CorrelationLookup {

    private val windows = ConcurrentHashMap<String, ArrayDeque<FenceEvent>>()
    private val incidentsByAnchor = ConcurrentHashMap<String, Incident>()

    /** Called synchronously from the decision path so subsequent decisions see this access. */
    fun noteSensitiveAccess(pluginId: String, event: FenceEvent) {
        if (!isSensitive(event)) return
        val window = windows.computeIfAbsent(pluginId) { ArrayDeque() }
        synchronized(window) {
            window.addLast(event)
            trim(window, event.timestamp)
        }
    }

    override fun recentSensitiveAccess(pluginId: String, now: Long, windowMs: Long): FenceEvent? {
        val window = windows[pluginId] ?: return null
        synchronized(window) {
            trim(window, now)
            return window.lastOrNull { isSensitive(it) && now - it.timestamp <= windowMs }
        }
    }

    /** Called from the background processor with the fully built event. Returns a new or updated incident. */
    fun observe(event: FenceEvent): Incident? {
        val pluginId = event.pluginId
        when (event.operation) {
            FenceOperation.NETWORK_CONNECT, FenceOperation.PROCESS_EXEC -> {
                val anchor = recentSensitiveAccess(pluginId, event.timestamp, windowMs) ?: return null
                val kind = if (event.operation == FenceOperation.NETWORK_CONNECT) IncidentKind.POTENTIAL_SECRET_EXFILTRATION else IncidentKind.SECRET_ACCESS_THEN_PROCESS
                val key = "$pluginId|${anchor.id}|${kind.name}"
                val existing = incidentsByAnchor[key]
                val chain = (existing?.chain ?: listOf(anchor)) + event
                val score = RiskWeights.clamp(anchor.riskScore.coerceAtLeast(RiskWeights.SENSITIVE_FILE) + event.riskScore.coerceAtLeast(RiskWeights.NEW_DESTINATION) + RiskWeights.SECRET_THEN_EXFIL)
                val secretWhat = describeSensitive(anchor)
                val followUp = if (event.operation == FenceOperation.NETWORK_CONNECT) "attempted a new outbound connection to ${event.target}" else "launched ${event.target}"
                val incident = Incident(
                    id = existing?.id ?: "inc-${anchor.id}-${kind.name.lowercase()}",
                    timestamp = existing?.timestamp ?: event.timestamp,
                    kind = kind,
                    pluginId = pluginId,
                    pluginName = event.pluginName,
                    pluginVersion = event.pluginVersion,
                    summary = "Plugin ${verb(anchor)} $secretWhat and within ${(event.timestamp - anchor.timestamp).coerceAtLeast(0) / 1000.0} s $followUp.",
                    outcome = outcome(event),
                    riskScore = score,
                    riskLevel = FenceRisk.fromScore(score),
                    chain = chain,
                )
                incidentsByAnchor[key] = incident
                return incident
            }
            else -> {
                if (!isSensitive(event) || !event.prevented) return null
                val kind = if (event.capability == Capability.SECRET_ENVIRONMENT) IncidentKind.SECRET_ENVIRONMENT_BLOCKED else IncidentKind.SENSITIVE_ACCESS_BLOCKED
                val key = "$pluginId|${event.id}|${kind.name}"
                val incident = Incident(
                    id = "inc-${event.id}-single",
                    timestamp = event.timestamp,
                    kind = kind,
                    pluginId = pluginId,
                    pluginName = event.pluginName,
                    pluginVersion = event.pluginVersion,
                    summary = "Plugin attempted to ${if (event.operation == FenceOperation.FILE_WRITE) "write" else "read"} ${describeSensitive(event)}.",
                    outcome = outcome(event),
                    riskScore = event.riskScore,
                    riskLevel = event.riskLevel,
                    chain = listOf(event),
                )
                incidentsByAnchor[key] = incident
                return incident
            }
        }
    }

    fun reset() {
        windows.clear()
        incidentsByAnchor.clear()
    }

    private fun trim(window: ArrayDeque<FenceEvent>, now: Long) {
        while (window.isNotEmpty() && (now - window.first().timestamp > windowMs * 3 || window.size > 64)) {
            window.removeFirst()
        }
    }

    private fun isSensitive(event: FenceEvent) =
        event.capability == Capability.SENSITIVE_FILES || (event.capability == Capability.SECRET_ENVIRONMENT && event.operation == FenceOperation.ENV_READ)

    private fun describeSensitive(event: FenceEvent): String = when (event.capability) {
        Capability.SECRET_ENVIRONMENT -> "secret environment variable ${event.target}"
        else -> "sensitive file ${event.target}" + (event.sensitiveCategory?.let { " ($it)" } ?: "")
    }

    private fun verb(anchor: FenceEvent) = if (anchor.prevented) "attempted to read" else "read"

    private fun outcome(event: FenceEvent) = when (event.verdict) {
        FenceVerdict.BLOCK -> "${event.actionLabel} blocked"
        FenceVerdict.ASK -> "${event.actionLabel} prevented, awaiting permission"
        FenceVerdict.ALLOW -> "${event.actionLabel} allowed by policy"
        FenceVerdict.MONITOR -> "${event.actionLabel} monitored (enforcement inactive)"
    }
}
