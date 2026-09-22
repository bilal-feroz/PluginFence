package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pluginfence.ai.AiConfigurable
import com.pluginfence.ai.AiSettings
import com.pluginfence.ai.AnalysisListener
import com.pluginfence.ai.AnalysisResult
import com.pluginfence.ai.AnalysisService
import com.pluginfence.ai.AnalysisTask
import com.pluginfence.ai.AnalystVerdict
import com.pluginfence.ai.TraceStep
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.PolicyDecision
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * The AI analyst, embedded where the question arises: under an incident, beside a drift report, on
 * a plugin's permission page.
 *
 * Three ideas drive the layout. The verdict is a banner, not a line of text, because it is the one
 * thing a developer reads first. The investigation is shown live, step by step, because a security
 * verdict nobody can trace back to evidence is worth nothing. And recommendations are rendered as
 * *changes* - current policy struck through, proposed policy beside it - so the Apply button has an
 * obvious and reversible meaning.
 */
class AnalystCard(private val engine: FenceEngine) : FenceCard() {

    private val service get() = AnalysisService.getInstance()
    private val body = UiSupport.column(UiSupport.GAP)
    private var task: AnalysisTask? = null
    private var connection: MessageBusConnection? = null
    private var showTrace = false
    private var appliedFor: String? = null

    /** Drives the elapsed-seconds counter while an investigation is in flight. */
    private val ticker = Timer(500) { if (task?.let { service.status(it) } is AnalysisService.Status.Running) render() }

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        accent = UiSupport.accent
        padding(12, 14, 12, 14)
        add(body, BorderLayout.CENTER)
        isVisible = false
        ticker.isRepeats = true
    }

    fun bind(task: AnalysisTask?) {
        this.task = task
        isVisible = task != null
        render()
    }

    // The card is rebuilt with its parent panel on every refresh, so the subscription follows the
    // Swing lifecycle instead of a Disposable that would outlive the component.
    override fun addNotify() {
        super.addNotify()
        connection = ApplicationManager.getApplication().messageBus.connect().also {
            it.subscribe(
                AnalysisListener.TOPIC,
                object : AnalysisListener {
                    override fun changed(taskKey: String) {
                        if (taskKey == task?.key) ApplicationManager.getApplication().invokeLater { render() }
                    }
                },
            )
        }
        ticker.start()
        render()
    }

    override fun removeNotify() {
        ticker.stop()
        connection?.disconnect()
        connection = null
        super.removeNotify()
    }

    private fun render() {
        val t = task ?: return
        body.removeAll()
        val settings = AiSettings.getInstance()
        when {
            !settings.enabled -> renderOffer(t, "Off - PluginFence will answer from its own rules")
            !settings.isConfiguredQuick(onResolved = { render() }) -> renderOffer(t, "No key - PluginFence will answer from its own rules")
            else -> when (val status = service.status(t)) {
                AnalysisService.Status.Idle -> renderIdle(t, settings.model)
                is AnalysisService.Status.Running -> renderRunning(status)
                is AnalysisService.Status.Done -> renderResult(t, status.result)
            }
        }
        body.revalidate()
        body.repaint()
        revalidate()
        repaint()
    }

    // --- states -----------------------------------------------------------------------------------

    private fun header(subtitle: String): JComponent = UiSupport.column(
        2,
        UiSupport.row(
            8,
            JBLabel(AllIcons.Actions.IntentionBulb),
            UiSupport.subheading("AI security analyst"),
            Pill("ADVISORY", UiSupport.accent),
        ),
        UiSupport.hint(subtitle),
    )

    /**
     * Not configured is still a working feature: the analyst runs on PluginFence's own rules, and
     * connecting a model is an upgrade rather than a prerequisite.
     */
    private fun renderOffer(t: AnalysisTask, subtitle: String) {
        body.add(header(subtitle))
        body.add(WrappedText(idleText(t)).muted())
        body.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton("Analyse", AllIcons.Actions.Execute).apply { addActionListener { service.analyse(t) } },
                JButton("Connect a model...", AllIcons.General.Settings).apply { addActionListener { openSettings() } },
            ),
        )
        body.add(UiSupport.hint("Connecting a model adds the written narrative. The findings come from PluginFence either way."))
    }

    private fun renderIdle(t: AnalysisTask, model: String) {
        body.add(header("Ready - $model"))
        body.add(WrappedText(idleText(t)).muted())
        body.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton(buttonText(t), AllIcons.Actions.Execute).apply { addActionListener { service.analyse(t) } },
                UiSupport.hint("Nothing is sent until you click."),
            ),
        )
    }

    private fun renderRunning(status: AnalysisService.Status.Running) {
        val elapsed = (System.currentTimeMillis() - status.startedAt) / 1000
        body.add(header("Investigating - ${elapsed}s"))
        // Each completed step stays on screen: the trace IS the explanation of how the verdict was reached.
        status.trace.forEach { body.add(traceRow(it, done = true)) }
        body.add(
            UiSupport.row(
                8,
                JBLabel(AnimatedIcon.Default()),
                UiSupport.hint(
                    when {
                        status.trace.isEmpty() -> "Reading the recorded evidence..."
                        status.trace.last().kind == TraceStep.Kind.MESSAGE -> status.trace.last().title
                        else -> "Reasoning over the evidence..."
                    },
                ),
            ),
        )
    }

    private fun renderResult(t: AnalysisTask, result: AnalysisResult) {
        body.add(header(provenance(result)))
        body.add(verdictBanner(result))
        if (result.narrative.isNotBlank()) body.add(WrappedText(result.narrative))

        if (result.evidence.isNotEmpty()) {
            body.add(UiSupport.sectionLabel("Evidence"))
            result.evidence.take(6).forEach { body.add(bullet(it)) }
        }

        if (result.hasChanges) {
            body.add(UiSupport.sectionLabel("Proposed policy for ${t.pluginId}"))
            result.recommendations.forEach { body.add(changeRow(t.pluginId, it.capability.displayName, engine.policies.effective(t.pluginId, it.capability), it.decision, it.reason)) }
            result.targetChanges.forEach { change ->
                body.add(
                    UiSupport.column(
                        2,
                        UiSupport.row(
                            8,
                            Pill(if (change.approve) "ALWAYS ALLOW" else "REVOKE", if (change.approve) UiSupport.allowed else UiSupport.blocked, solid = true),
                            UiSupport.mono(change.target),
                        ),
                        UiSupport.hint(change.reason),
                    ),
                )
            }
        }

        if (result.nextSteps.isNotEmpty()) {
            body.add(UiSupport.sectionLabel("What to do next"))
            result.nextSteps.take(4).forEach { body.add(bullet(it)) }
        }

        val applied = appliedFor == result.task.key
        val actions = UiSupport.row(UiSupport.GAP)
        if (result.hasChanges) {
            val count = result.recommendations.size + result.targetChanges.size
            actions.add(
                JButton(if (applied) "Applied" else "Apply $count change${if (count == 1) "" else "s"}", AllIcons.Actions.Commit).apply {
                    isEnabled = !applied
                    addActionListener {
                        service.apply(result)
                        appliedFor = result.task.key
                        render()
                    }
                },
            )
        }
        actions.add(JButton("Re-run", AllIcons.Actions.Refresh).apply { addActionListener { appliedFor = null; service.analyse(t, force = true) } })
        actions.add(
            JButton(if (showTrace) "Hide investigation" else "Show investigation (${result.toolCallCount} tool calls)").apply {
                addActionListener { showTrace = !showTrace; render() }
            },
        )
        body.add(actions)
        if (showTrace) {
            body.add(UiSupport.sectionLabel("How this was reached"))
            result.trace.forEach { body.add(traceRow(it, done = true)) }
        }
        body.add(UiSupport.hint(footer(result)))
    }

    // --- pieces -------------------------------------------------------------------------------------

    /** The verdict as a banner: colour, word, confidence and a meter, readable across a room. */
    private fun verdictBanner(result: AnalysisResult): JComponent {
        val color = verdictColor(result.verdict)
        val card = FenceCard().apply {
            tint = color
            padding(10, 12, 10, 12)
        }
        val meter = RiskMeter(120, 7).apply { set(confidenceScore(result), color) }
        card.add(
            UiSupport.column(
                UiSupport.TIGHT,
                UiSupport.row(
                    UiSupport.GAP,
                    Pill(result.verdict.label.uppercase(), color, solid = true),
                    JBLabel("${result.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence").apply {
                        font = JBFont.small().asBold()
                        foreground = color
                    },
                    meter,
                ),
                JBLabel("<html><b>${UiSupport.escape(result.headline)}</b></html>").apply {
                    font = JBFont.label().deriveFont(Font.PLAIN, JBFont.label().size2D + 1f)
                },
            ),
            BorderLayout.CENTER,
        )
        return card
    }

    /**
     * A recommendation shown as a transition: what the policy is now, and what it would become.
     * "NETWORK: ASK -> BLOCK" is instantly checkable; "we suggest blocking network" is not.
     */
    private fun changeRow(pluginId: String, capability: String, from: PolicyDecision, to: PolicyDecision, reason: String): JComponent {
        val row = UiSupport.row(
            8,
            Pill(capability.uppercase(), UiSupport.accent),
            JBLabel(from.name).apply {
                font = JBFont.small()
                foreground = UIUtil.getContextHelpForeground()
            },
            JBLabel("→").apply { foreground = UIUtil.getContextHelpForeground() },
            Pill(to.name, decisionColor(to), solid = true),
        )
        return UiSupport.column(2, row, UiSupport.hint(reason))
    }

    private fun bullet(text: String): JComponent = UiSupport.row(
        6,
        JBLabel("•").apply { foreground = UiSupport.accent },
        WrappedText(text).muted(),
    )

    private fun traceRow(step: TraceStep, done: Boolean): JComponent {
        val icon = when (step.kind) {
            TraceStep.Kind.TOOL -> if (done) AllIcons.RunConfigurations.TestPassed else AllIcons.Nodes.Function
            TraceStep.Kind.MESSAGE -> AllIcons.General.Information
            TraceStep.Kind.FINAL -> AllIcons.RunConfigurations.TestPassed
            TraceStep.Kind.ERROR -> AllIcons.General.Error
        }
        val title = JBLabel(step.title, icon, SwingConstants.LEFT).apply {
            font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.small().size))
            foreground = when (step.kind) {
                TraceStep.Kind.ERROR -> UiSupport.high
                TraceStep.Kind.MESSAGE -> UIUtil.getContextHelpForeground()
                else -> UIUtil.getLabelForeground()
            }
        }
        val detail = JBLabel(step.detail.replace('\n', ' ').take(120) + if (step.durationMs > 0) "   ${step.durationMs} ms" else "").apply {
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(22)
        }
        return UiSupport.column(1, title, detail)
    }

    /** Where the answer came from - never hidden, because it changes how much to trust the prose. */
    private fun provenance(result: AnalysisResult): String = when {
        result.deterministic && result.fallbackReason != null -> "PluginFence rules - the model was unavailable"
        result.deterministic -> "PluginFence rules - no model involved"
        result.fallbackReason != null -> "${result.model} (fallback)"
        else -> result.model
    }

    private fun footer(result: AnalysisResult): String = buildString {
        append("Advisory: the analyst explains and proposes; PluginFence's deterministic policy is what blocks.")
        if (!result.deterministic) {
            append("  ")
            append("%.1fs".format(result.durationMs / 1000.0))
            if (result.promptTokens > 0) append(", ${result.promptTokens + result.completionTokens} tokens")
        }
    }

    private fun confidenceScore(result: AnalysisResult): Int = when (result.confidence) {
        com.pluginfence.ai.Confidence.HIGH -> 100
        com.pluginfence.ai.Confidence.MEDIUM -> 60
        com.pluginfence.ai.Confidence.LOW -> 30
    }

    private fun idleText(t: AnalysisTask): String = when (t) {
        is AnalysisTask.Incident -> "Investigates this incident against the plugin's baseline, its drift, its own plugin.xml and its current policy, then explains what happened and proposes the permissions it should have."
        is AnalysisTask.UpdateReview -> "Reviews what ${t.newVersion} does that ${t.oldVersion} never did, checks it against what the plugin claims to be, and says whether the update should keep its permissions."
        is AnalysisTask.TrustReport -> "Summarises everything PluginFence has observed this plugin doing and judges whether its permissions are the least privilege it needs."
    }

    private fun buttonText(t: AnalysisTask): String = when (t) {
        is AnalysisTask.Incident -> "Analyse with AI"
        is AnalysisTask.UpdateReview -> "Review this update with AI"
        is AnalysisTask.TrustReport -> "Generate trust report"
    }

    private fun openSettings() {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        ShowSettingsUtil.getInstance().showSettingsDialog(project, AiConfigurable::class.java)
    }

    private fun verdictColor(verdict: AnalystVerdict): Color = when (verdict) {
        AnalystVerdict.MALICIOUS -> UiSupport.critical
        AnalystVerdict.SUSPICIOUS -> UiSupport.high
        AnalystVerdict.INCONCLUSIVE -> UiSupport.medium
        AnalystVerdict.LIKELY_BENIGN -> UiSupport.low
        AnalystVerdict.BENIGN -> UiSupport.allowed
    }

    private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
        PolicyDecision.ALLOW -> UiSupport.allowed
        PolicyDecision.ASK -> UiSupport.ask
        PolicyDecision.BLOCK -> UiSupport.blocked
    }
}
