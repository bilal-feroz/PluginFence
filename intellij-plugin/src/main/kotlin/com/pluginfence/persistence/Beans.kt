package com.pluginfence.persistence

import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import com.pluginfence.model.IncidentKind
import com.pluginfence.model.PathRelation
import com.pluginfence.model.PluginPolicy
import com.pluginfence.model.PolicyDecision
import com.pluginfence.model.RiskFactor

// Flat, default-constructible beans for IntelliJ's XML serializer. Conversion helpers keep the
// domain model immutable and the persistence format boring. Nothing here ever holds a secret value.

@Tag("override")
class OverrideBean {
    var capability: String = ""
    var decision: String = ""
}

@Tag("approval")
class ApprovalBean {
    var capability: String = ""
    var target: String = ""
}

@Tag("policy")
class PolicyBean {
    var pluginId: String = ""
    @XCollection(style = XCollection.Style.v2)
    var overrides: MutableList<OverrideBean> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var approvals: MutableList<ApprovalBean> = mutableListOf()
}

@Tag("factor")
class FactorBean {
    var id: String = ""
    var label: String = ""
    var points: Int = 0
}

@Tag("profile")
class ProfileBean {
    var pluginId: String = ""
    var pluginName: String = ""
    var version: String = ""
    @XCollection(style = XCollection.Style.v2)
    var capabilities: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var hosts: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var processes: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var sensitiveResources: MutableList<String> = mutableListOf()
    var firstSeen: Long = 0
    var lastSeen: Long = 0
    var eventCount: Long = 0
}

@Tag("drift")
class DriftBean {
    var pluginId: String = ""
    var pluginName: String = ""
    var oldVersion: String = ""
    var newVersion: String = ""
    @XCollection(style = XCollection.Style.v2)
    var addedCapabilities: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var removedCapabilities: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var addedHosts: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var addedProcesses: MutableList<String> = mutableListOf()
    @XCollection(style = XCollection.Style.v2)
    var addedSensitiveResources: MutableList<String> = mutableListOf()
    var riskScore: Int = 0
    var detectedAt: Long = 0
    @XCollection(style = XCollection.Style.v2)
    var factors: MutableList<FactorBean> = mutableListOf()
}

@Tag("meta")
class MetaBean {
    var key: String = ""
    var value: String = ""
}

@Tag("event")
class EventBean {
    var id: Long = 0
    var timestamp: Long = 0
    var pluginId: String = ""
    var pluginName: String = ""
    var pluginVersion: String = ""
    var pluginKnown: Boolean = false
    var sourceClass: String = ""
    var operation: String = ""
    var capability: String = ""
    var target: String = ""
    var approvalTarget: String = ""
    var api: String = ""
    var verdict: String = ""
    var reason: String = ""
    var ruleId: String = ""
    var riskScore: Int = 0
    @XCollection(style = XCollection.Style.v2)
    var factors: MutableList<FactorBean> = mutableListOf()
    var sensitiveCategory: String = ""
    var pathRelation: String = ""
    @XCollection(style = XCollection.Style.v2)
    var metadata: MutableList<MetaBean> = mutableListOf()
    var agentMode: Boolean = true
}

@Tag("incident")
class IncidentBean {
    var id: String = ""
    var timestamp: Long = 0
    var kind: String = ""
    var pluginId: String = ""
    var pluginName: String = ""
    var pluginVersion: String = ""
    var summary: String = ""
    var outcome: String = ""
    var riskScore: Int = 0
    @XCollection(style = XCollection.Style.v2)
    var chain: MutableList<EventBean> = mutableListOf()
}

// --- conversions -------------------------------------------------------------------------------

fun PluginPolicy.toBean(): PolicyBean = PolicyBean().also { b ->
    b.pluginId = pluginId
    b.overrides = overrides.map { (c, d) -> OverrideBean().apply { capability = c.name; decision = d.name } }.toMutableList()
    b.approvals = approvedTargets.flatMap { (c, targets) -> targets.map { t -> ApprovalBean().apply { capability = c.name; target = t } } }.toMutableList()
}

fun PolicyBean.toModel(): PluginPolicy = PluginPolicy(
    pluginId = pluginId,
    overrides = overrides.mapNotNull { o -> enumOrNull<Capability>(o.capability)?.let { c -> enumOrNull<PolicyDecision>(o.decision)?.let { c to it } } }.toMap(),
    approvedTargets = approvals.mapNotNull { a -> enumOrNull<Capability>(a.capability)?.let { it to a.target } }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
)

fun BehaviorProfile.toBean(): ProfileBean = ProfileBean().also { b ->
    b.pluginId = pluginId; b.pluginName = pluginName; b.version = version
    b.capabilities = capabilities.map { it.name }.toMutableList()
    b.hosts = networkHosts.toMutableList(); b.processes = processes.toMutableList()
    b.sensitiveResources = sensitiveResources.toMutableList()
    b.firstSeen = firstSeen; b.lastSeen = lastSeen; b.eventCount = eventCount
}

fun ProfileBean.toModel(): BehaviorProfile = BehaviorProfile(
    pluginId, pluginName, version,
    capabilities.mapNotNull { enumOrNull<Capability>(it) }.toSet(),
    hosts.toSet(), processes.toSet(), sensitiveResources.toSet(), firstSeen, lastSeen, eventCount,
)

fun BehaviorDrift.toBean(): DriftBean = DriftBean().also { b ->
    b.pluginId = pluginId; b.pluginName = pluginName; b.oldVersion = oldVersion; b.newVersion = newVersion
    b.addedCapabilities = addedCapabilities.map { it.name }.toMutableList()
    b.removedCapabilities = removedCapabilities.map { it.name }.toMutableList()
    b.addedHosts = addedHosts.toMutableList(); b.addedProcesses = addedProcesses.toMutableList()
    b.addedSensitiveResources = addedSensitiveResources.toMutableList()
    b.riskScore = riskScore; b.detectedAt = detectedAt
    b.factors = riskFactors.map { it.toBean() }.toMutableList()
}

fun DriftBean.toModel(): BehaviorDrift = BehaviorDrift(
    pluginId, pluginName, oldVersion, newVersion,
    addedCapabilities.mapNotNull { enumOrNull<Capability>(it) }.toSet(),
    removedCapabilities.mapNotNull { enumOrNull<Capability>(it) }.toSet(),
    addedHosts.toSet(), addedProcesses.toSet(), addedSensitiveResources.toSet(),
    riskScore, FenceRisk.fromScore(riskScore), detectedAt, factors.map { it.toModel() },
)

fun RiskFactor.toBean(): FactorBean = FactorBean().also { it.id = id; it.label = label; it.points = points }
fun FactorBean.toModel(): RiskFactor = RiskFactor(id, label, points)

fun FenceEvent.toBean(): EventBean = EventBean().also { b ->
    b.id = id; b.timestamp = timestamp; b.pluginId = pluginId; b.pluginName = pluginName; b.pluginVersion = pluginVersion
    b.pluginKnown = pluginKnown; b.sourceClass = sourceClass; b.operation = operation.name; b.capability = capability?.name ?: ""
    b.target = target; b.approvalTarget = approvalTarget; b.api = api; b.verdict = verdict.name; b.reason = reason; b.ruleId = ruleId; b.riskScore = riskScore
    b.factors = riskFactors.map { it.toBean() }.toMutableList()
    b.sensitiveCategory = sensitiveCategory ?: ""; b.pathRelation = pathRelation?.name ?: ""
    b.metadata = metadata.map { (k, v) -> MetaBean().apply { key = k; value = v } }.toMutableList()
    b.agentMode = agentMode
}

fun EventBean.toModel(): FenceEvent? {
    val op = enumOrNull<FenceOperation>(operation) ?: return null
    val v = enumOrNull<FenceVerdict>(verdict) ?: return null
    return FenceEvent(
        id, timestamp, pluginId, pluginName, pluginVersion, pluginKnown, sourceClass, op,
        enumOrNull<Capability>(capability), target, approvalTarget, api, v, reason, ruleId, riskScore, FenceRisk.fromScore(riskScore),
        factors.map { it.toModel() }, sensitiveCategory.ifBlank { null }, enumOrNull<PathRelation>(pathRelation),
        metadata.associate { it.key to it.value }, agentMode,
    )
}

fun Incident.toBean(): IncidentBean = IncidentBean().also { b ->
    b.id = id; b.timestamp = timestamp; b.kind = kind.name; b.pluginId = pluginId; b.pluginName = pluginName
    b.pluginVersion = pluginVersion; b.summary = summary; b.outcome = outcome; b.riskScore = riskScore
    b.chain = chain.map { it.toBean() }.toMutableList()
}

fun IncidentBean.toModel(): Incident? {
    val k = enumOrNull<IncidentKind>(kind) ?: return null
    return Incident(id, timestamp, k, pluginId, pluginName, pluginVersion, summary, outcome, riskScore,
        FenceRisk.fromScore(riskScore), chain.mapNotNull { it.toModel() })
}

inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
    if (name.isBlank()) null else runCatching { enumValueOf<E>(name) }.getOrNull()
