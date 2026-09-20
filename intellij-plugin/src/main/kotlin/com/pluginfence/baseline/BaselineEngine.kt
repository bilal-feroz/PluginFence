package com.pluginfence.baseline

import com.pluginfence.classify.NetworkClassifier
import com.pluginfence.classify.ProcessClassifier
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.RiskFactor
import com.pluginfence.policy.BaselineLookup
import com.pluginfence.policy.RiskWeights

/**
 * Builds deterministic behaviour baselines per (plugin, version) and detects drift between
 * versions. "Learning" here means recording what was observed - nothing statistical.
 *
 * Attempts count as observations even when they were blocked: the baseline answers
 * "what does this version *try* to do", which is exactly what an update review needs.
 */
class BaselineEngine(
    initialProfiles: Collection<BehaviorProfile> = emptyList(),
    initialDrifts: Collection<BehaviorDrift> = emptyList(),
    private val onChange: () -> Unit = {},
) : BaselineLookup {

    private val lock = Any()
    private val profiles = HashMap<String, HashMap<String, BehaviorProfile>>() // pluginId -> version -> profile
    private val drifts = HashMap<String, BehaviorDrift>()                       // pluginId|old|new -> drift

    init {
        initialProfiles.forEach { profiles.getOrPut(it.pluginId) { HashMap() }[it.version] = it }
        initialDrifts.forEach { drifts[driftKey(it.pluginId, it.oldVersion, it.newVersion)] = it }
    }

    /** Result of observing one event. */
    data class Observation(val profile: BehaviorProfile, val drift: BehaviorDrift?, val driftChanged: Boolean, val becameHighRisk: Boolean)

    fun observe(event: FenceEvent): Observation? {
        if (!event.pluginKnown || event.capability == null) return null
        synchronized(lock) {
            val versions = profiles.getOrPut(event.pluginId) { HashMap() }
            val version = event.pluginVersion.ifBlank { "unknown" }
            val profile = (versions[version] ?: BehaviorProfile.empty(event.pluginId, event.pluginName, version, event.timestamp)).with(event)
            versions[version] = profile

            val previous = previousVersionLocked(event.pluginId, version)
            var drift: BehaviorDrift? = null
            var changed = false
            var becameHighRisk = false
            if (previous != null) {
                drift = compare(previous, profile, event.timestamp)
                val key = driftKey(event.pluginId, previous.version, version)
                val old = drifts[key]
                if (drift.hasChanges) {
                    if (old == null || old.newCapabilityCount != drift.newCapabilityCount || old.riskScore != drift.riskScore) {
                        drifts[key] = drift
                        changed = true
                        becameHighRisk = drift.highRisk && (old == null || !old.highRisk)
                    } else {
                        drift = old
                    }
                } else {
                    drift = null
                }
            }
            onChange()
            return Observation(profile, drift, changed, becameHighRisk)
        }
    }

    // --- lookups ----------------------------------------------------------------------------

    override fun previousVersionProfile(pluginId: String, currentVersion: String): BehaviorProfile? =
        synchronized(lock) { previousVersionLocked(pluginId, currentVersion.ifBlank { "unknown" }) }

    override fun isKnownHost(pluginId: String, host: String): Boolean = synchronized(lock) {
        val h = NetworkClassifier.normalizeHost(host)
        profiles[pluginId]?.values?.any { p -> p.networkHosts.any { NetworkClassifier.normalizeHost(it) == h } } == true
    }

    fun profiles(pluginId: String): List<BehaviorProfile> = synchronized(lock) {
        profiles[pluginId]?.values?.sortedBy { it.firstSeen } ?: emptyList()
    }

    fun allProfiles(): List<BehaviorProfile> = synchronized(lock) {
        profiles.values.flatMap { it.values }.sortedWith(compareBy({ it.pluginId }, { it.firstSeen }))
    }

    fun allDrifts(): List<BehaviorDrift> = synchronized(lock) { drifts.values.sortedByDescending { it.detectedAt } }

    fun drift(pluginId: String): BehaviorDrift? = synchronized(lock) {
        drifts.values.filter { it.pluginId == pluginId }.maxByOrNull { it.detectedAt }
    }

    fun pluginIds(): Set<String> = synchronized(lock) { profiles.keys.toSet() }

    fun reset() {
        synchronized(lock) {
            profiles.clear()
            drifts.clear()
        }
        onChange()
    }

    private fun previousVersionLocked(pluginId: String, currentVersion: String): BehaviorProfile? =
        profiles[pluginId]?.values?.filter { it.version != currentVersion }?.maxByOrNull { it.lastSeen }

    private fun driftKey(pluginId: String, old: String, new: String) = "$pluginId|$old|$new"

    companion object {
        /** Pure comparison of two version profiles. */
        fun compare(previous: BehaviorProfile, current: BehaviorProfile, now: Long): BehaviorDrift {
            val addedCaps = current.capabilities - previous.capabilities
            val removedCaps = previous.capabilities - current.capabilities
            val prevHosts = previous.networkHosts.map { NetworkClassifier.normalizeHost(it) }.toSet()
            val addedHosts = current.networkHosts.filter { NetworkClassifier.normalizeHost(it) !in prevHosts }.toSet()
            val prevProcs = previous.processes.map { it.lowercase() }.toSet()
            val addedProcs = current.processes.filter { it.lowercase() !in prevProcs }.toSet()
            val addedSensitive = current.sensitiveResources - previous.sensitiveResources

            val factors = ArrayList<RiskFactor>()
            if (addedCaps.isNotEmpty() || addedHosts.isNotEmpty() || addedProcs.isNotEmpty() || addedSensitive.isNotEmpty()) {
                factors += RiskFactor("drift", "Behaviour changed after update", RiskWeights.NEW_BEHAVIOR_AFTER_UPDATE)
            }
            if (Capability.SENSITIVE_FILES in addedCaps || addedSensitive.isNotEmpty()) {
                factors += RiskFactor("drift.sensitive", "New sensitive-file access", RiskWeights.CREDENTIAL_STORE)
            }
            if (Capability.SECRET_ENVIRONMENT in addedCaps) {
                factors += RiskFactor("drift.secretenv", "New secret environment access", RiskWeights.SECRET_ENVIRONMENT)
            }
            if (Capability.PROCESS_EXECUTION in addedCaps || addedProcs.isNotEmpty()) {
                factors += RiskFactor("drift.process", "New process execution", RiskWeights.PROCESS_EXECUTION)
                if (addedProcs.any { ProcessClassifier.isHighRisk(it) }) {
                    factors += RiskFactor("drift.process.highrisk", "New shell / interpreter launch", RiskWeights.HIGH_RISK_EXECUTABLE)
                }
            }
            if (addedHosts.isNotEmpty()) {
                factors += RiskFactor("drift.network", "New network destination", RiskWeights.NEW_DESTINATION)
                if (addedHosts.any { NetworkClassifier.isRawIp(it) }) {
                    factors += RiskFactor("drift.network.rawip", "New raw IP destination", RiskWeights.RAW_IP)
                }
            }
            if (Capability.FILES_OUTSIDE_PROJECT in addedCaps) {
                factors += RiskFactor("drift.outside", "New access outside the project", RiskWeights.OUTSIDE_PROJECT)
            }
            val score = RiskWeights.clamp(factors.sumOf { it.points })
            return BehaviorDrift(
                pluginId = current.pluginId,
                pluginName = current.pluginName.ifBlank { previous.pluginName },
                oldVersion = previous.version,
                newVersion = current.version,
                addedCapabilities = addedCaps,
                removedCapabilities = removedCaps,
                addedHosts = addedHosts,
                addedProcesses = addedProcs,
                addedSensitiveResources = addedSensitive,
                riskScore = score,
                riskLevel = FenceRisk.fromScore(score),
                detectedAt = now,
                riskFactors = factors,
            )
        }
    }
}
