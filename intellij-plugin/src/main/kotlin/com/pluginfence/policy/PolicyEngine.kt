package com.pluginfence.policy

import com.pluginfence.classify.NetworkClassifier
import com.pluginfence.classify.PathScope
import com.pluginfence.classify.ProcessClassifier
import com.pluginfence.classify.SecretEnvClassifier
import com.pluginfence.classify.SensitivePathClassifier
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.Decision
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.OperationRequest
import com.pluginfence.model.PathRelation
import com.pluginfence.model.PluginPolicy
import com.pluginfence.model.PolicyDecision
import com.pluginfence.model.RiskFactor
import java.util.Locale

/** Deterministic risk weights. Documented in the README; tuned for clarity, not learned. */
object RiskWeights {
    const val CREDENTIAL_STORE = 60
    const val SENSITIVE_FILE = 50
    const val SECRET_ENVIRONMENT = 45
    const val PROCESS_EXECUTION = 25
    const val HIGH_RISK_EXECUTABLE = 15
    const val NEW_DESTINATION = 20
    const val RAW_IP = 20
    const val PLAINTEXT = 20
    const val OUTSIDE_PROJECT = 15
    const val NEW_BEHAVIOR_AFTER_UPDATE = 20
    const val SECRET_THEN_EXFIL = 50
    const val ENV_ENUMERATION = 10

    fun clamp(points: Int): Int = points.coerceIn(0, 100)
}

/** Default per-capability policy applied to every non-trusted plugin unless overridden. */
object DefaultPolicies {
    val DEFAULTS: Map<Capability, PolicyDecision> = mapOf(
        Capability.PROJECT_FILES to PolicyDecision.ALLOW,
        Capability.FILES_OUTSIDE_PROJECT to PolicyDecision.ASK,
        Capability.SENSITIVE_FILES to PolicyDecision.BLOCK,
        Capability.SECRET_ENVIRONMENT to PolicyDecision.BLOCK,
        Capability.NETWORK to PolicyDecision.ASK,
        Capability.PROCESS_EXECUTION to PolicyDecision.ASK,
    )

    fun of(capability: Capability): PolicyDecision = DEFAULTS.getValue(capability)
}

/** Window in which "sensitive access then network/process" counts as correlated. */
const val CORRELATION_WINDOW_MS = 10_000L

interface PolicyLookup {
    fun policy(pluginId: String): PluginPolicy?
}

interface GrantLookup {
    /** Consumes a one-time grant if present. */
    fun consumeOnce(pluginId: String, capability: Capability, target: String): Boolean
}

interface BaselineLookup {
    /** Profile of the most recently seen *other* version of the plugin, if any. */
    fun previousVersionProfile(pluginId: String, currentVersion: String): BehaviorProfile?

    /** Whether any version of the plugin has been observed contacting this host. */
    fun isKnownHost(pluginId: String, host: String): Boolean
}

interface CorrelationLookup {
    fun recentSensitiveAccess(pluginId: String, now: Long, windowMs: Long): FenceEvent?
}

/** Everything the policy engine derived for one request. */
data class Evaluation(
    val capability: Capability?,
    val decision: Decision,
    val riskFactors: List<RiskFactor>,
    val sensitiveMatch: SensitivePathClassifier.Match?,
    val pathRelation: PathRelation?,
    val correlatedWith: FenceEvent?,
    val newBehavior: Boolean,
    /** Key used for Allow Once / Always Allow (host, executable, directory, variable name). */
    val approvalTarget: String,
)

/**
 * Pure, synchronous policy evaluation. Runs on the intercepted thread, so it only does
 * in-memory lookups. Precedence (highest first):
 *
 * 1. trusted / exempt plugin (PluginFence itself, bundled or JetBrains plugins) -> allow, monitor
 * 2. one-time grant ("Allow Once")
 * 3. explicit user BLOCK for the capability
 * 4. explicit user ALLOW for the capability
 * 5. explicit approval of the target ("Always Allow")
 * 6. sensitive credential file  (default BLOCK)
 * 7. secret environment variable (default BLOCK)
 * 8. correlated exfiltration: network/process within 10 s of sensitive access -> BLOCK
 * 9. process execution (default ASK)
 * 10. project file (default ALLOW), IDE-internal path (allow)
 * 11. file outside project (default ASK)
 * 12. network: loopback allowed, otherwise default ASK
 * 13. fallback: allow and monitor
 */
class PolicyEngine(
    private val sensitivePaths: SensitivePathClassifier,
    private val secretEnv: SecretEnvClassifier,
    private val pathScope: () -> PathScope,
    private val policies: PolicyLookup,
    private val grants: GrantLookup,
    private val baseline: BaselineLookup,
    private val correlation: CorrelationLookup,
) {

    fun evaluate(request: OperationRequest, trusted: Boolean, consumeGrants: Boolean = true): Evaluation {
        val pluginId = request.pluginId
        val factors = ArrayList<RiskFactor>(6)

        // ---- classification ---------------------------------------------------------------
        var capability: Capability? = null
        var sensitiveMatch: SensitivePathClassifier.Match? = null
        var pathRelation: PathRelation? = null
        var approvalTarget = request.target
        val host = request.host

        when (request.operation) {
            FenceOperation.FILE_READ, FenceOperation.FILE_WRITE -> {
                sensitiveMatch = sensitivePaths.classify(request.target)
                pathRelation = pathScope().relation(request.target)
                when {
                    sensitiveMatch != null -> {
                        capability = Capability.SENSITIVE_FILES
                        factors += if (sensitiveMatch.credentialStore) {
                            RiskFactor("sensitive.credential", "Credential store: ${sensitiveMatch.label}", RiskWeights.CREDENTIAL_STORE)
                        } else {
                            RiskFactor("sensitive.file", "Sensitive file: ${sensitiveMatch.label}", RiskWeights.SENSITIVE_FILE)
                        }
                    }
                    pathRelation == PathRelation.INSIDE_PROJECT -> capability = Capability.PROJECT_FILES
                    pathRelation == PathRelation.IDE_INTERNAL -> capability = null
                    else -> {
                        capability = Capability.FILES_OUTSIDE_PROJECT
                        approvalTarget = parentDirectory(request.target)
                        factors += RiskFactor("file.outside", "File outside open projects", RiskWeights.OUTSIDE_PROJECT)
                    }
                }
            }
            FenceOperation.ENV_READ -> {
                if (secretEnv.isSecret(request.target)) {
                    capability = Capability.SECRET_ENVIRONMENT
                    factors += RiskFactor("env.secret", "Secret environment variable", RiskWeights.SECRET_ENVIRONMENT)
                }
            }
            FenceOperation.ENV_ENUMERATE -> {
                capability = Capability.SECRET_ENVIRONMENT
                approvalTarget = "*"
                factors += RiskFactor("env.enumerate", "Whole environment enumerated", RiskWeights.ENV_ENUMERATION)
            }
            FenceOperation.PROCESS_EXEC -> {
                capability = Capability.PROCESS_EXECUTION
                approvalTarget = request.target.lowercase(Locale.ROOT)
                factors += RiskFactor("process", "Process execution", RiskWeights.PROCESS_EXECUTION)
                if (ProcessClassifier.isHighRisk(request.target)) {
                    factors += RiskFactor("process.highrisk", "Shell / interpreter / downloader", RiskWeights.HIGH_RISK_EXECUTABLE)
                }
            }
            FenceOperation.NETWORK_CONNECT -> {
                capability = Capability.NETWORK
                approvalTarget = NetworkClassifier.normalizeHost(host)
                val policy = policies.policy(pluginId)
                val loopback = NetworkClassifier.isLoopback(host)
                if (!loopback && !baseline.isKnownHost(pluginId, approvalTarget) && policy?.isApproved(Capability.NETWORK, approvalTarget) != true) {
                    factors += RiskFactor("network.new", "New network destination", RiskWeights.NEW_DESTINATION)
                }
                if (!loopback && NetworkClassifier.isRawIp(host)) {
                    factors += RiskFactor("network.rawip", "Raw IP destination", RiskWeights.RAW_IP)
                }
                if (!loopback && NetworkClassifier.isPlaintext(request.scheme)) {
                    factors += RiskFactor("network.plaintext", "Plaintext protocol", RiskWeights.PLAINTEXT)
                }
            }
        }

        // ---- context: behaviour drift and correlation ---------------------------------------
        val previous = baseline.previousVersionProfile(pluginId, request.pluginVersion)
        val newBehavior = previous != null && capability != null && !previous.hasObserved(capability, request, sensitiveMatch)
        if (newBehavior && previous != null) {
            factors += RiskFactor("drift", "New behaviour after update (${previous.version} -> ${request.pluginVersion})", RiskWeights.NEW_BEHAVIOR_AFTER_UPDATE)
        }
        val correlated = if (request.operation == FenceOperation.NETWORK_CONNECT || request.operation == FenceOperation.PROCESS_EXEC) {
            correlation.recentSensitiveAccess(pluginId, request.timestamp, CORRELATION_WINDOW_MS)
        } else null
        if (correlated != null) {
            factors += RiskFactor("correlation", "Follows sensitive access within ${CORRELATION_WINDOW_MS / 1000} s", RiskWeights.SECRET_THEN_EXFIL)
        }
        val score = RiskWeights.clamp(factors.sumOf { it.points })

        // ---- verdict ---------------------------------------------------------------------------
        val decision = decide(request, capability, approvalTarget, trusted, consumeGrants, correlated != null, sensitiveMatch, pathRelation, score)
        return Evaluation(capability, decision, factors, sensitiveMatch, pathRelation, correlated, newBehavior, approvalTarget)
    }

    private fun decide(
        request: OperationRequest,
        capability: Capability?,
        approvalTarget: String,
        trusted: Boolean,
        consumeGrants: Boolean,
        correlated: Boolean,
        sensitiveMatch: SensitivePathClassifier.Match?,
        pathRelation: PathRelation?,
        score: Int,
    ): Decision {
        val pluginId = request.pluginId
        if (trusted) {
            return Decision(FenceVerdict.ALLOW, "Trusted platform plugin (monitored only)", "exempt.trusted", score)
        }
        if (capability == null) {
            val why = if (pathRelation == PathRelation.IDE_INTERNAL) "IDE-managed location" else "Not a governed resource"
            return Decision(FenceVerdict.ALLOW, why, "monitor.ungoverned", score)
        }
        if (consumeGrants && grants.consumeOnce(pluginId, capability, approvalTarget)) {
            return Decision(FenceVerdict.ALLOW, "Allowed once by the user", "grant.once", score)
        }
        val policy = policies.policy(pluginId)
        val override = policy?.overrides?.get(capability)
        if (override == PolicyDecision.BLOCK) {
            return Decision(FenceVerdict.BLOCK, "${capability.displayName}: blocked by user policy", "policy.user.block", score)
        }
        if (override == PolicyDecision.ALLOW) {
            return Decision(FenceVerdict.ALLOW, "${capability.displayName}: allowed by user policy", "policy.user.allow", score)
        }
        if (policy?.isApproved(capability, approvalTarget) == true) {
            return Decision(FenceVerdict.ALLOW, "Always allowed by the user for $approvalTarget", "approval.target", score)
        }
        val effective = override ?: DefaultPolicies.of(capability)
        val source = if (override != null) "user" else "default"
        return when (capability) {
            Capability.SENSITIVE_FILES -> apply(effective, "Sensitive credential file (${sensitiveMatch?.label ?: "sensitive"})", "sensitive.$source", score)
            Capability.SECRET_ENVIRONMENT -> apply(effective, if (request.operation == FenceOperation.ENV_ENUMERATE) "Environment enumeration" else "Secret environment variable", "secretenv.$source", score)
            Capability.NETWORK, Capability.PROCESS_EXECUTION -> {
                if (correlated) {
                    val what = if (capability == Capability.NETWORK) "New network destination right after sensitive access" else "Process launched right after sensitive access"
                    return Decision(FenceVerdict.BLOCK, what, "correlation.exfiltration", score)
                }
                if (capability == Capability.PROCESS_EXECUTION) {
                    apply(effective, "Process execution", "process.$source", score)
                } else if (NetworkClassifier.isLoopback(request.host)) {
                    Decision(FenceVerdict.ALLOW, "Loopback destination", "network.loopback", score)
                } else {
                    apply(effective, "Network destination $approvalTarget", "network.$source", score)
                }
            }
            Capability.PROJECT_FILES -> apply(effective, "Project file", "project.$source", score)
            Capability.FILES_OUTSIDE_PROJECT -> apply(effective, "File outside open projects", "outside.$source", score)
        }
    }

    private fun apply(decision: PolicyDecision, what: String, ruleId: String, score: Int): Decision =
        when (decision) {
            PolicyDecision.ALLOW -> Decision(FenceVerdict.ALLOW, "$what allowed", ruleId, score)
            PolicyDecision.ASK -> Decision(FenceVerdict.ASK, "$what requires permission", ruleId, score)
            PolicyDecision.BLOCK -> Decision(FenceVerdict.BLOCK, "$what blocked", ruleId, score)
        }

    companion object {
        fun parentDirectory(path: String): String {
            val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (cut > 0) path.substring(0, cut) else path
        }
    }
}

/** True if the profile already contains this kind of behaviour. */
fun BehaviorProfile.hasObserved(capability: Capability, request: OperationRequest, match: SensitivePathClassifier.Match?): Boolean {
    if (capability !in capabilities) return false
    return when (request.operation) {
        FenceOperation.NETWORK_CONNECT -> {
            val host = NetworkClassifier.normalizeHost(request.host)
            networkHosts.any { NetworkClassifier.normalizeHost(it) == host }
        }
        FenceOperation.PROCESS_EXEC -> processes.any { it.equals(request.target, ignoreCase = true) }
        FenceOperation.FILE_READ, FenceOperation.FILE_WRITE -> match == null || match.category in sensitiveResources
        else -> true
    }
}
