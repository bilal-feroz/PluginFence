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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The AI analyst, embedded where the question arises: under an incident, next to a drift report,
 * on a plugin's permission page. One card, three states - not configured, investigating, done.
 *
 * The investigation trace is shown live, tool call by tool call, because a security verdict that
 * cannot be traced back to evidence is worth nothing. Recommendations are proposals until the
 * user clicks Apply; the card never changes a policy on its own.
 */
class AnalystCard(private val engine: FenceEngine) : FenceCard() {

    private val service get() = AnalysisService.getInstance()
    private val body = UiSupport.column(UiSupport.GAP)
    private var task: AnalysisTask? = null
    private var connection: MessageBusConnection? = null
    private var showTrace = false
    private var appliedFor: String? = null

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        accent = UiSupport.accent
        padding(12, 14, 12, 14)
        add(body, BorderLayout.CENTER)
        isVisible = false
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
        render()
    }

    override fun removeNotify() {
        connection?.disconnect()
        connection = null
        super.removeNotify()
    }

    private fun render() {
        val t = task ?: return
        body.removeAll()
        val settings = AiSettings.getInstance()
        when {
            !settings.enabled -> renderNotConfigured("The AI analyst is off. Enable it to get a plain-English investigation and a suggested policy.")
            // EDT-safe: never reads the credential store on the UI thread; re-renders once the answer is known.
            !settings.isConfiguredQuick(onResolved = { render() }) ->
                renderNotConfigured("Add an API key, or point PluginFence at a local model such as Ollama, to run the analyst.")
            else -> when (val status = service.status(t)) {
                AnalysisService.Status.Idle -> renderIdle(t, settings)
                is AnalysisService.Status.Running -> renderRunning(status, settings)
                is AnalysisService.Status.Done -> if (status.result.failed) renderFailed(t, status.result) else renderResult(t, status.result)
            }
        }
        body.revalidate()
        body.repaint()
        revalidate()
        repaint()
    }

    // --- states -----------------------------------------------------------------------------------

    private fun header(subtitle: String, extra: JComponent? = null): JComponent {
        val title = UiSupport.row(
            8,
            JBLabel(AllIcons.Actions.IntentionBulb),
            UiSupport.subheading("AI security analyst"),
            Pill("ADVISORY", UiSupport.accent),
        )
        extra?.let { title.add(it) }
        return UiSupport.column(2, title, UiSupport.hint(subtitle))
    }

    private fun renderNotConfigured(text: String) {
        body.add(header("Not configured"))
        body.add(WrappedText(text).muted())
        body.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton("Configure...", AllIcons.General.Settings).apply { addActionListener { openSettings() } },
                UiSupport.hint("Opt-in. Only redacted metadata is ever sent; local endpoints keep everything on this machine."),
            ),
        )
    }

    private fun renderIdle(t: AnalysisTask, settings: AiSettings) {
        body.add(header("Ready - ${settings.model}"))
        body.add(WrappedText(idleText(t)).muted())
        body.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton(buttonText(t), AllIcons.Actions.Execute).apply { addActionListener { service.analyse(t) } },
                UiSupport.hint("Nothing is sent until you click."),
            ),
        )
    }

    private fun renderRunning(status: AnalysisService.Status.Running, settings: AiSettings) {
        val elapsed = (System.currentTimeMillis() - status.startedAt) / 1000
        body.add(header("Investigating with ${settings.model} - ${elapsed}s"))
        status.trace.forEach { body.add(traceRow(it)) }
        body.add(
            UiSupport.row(
                8,
                JBLabel(AnimatedIcon.Default()),
                UiSupport.hint(if (status.trace.isEmpty()) "Reading the evidence..." else "Thinking..."),
            ),
        )
    }

    private fun renderFailed(t: AnalysisTask, result: AnalysisResult) {
        body.add(header("Failed"))
        body.add(WrappedText(result.error ?: "Unknown error").apply { foreground = UiSupport.high })
        body.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton("Retry", AllIcons.Actions.Refresh).apply { addActionListener { service.analyse(t, force = true) } },
                JButton("Configure...", AllIcons.General.Settings).apply { addActionListener { openSettings() } },
            ),
        )
    }

    private fun renderResult(t: AnalysisTask, result: AnalysisResult) {
        val color = verdictColor(result.verdict)
        val toolCalls = result.trace.count { it.kind == TraceStep.Kind.TOOL }
        body.add(header("${result.model} - ${result.durationMs / 1000.0}s - $toolCalls tool call${if (toolCalls == 1) "" else "s"}"))

        body.add(
            UiSupport.row(
                UiSupport.GAP,
                Pill(result.verdict.label.uppercase(), color, solid = true),
                Pill("${result.confidence.name} CONFIDENCE", UiSupport.info),
            ),
        )
        body.add(UiSupport.subheading(result.headline))
        if (result.narrative.isNotBlank()) body.add(WrappedText(result.narrative))

        if (result.evidence.isNotEmpty()) {
            body.add(UiSupport.sectionLabel("Evidence"))
            result.evidence.forEach { body.add(WrappedText("• $it").muted()) }
        }

        if (result.hasChanges) {
            body.add(UiSupport.sectionLabel("Recommended policy for ${t.pluginId}"))
            result.recommendations.forEach { rec ->
                body.add(
                    UiSupport.row(
                        8,
                        Pill(rec.capability.displayName.uppercase(), UiSupport.accent),
                        Pill(rec.decision.name, decisionColor(rec.decision), solid = true),
                    ),
                )
                if (rec.reason.isNotBlank()) body.add(WrappedText(rec.reason).muted())
            }
            result.targetChanges.forEach { change ->
                body.add(
                    UiSupport.row(
                        8,
                        Pill(if (change.approve) "ALWAYS ALLOW" else "REVOKE", if (change.approve) UiSupport.allowed else UiSupport.blocked),
                        Pill(change.capability.displayName.uppercase(), UiSupport.accent),
                        UiSupport.mono(change.target),
                    ),
                )
                if (change.reason.isNotBlank()) body.add(WrappedText(change.reason).muted())
            }
        }

        if (result.nextSteps.isNotEmpty()) {
            body.add(UiSupport.sectionLabel("Next steps"))
            result.nextSteps.forEach { body.add(WrappedText("• $it").muted()) }
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
            JButton(if (showTrace) "Hide investigation" else "Show investigation").apply {
                addActionListener { showTrace = !showTrace; render() }
            },
        )
        body.add(actions)
        if (showTrace) {
            body.add(UiSupport.sectionLabel("Investigation"))
            result.trace.forEach { body.add(traceRow(it)) }
        }
        body.add(UiSupport.hint("Advisory only: the analyst explains and proposes; PluginFence's deterministic policy is what actually blocks."))
    }

    // --- pieces -------------------------------------------------------------------------------------

    private fun traceRow(step: TraceStep): JComponent {
        val icon = when (step.kind) {
            TraceStep.Kind.TOOL -> AllIcons.Nodes.Function
            TraceStep.Kind.MESSAGE -> AllIcons.General.Balloon
            TraceStep.Kind.FINAL -> AllIcons.RunConfigurations.TestPassed
            TraceStep.Kind.ERROR -> AllIcons.General.Error
        }
        val title = JBLabel(step.title, icon, SwingConstants.LEFT).apply {
            font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.small().size))
            foreground = if (step.kind == TraceStep.Kind.ERROR) UiSupport.high else UIUtil.getLabelForeground()
        }
        val detail = JBLabel(step.detail.replace('\n', ' ').take(140) + if (step.durationMs > 0) "   ${step.durationMs} ms" else "").apply {
            font = JBFont.small()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyLeft(22)
        }
        return UiSupport.column(1, title, detail)
    }

    private fun idleText(t: AnalysisTask): String = when (t) {
        is AnalysisTask.Incident -> "Investigates this incident with read-only tools over PluginFence's evidence - the chain of events, the plugin's baseline and drift, its manifest and its current policy - then explains what happened and proposes the permissions this plugin should have."
        is AnalysisTask.UpdateReview -> "Reviews what ${t.newVersion} does that ${t.oldVersion} never did, checks it against what the plugin claims to be, and says whether the update should keep its permissions."
        is AnalysisTask.TrustReport -> "Summarises everything PluginFence has observed this plugin doing and judges whether its current permission matrix is the least privilege it needs."
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
