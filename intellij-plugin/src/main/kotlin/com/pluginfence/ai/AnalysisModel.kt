package com.pluginfence.ai

import com.pluginfence.model.Capability
import com.pluginfence.model.PolicyDecision

/** What the analyst is asked to look at. Each task maps to one entry point in the UI. */
sealed class AnalysisTask {
    abstract val key: String
    abstract val pluginId: String
    abstract val title: String

    /** "What just happened, and is it an attack?" - from the Overview incident detail. */
    data class Incident(val incidentId: String, override val pluginId: String, val pluginName: String) : AnalysisTask() {
        override val key get() = "incident:$incidentId"
        override val title get() = "Incident analysis"
    }

    /** "Should I trust this update?" - from the Drift tab. */
    data class UpdateReview(override val pluginId: String, val pluginName: String, val oldVersion: String, val newVersion: String) : AnalysisTask() {
        override val key get() = "update:$pluginId:$oldVersion:$newVersion"
        override val title get() = "Update review"
    }

    /** "Is this plugin's current permission set sensible?" - from the Permissions tab. */
    data class TrustReport(override val pluginId: String, val pluginName: String) : AnalysisTask() {
        override val key get() = "trust:$pluginId"
        override val title get() = "Trust report"
    }
}

enum class AnalystVerdict(val label: String) {
    MALICIOUS("Malicious"),
    SUSPICIOUS("Suspicious"),
    INCONCLUSIVE("Inconclusive"),
    LIKELY_BENIGN("Likely benign"),
    BENIGN("Benign");

    companion object {
        fun parse(raw: String?): AnalystVerdict =
            values().firstOrNull { it.name.equals(raw?.trim()?.replace(' ', '_'), ignoreCase = true) } ?: INCONCLUSIVE
    }
}

enum class Confidence {
    HIGH, MEDIUM, LOW;

    companion object {
        fun parse(raw: String?): Confidence = values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MEDIUM
    }
}

/** A policy change the analyst proposes. Applied only when the user clicks Apply. */
data class Recommendation(val capability: Capability, val decision: PolicyDecision, val reason: String)

/** An always-allowed target the analyst proposes to add or remove. */
data class TargetChange(val approve: Boolean, val capability: Capability, val target: String, val reason: String)

/** One entry in the investigation trace shown live in the UI. */
data class TraceStep(
    val kind: Kind,
    val title: String,
    val detail: String,
    val durationMs: Long = 0,
) {
    enum class Kind { TOOL, MESSAGE, FINAL, ERROR }
}

data class AnalysisResult(
    val task: AnalysisTask,
    val model: String,
    val startedAt: Long,
    val durationMs: Long,
    val verdict: AnalystVerdict,
    val confidence: Confidence,
    val headline: String,
    val narrative: String,
    val evidence: List<String>,
    val recommendations: List<Recommendation>,
    val targetChanges: List<TargetChange>,
    val nextSteps: List<String>,
    val trace: List<TraceStep>,
    val error: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** True when produced by PluginFence's own rules rather than a language model. */
    val deterministic: Boolean = false,
    /** Set when the configured model could not be used and something else answered. */
    val fallbackReason: String? = null,
) {
    val failed: Boolean get() = error != null
    val hasChanges: Boolean get() = recommendations.isNotEmpty() || targetChanges.isNotEmpty()
    val toolCallCount: Int get() = trace.count { it.kind == TraceStep.Kind.TOOL }

    companion object {
        fun failure(task: AnalysisTask, model: String, startedAt: Long, trace: List<TraceStep>, message: String) = AnalysisResult(
            task, model, startedAt, System.currentTimeMillis() - startedAt, AnalystVerdict.INCONCLUSIVE, Confidence.LOW,
            "Analysis failed", "", emptyList(), emptyList(), emptyList(), emptyList(),
            trace + TraceStep(TraceStep.Kind.ERROR, "Failed", message), error = message,
        )
    }
}
