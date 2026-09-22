package com.pluginfence.ai

import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import com.pluginfence.model.IncidentKind
import com.pluginfence.model.PolicyDecision

/**
 * The analysis PluginFence can always produce - with no model, no key and no network.
 *
 * It is not a mock. Every sentence below is derived from evidence the engine already computed:
 * the correlated chain, the deterministic risk factors, the behaviour baseline and the drift
 * report. A language model writes this more fluently and can weigh a plugin's manifest against its
 * behaviour, but it is not the source of the finding - which is exactly the claim PluginFence
 * makes about AI generally.
 *
 * Used when no model is configured, when every model in the fallback chain fails, and as the
 * instant answer while a remote model is still thinking.
 */
object DeterministicAnalyst {

    const val MODEL_NAME = "PluginFence rules (no model)"

    fun analyse(engine: FenceEngine, task: AnalysisTask, startedAt: Long = System.currentTimeMillis(), note: String? = null): AnalysisResult = when (task) {
        is AnalysisTask.Incident -> incident(engine, task, startedAt, note)
        is AnalysisTask.UpdateReview -> update(engine, task, startedAt, note)
        is AnalysisTask.TrustReport -> trust(engine, task, startedAt, note)
    }

    // --- incident ---------------------------------------------------------------------------------

    private fun incident(engine: FenceEngine, task: AnalysisTask.Incident, startedAt: Long, note: String?): AnalysisResult {
        val incident = engine.incident(task.incidentId)
            ?: return empty(task, startedAt, "That incident is no longer in the recorded history.", note)
        val chain = incident.chain
        val sensitive = chain.firstOrNull { it.capability == Capability.SENSITIVE_FILES || it.capability == Capability.SECRET_ENVIRONMENT }
        val network = chain.firstOrNull { it.capability == Capability.NETWORK }
        val process = chain.firstOrNull { it.capability == Capability.PROCESS_EXECUTION }
        val prevented = chain.filter { it.prevented }
        val drift = engine.baselines.drift(task.pluginId)
        val newBehaviour = chain.any { e -> e.riskFactors.any { it.id == "drift" } }

        val correlated = incident.kind == IncidentKind.POTENTIAL_SECRET_EXFILTRATION || incident.kind == IncidentKind.SECRET_ACCESS_THEN_PROCESS
        val verdict = when {
            correlated && newBehaviour -> AnalystVerdict.MALICIOUS
            correlated -> AnalystVerdict.SUSPICIOUS
            incident.riskLevel >= FenceRisk.HIGH -> AnalystVerdict.SUSPICIOUS
            else -> AnalystVerdict.INCONCLUSIVE
        }
        val confidence = if (correlated && sensitive != null) Confidence.HIGH else if (incident.riskLevel >= FenceRisk.HIGH) Confidence.MEDIUM else Confidence.LOW

        val what = sensitive?.let { describeTarget(engine, it) } ?: "a governed resource"
        val headline = when {
            correlated && network != null -> "${task.pluginName} read $what, then immediately tried to reach ${network.target}"
            correlated && process != null -> "${task.pluginName} read $what, then immediately launched ${process.target}"
            sensitive != null -> "${task.pluginName} tried to read $what"
            else -> incident.title
        }

        val narrative = buildString {
            append(incident.summary.trimEnd('.')).append(". ")
            if (prevented.isNotEmpty()) {
                append("PluginFence prevented ")
                append(if (prevented.size == chain.size) "every step" else "${prevented.size} of the ${chain.size} steps")
                append(", so nothing was read or sent. ")
            }
            if (correlated) {
                val gap = chain.lastOrNull()?.let { last -> sensitive?.let { (last.timestamp - it.timestamp) / 1000.0 } }
                append("Credential access followed within ")
                append(gap?.let { "%.1f s".format(it) } ?: "the correlation window")
                append(" by a previously unseen destination is the pattern credential-stealing plugins use; that is why the score is ")
                append(incident.riskScore).append("/100. ")
            }
            if (newBehaviour && drift != null) {
                append("Version ${drift.newVersion} is also doing things version ${drift.oldVersion} never did, so this is new behaviour introduced by an update rather than something the plugin always needed. ")
            }
            append("This summary is derived from PluginFence's own recorded evidence.")
        }

        val evidence = chain.map { e ->
            "${e.actionLabel} ${describeTarget(engine, e)} - ${e.decisionLabel}" +
                (e.riskFactors.takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") { "${it.label} +${it.points}" } ?: "")
        }

        val recommendations = recommendations(engine, task.pluginId, sensitive != null, network, process)
        val nextSteps = buildList {
            if (correlated) add("Treat this plugin as untrusted until you know why it needs ${network?.target ?: "an external endpoint"}.")
            if (sensitive != null && !sensitive.prevented) add("Rotate the credential it reached: ${describeTarget(engine, sensitive)}.")
            if (drift?.hasChanges == true) add("Compare ${drift.oldVersion} and ${drift.newVersion} on the Drift tab before keeping the update.")
            if (isEmpty()) add("No action needed unless this plugin is not expected to touch these resources.")
        }

        return AnalysisResult(
            task = task,
            model = MODEL_NAME,
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            verdict = verdict,
            confidence = confidence,
            headline = headline,
            narrative = narrative,
            evidence = evidence,
            recommendations = recommendations,
            targetChanges = emptyList(),
            nextSteps = nextSteps,
            trace = trace(note),
            deterministic = true,
        )
    }

    // --- update review ------------------------------------------------------------------------------

    private fun update(engine: FenceEngine, task: AnalysisTask.UpdateReview, startedAt: Long, note: String?): AnalysisResult {
        val drift = engine.baselines.drift(task.pluginId)
            ?: return empty(task, startedAt, "No drift recorded between these versions.", note)
        val dangerous = Capability.SENSITIVE_FILES in drift.addedCapabilities || Capability.SECRET_ENVIRONMENT in drift.addedCapabilities
        val verdict = when {
            dangerous -> AnalystVerdict.MALICIOUS
            drift.highRisk -> AnalystVerdict.SUSPICIOUS
            drift.hasChanges -> AnalystVerdict.INCONCLUSIVE
            else -> AnalystVerdict.LIKELY_BENIGN
        }
        val additions = buildList {
            drift.addedSensitiveResources.forEach { add("sensitive resource access ($it)") }
            drift.addedHosts.forEach { add("network destination $it") }
            drift.addedProcesses.forEach { add("process execution: $it") }
            drift.addedCapabilities.forEach { add(it.displayName.lowercase()) }
        }.distinct()

        return AnalysisResult(
            task = task,
            model = MODEL_NAME,
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            verdict = verdict,
            confidence = if (dangerous) Confidence.HIGH else Confidence.MEDIUM,
            headline = "${task.newVersion} gained ${drift.newCapabilityCount} capabilit${if (drift.newCapabilityCount == 1) "y" else "ies"} that ${task.oldVersion} never used",
            narrative = "Between ${task.oldVersion} and ${task.newVersion}, ${task.pluginName} started doing things the previous " +
                "version never did: ${additions.joinToString(", ")}. PluginFence rates that ${drift.riskScore}/100 " +
                "(${drift.riskLevel.label}). An update that quietly widens what a plugin touches is the pattern behind " +
                "most compromised-plugin incidents, so the additions are worth justifying one by one. " +
                "This summary is derived from PluginFence's own recorded evidence.",
            evidence = drift.riskFactors.map { "${it.label} (+${it.points})" } + additions.map { "New: $it" },
            recommendations = recommendations(engine, task.pluginId, dangerous, null, null),
            targetChanges = drift.addedHosts.map { TargetChange(false, Capability.NETWORK, it, "New destination introduced by ${task.newVersion}") },
            nextSteps = listOf("Review the additions on the Drift tab.", "If they are not justified, keep the previous version."),
            trace = trace(note),
            deterministic = true,
        )
    }

    // --- trust report ---------------------------------------------------------------------------------

    private fun trust(engine: FenceEngine, task: AnalysisTask.TrustReport, startedAt: Long, note: String?): AnalysisResult {
        val profiles = engine.profiles(task.pluginId)
        val events = engine.events().filter { it.pluginId == task.pluginId }
        if (profiles.isEmpty() && events.isEmpty()) return empty(task, startedAt, "PluginFence has not observed this plugin doing anything yet.", note)

        val prevented = events.count { it.prevented }
        val capabilities = profiles.flatMap { it.capabilities }.toSet()
        val hosts = profiles.flatMap { it.networkHosts }.toSet()
        val sensitive = Capability.SENSITIVE_FILES in capabilities || Capability.SECRET_ENVIRONMENT in capabilities
        val verdict = when {
            sensitive -> AnalystVerdict.SUSPICIOUS
            prevented > 0 -> AnalystVerdict.INCONCLUSIVE
            events.isNotEmpty() -> AnalystVerdict.LIKELY_BENIGN
            else -> AnalystVerdict.INCONCLUSIVE
        }
        return AnalysisResult(
            task = task,
            model = MODEL_NAME,
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            verdict = verdict,
            confidence = if (events.size >= 5) Confidence.MEDIUM else Confidence.LOW,
            headline = if (sensitive) "${task.pluginName} has reached for credential material" else "${task.pluginName} has stayed within ordinary developer-tool behaviour",
            narrative = "Across ${events.size} observed operation${if (events.size == 1) "" else "s"} " +
                "(${profiles.size} version${if (profiles.size == 1) "" else "s"}), this plugin used: " +
                "${capabilities.joinToString { it.displayName.lowercase() }.ifBlank { "nothing governed" }}. " +
                (if (hosts.isNotEmpty()) "It contacted ${hosts.joinToString()}. " else "It made no outbound connections. ") +
                (if (prevented > 0) "$prevented operation${if (prevented == 1) " was" else "s were"} prevented by policy. " else "Nothing was prevented. ") +
                "This summary is derived from PluginFence's own recorded evidence.",
            evidence = capabilities.map { "Used capability: ${it.displayName}" } + hosts.map { "Contacted $it" },
            recommendations = recommendations(engine, task.pluginId, sensitive, null, null),
            targetChanges = emptyList(),
            nextSteps = listOf("Tighten anything on this list the plugin does not need for its stated purpose."),
            trace = trace(note),
            deterministic = true,
        )
    }

    // --- shared -----------------------------------------------------------------------------------------

    /** Least privilege: propose BLOCK/ASK only where it differs from what is already configured. */
    private fun recommendations(engine: FenceEngine, pluginId: String, sensitive: Boolean, network: FenceEvent?, process: FenceEvent?): List<Recommendation> {
        val out = ArrayList<Recommendation>()
        fun propose(capability: Capability, decision: PolicyDecision, reason: String) {
            if (engine.policies.effective(pluginId, capability) != decision) out += Recommendation(capability, decision, reason)
        }
        if (sensitive) propose(Capability.SENSITIVE_FILES, PolicyDecision.BLOCK, "It reached for credential material; no developer tool needs that silently.")
        if (network != null) propose(Capability.NETWORK, PolicyDecision.BLOCK, "It attempted ${network.target} right after touching sensitive data.")
        if (process != null) propose(Capability.PROCESS_EXECUTION, PolicyDecision.ASK, "It launched ${process.target}; approve each launch instead of allowing all.")
        return out
    }

    private fun describeTarget(engine: FenceEngine, event: FenceEvent): String =
        if (event.capability == Capability.SECRET_ENVIRONMENT) event.target else engine.sensitivePaths.displayPath(event.target)

    private fun trace(note: String?): List<TraceStep> = buildList {
        if (note != null) add(TraceStep(TraceStep.Kind.MESSAGE, "Fell back to PluginFence's own rules", note))
        add(TraceStep(TraceStep.Kind.TOOL, "Reading recorded evidence", "incident chain, risk factors, baseline and drift"))
        add(TraceStep(TraceStep.Kind.FINAL, "Analysis complete", "derived deterministically, no model involved"))
    }

    private fun empty(task: AnalysisTask, startedAt: Long, why: String, note: String?) = AnalysisResult(
        task, MODEL_NAME, startedAt, System.currentTimeMillis() - startedAt,
        AnalystVerdict.INCONCLUSIVE, Confidence.LOW, "Not enough evidence yet", why,
        emptyList(), emptyList(), emptyList(), listOf("Exercise the plugin, then analyse again."), trace(note), deterministic = true,
    )
}
