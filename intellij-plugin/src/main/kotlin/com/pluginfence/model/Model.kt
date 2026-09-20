package com.pluginfence.model

// The model deliberately has no dependency on the bootstrap module: the plugin must load, show
// its UI and read its history even when the agent (and therefore the boot-class-path types) is
// absent. Bootstrap types are converted at the provider boundary in FenceEngine.

/** Mirrors com.pluginfence.bootstrap.OperationType. */
enum class FenceOperation(val label: String) {
    FILE_READ("File read"),
    FILE_WRITE("File write"),
    ENV_READ("Env read"),
    ENV_ENUMERATE("Env enumerate"),
    PROCESS_EXEC("Process"),
    NETWORK_CONNECT("Network");
}

/** Mirrors com.pluginfence.bootstrap.Verdict. */
enum class FenceVerdict(val label: String) {
    ALLOW("ALLOWED"), MONITOR("MONITORED"), ASK("ASK (prevented)"), BLOCK("BLOCKED");

    val prevents: Boolean get() = this == ASK || this == BLOCK
}

/** Mirrors com.pluginfence.bootstrap.RiskLevel. */
enum class FenceRisk(val label: String) {
    INFO("Info"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), CRITICAL("Critical");

    companion object {
        fun fromScore(score: Int): FenceRisk = when {
            score >= 80 -> CRITICAL
            score >= 60 -> HIGH
            score >= 40 -> MEDIUM
            score >= 20 -> LOW
            else -> INFO
        }
    }
}

/** Plugin-side view of an intercepted operation (already redacted by the bootstrap hooks). */
data class OperationRequest(
    val eventId: Long,
    val timestamp: Long,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginKnown: Boolean,
    val bundled: Boolean,
    val vendor: String?,
    val sourceClass: String,
    val operation: FenceOperation,
    val target: String,
    val api: String,
    val metadata: Map<String, String>,
) {
    val host: String get() = metadata["host"] ?: target.substringBefore(':')
    val scheme: String? get() = metadata["scheme"]
}

/** Outcome of a policy evaluation, converted to a bootstrap SecurityDecision by the provider. */
data class Decision(val verdict: FenceVerdict, val reason: String, val ruleId: String, val riskScore: Int) {
    val riskLevel: FenceRisk get() = FenceRisk.fromScore(riskScore)
    val prevents: Boolean get() = verdict.prevents

    companion object {
        const val MONITOR_RULE = "fallback.monitor"
    }
}

/** The per-plugin permission categories exposed in the Permissions tab. */
enum class Capability(val displayName: String, val description: String) {
    PROJECT_FILES("Project files", "Read or write files inside open projects"),
    FILES_OUTSIDE_PROJECT("Files outside project", "Read or write files outside open projects"),
    SENSITIVE_FILES("Sensitive files", "SSH keys, cloud credentials, .env files, private keys"),
    SECRET_ENVIRONMENT("Environment secrets", "Environment variables that look like credentials"),
    NETWORK("Network", "Outbound connections"),
    PROCESS_EXECUTION("System commands", "Launching external processes");
}

enum class PolicyDecision {
    ALLOW, ASK, BLOCK;

    fun toVerdict(): FenceVerdict = when (this) {
        ALLOW -> FenceVerdict.ALLOW
        ASK -> FenceVerdict.ASK
        BLOCK -> FenceVerdict.BLOCK
    }
}

/** Where a file path sits relative to what the IDE is working on. */
enum class PathRelation {
    INSIDE_PROJECT, IDE_INTERNAL, OUTSIDE_PROJECT
}

/** One deterministic contribution to a risk score; surfaced in event details. */
data class RiskFactor(val id: String, val label: String, val points: Int)

/** A recorded, redacted runtime event. Never contains secret values. */
data class FenceEvent(
    val id: Long,
    val timestamp: Long,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginKnown: Boolean,
    val sourceClass: String,
    val operation: FenceOperation,
    val capability: Capability?,
    val target: String,
    /** Key used by Allow Once / Always Allow (host, executable, directory, variable name). */
    val approvalTarget: String,
    val api: String,
    val verdict: FenceVerdict,
    val reason: String,
    val ruleId: String,
    val riskScore: Int,
    val riskLevel: FenceRisk,
    val riskFactors: List<RiskFactor>,
    val sensitiveCategory: String?,
    val pathRelation: PathRelation?,
    val metadata: Map<String, String>,
    val agentMode: Boolean,
) {
    val prevented: Boolean get() = verdict.prevents
    val displayPlugin: String get() = if (pluginKnown) pluginName else "Unknown third-party plugin"
    val actionLabel: String get() = operation.label
    val decisionLabel: String get() = verdict.label
    val host: String get() = metadata["host"] ?: target.substringBefore(':')
}

enum class IncidentKind(val title: String) {
    POTENTIAL_SECRET_EXFILTRATION("Potential secret exfiltration"),
    SECRET_ACCESS_THEN_PROCESS("Secret access followed by process execution"),
    SENSITIVE_ACCESS_BLOCKED("Sensitive credential access blocked"),
    SECRET_ENVIRONMENT_BLOCKED("Secret environment access blocked"),
    HIGH_RISK_BEHAVIOR_CHANGE("High-risk behaviour change after update"),
}

/** A correlated or otherwise notable security incident, shown on the Overview tab. */
data class Incident(
    val id: String,
    val timestamp: Long,
    val kind: IncidentKind,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val summary: String,
    val outcome: String,
    val riskScore: Int,
    val riskLevel: FenceRisk,
    val chain: List<FenceEvent>,
) {
    val title: String get() = kind.title
}

/** Deterministic behaviour profile of one plugin version (the "baseline"). No ML involved. */
data class BehaviorProfile(
    val pluginId: String,
    val pluginName: String,
    val version: String,
    val capabilities: Set<Capability>,
    val networkHosts: Set<String>,
    val processes: Set<String>,
    val sensitiveResources: Set<String>,
    val firstSeen: Long,
    val lastSeen: Long,
    val eventCount: Long,
) {
    fun with(event: FenceEvent): BehaviorProfile {
        val caps = if (event.capability != null) capabilities + event.capability else capabilities
        val hosts = if (event.operation == FenceOperation.NETWORK_CONNECT && event.host.isNotBlank()) networkHosts + event.host else networkHosts
        val procs = if (event.operation == FenceOperation.PROCESS_EXEC && event.target.isNotBlank()) processes + event.target else processes
        val sensitive = if (event.sensitiveCategory != null) sensitiveResources + event.sensitiveCategory else sensitiveResources
        return copy(
            pluginName = event.pluginName.ifBlank { pluginName },
            capabilities = caps,
            networkHosts = hosts,
            processes = procs,
            sensitiveResources = sensitive,
            firstSeen = minOf(firstSeen, event.timestamp),
            lastSeen = maxOf(lastSeen, event.timestamp),
            eventCount = eventCount + 1,
        )
    }

    companion object {
        fun empty(pluginId: String, pluginName: String, version: String, now: Long) = BehaviorProfile(
            pluginId, pluginName, version, emptySet(), emptySet(), emptySet(), emptySet(), now, now, 0,
        )
    }
}

/** What a newer plugin version does that the previous one never did. */
data class BehaviorDrift(
    val pluginId: String,
    val pluginName: String,
    val oldVersion: String,
    val newVersion: String,
    val addedCapabilities: Set<Capability>,
    val removedCapabilities: Set<Capability>,
    val addedHosts: Set<String>,
    val addedProcesses: Set<String>,
    val addedSensitiveResources: Set<String>,
    val riskScore: Int,
    val riskLevel: FenceRisk,
    val detectedAt: Long,
    val riskFactors: List<RiskFactor>,
) {
    val newCapabilityCount: Int
        get() = addedCapabilities.size + addedHosts.size + addedProcesses.size + addedSensitiveResources.size
    val highRisk: Boolean get() = riskLevel >= FenceRisk.HIGH
    /** Only additions count as drift; a capability not (yet) exercised by the new version is informational. */
    val hasChanges: Boolean get() = newCapabilityCount > 0
}

/** User-configured policy for one plugin. Absent capabilities fall back to the defaults. */
data class PluginPolicy(
    val pluginId: String,
    val overrides: Map<Capability, PolicyDecision> = emptyMap(),
    val approvedTargets: Map<Capability, Set<String>> = emptyMap(),
) {
    fun isApproved(capability: Capability, target: String): Boolean =
        approvedTargets[capability]?.any { it.equals(target, ignoreCase = true) } == true
}

/** An installed plugin as listed on the Permissions tab. */
data class PluginInfo(
    val pluginId: String,
    val name: String,
    val version: String,
    val vendor: String,
    val bundled: Boolean,
    val trusted: Boolean,
    val enabled: Boolean,
)

data class FenceStats(
    val agentInstalled: Boolean,
    val providerRegistered: Boolean,
    val enforcementEnabled: Boolean,
    val pluginsMonitored: Int,
    val eventsRecorded: Long,
    val blockedAttempts: Long,
    val behaviorChanges: Int,
    val criticalIncidents: Int,
    val agentInfo: String,
    val agentDiagnostics: Map<String, String>,
)
