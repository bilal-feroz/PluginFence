package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pluginfence.ai.AnalysisTask
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceStats
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/**
 * The tab that answers "am I protected, and did anything happen?" in one screen.
 *
 * Top to bottom: a status banner that states the protection posture in words, four headline
 * numbers that double as shortcuts into the other tabs, then the incident feed with the attack
 * chain for whichever incident is selected.
 */
class OverviewPanel(private val engine: FenceEngine, private val navigator: FenceNavigator) :
    SimpleToolWindowPanel(true, true), FencePanel {

    private val statusBanner = StatusBanner()
    private val setupCard = SetupCard()

    private val monitoredTile = StatTile("Plugins watched", "third-party code under policy") { navigator.open(FenceToolWindowFactory.TAB_PERMISSIONS) }
    private val preventedTile = StatTile("Prevented", "blocked or held for approval") { navigator.openPrevented() }
    private val driftTile = StatTile("Behavior changes", "after a plugin update") { navigator.open(FenceToolWindowFactory.TAB_DRIFT) }
    private val criticalTile = StatTile("Critical incidents", "correlated attack chains") { selectFirstCritical() }

    private val incidentModel = DefaultListModel<Incident>()
    private val incidentList = JBList(incidentModel)
    private val detail = IncidentDetailPanel(engine, navigator)
    private var selectedId: String? = null

    init {
        toolbar = buildToolbar()

        incidentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        incidentList.cellRenderer = IncidentRow()
        incidentList.fixedCellHeight = JBUI.scale(52)
        incidentList.border = JBUI.Borders.empty()
        incidentList.emptyText.text = "No incidents yet"
        incidentList.emptyText.appendLine("Sequences such as \"read a credential, then open a socket\" are correlated and land here.")
        incidentList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedId = incidentList.selectedValue?.id
                detail.show(incidentList.selectedValue)
            }
        }

        val header = UiSupport.column(UiSupport.GAP, statusBanner, tiles(), setupCard).apply {
            border = JBUI.Borders.empty(UiSupport.PAD, UiSupport.PAD, UiSupport.GAP, UiSupport.PAD)
        }

        val feed = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                UiSupport.sectionLabel("Incidents").apply {
                    border = JBUI.Borders.empty(0, UiSupport.PAD, UiSupport.TIGHT, UiSupport.PAD)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(incidentList).apply {
                    border = JBUI.Borders.customLineTop(UiSupport.hairline)
                    viewport.background = UIUtil.getListBackground()
                },
                BorderLayout.CENTER,
            )
        }

        val splitter = OnePixelSplitter(false, 0.42f).apply {
            firstComponent = feed
            secondComponent = detail
        }

        setContent(
            JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(splitter, BorderLayout.CENTER)
            },
        )
    }

    override fun component(): JComponent = this

    override fun refresh() {
        val stats = engine.stats()
        statusBanner.render(stats)
        setupCard.isVisible = !stats.agentInstalled

        monitoredTile.set(stats.pluginsMonitored.toString(), UIUtil.getLabelForeground())
        preventedTile.set(stats.blockedAttempts.toString(), if (stats.blockedAttempts > 0) UiSupport.high else UIUtil.getLabelForeground())
        preventedTile.share(stats.blockedAttempts, stats.eventsRecorded)
        driftTile.set(stats.behaviorChanges.toString(), if (stats.behaviorChanges > 0) UiSupport.high else UIUtil.getLabelForeground())
        criticalTile.set(stats.criticalIncidents.toString(), if (stats.criticalIncidents > 0) UiSupport.critical else UIUtil.getLabelForeground())

        val incidents = engine.incidents()
        val previous = selectedId
        incidentModel.clear()
        incidents.forEach { incidentModel.addElement(it) }
        val index = incidents.indexOfFirst { it.id == previous }
        when {
            index >= 0 -> {
                incidentList.selectedIndex = index
                detail.show(incidents[index])
            }
            incidents.isNotEmpty() -> incidentList.selectedIndex = 0
            else -> detail.show(null)
        }
    }

    private fun tiles(): JComponent = JPanel(GridLayout(1, 4, JBUI.scale(UiSupport.GAP), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(monitoredTile)
        add(preventedTile)
        add(driftTile)
        add(criticalTile)
    }

    private fun selectFirstCritical() {
        val incidents = engine.incidents()
        val index = incidents.indexOfFirst { it.riskLevel == FenceRisk.CRITICAL }
        if (index >= 0) {
            incidentList.selectedIndex = index
            incidentList.ensureIndexIsVisible(index)
        }
    }

    // --- toolbar ----------------------------------------------------------------------------------

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : ToggleAction("Enforce Policies", "Block and prompt, instead of only recording", AllIcons.General.InspectionsOK), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun isSelected(e: AnActionEvent) = engine.enforcementEnabled

            override fun setSelected(e: AnActionEvent, state: Boolean) = engine.setEnforcement(state)

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.isEnabled = engine.agentAvailable()
                e.presentation.description = if (engine.agentAvailable()) {
                    "Block and prompt, instead of only recording"
                } else {
                    "Unavailable: the PluginFence agent is not attached"
                }
            }
        })
        group.addSeparator()
        group.add(object : AnAction("Export Incident", "Save the selected incident and its chain as JSON", AllIcons.ToolbarDecorator.Export), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = incidentList.selectedValue != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                incidentList.selectedValue?.let { IncidentExport.export(it, engine) }
            }
        })
        group.addSeparator()
        group.add(object : AnAction("Clear Activity History", "Remove recorded events and incidents", AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) {
                val answer = Messages.showYesNoDialog(
                    e.project,
                    "Remove all recorded PluginFence events and incidents?",
                    "Clear Activity History",
                    Messages.getQuestionIcon(),
                )
                if (answer == Messages.YES) engine.clearHistory()
            }
        })
        group.add(object : AnAction("Reset Behavior Baselines", "Forget learned plugin behaviour profiles and drift reports", AllIcons.Actions.Refresh), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) {
                val answer = Messages.showYesNoDialog(
                    e.project,
                    "Forget all learned behaviour baselines and drift reports?",
                    "Reset Behavior Baselines",
                    Messages.getQuestionIcon(),
                )
                if (answer == Messages.YES) engine.resetBaselines()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("PluginFenceOverview", group, true)
        toolbar.targetComponent = this
        return toolbar.component
    }

    // --- pieces -------------------------------------------------------------------------------------

    /**
     * States the protection posture in a sentence, not a status LED: what is on, what that means,
     * and - when something is off - the single next step to fix it.
     */
    private class StatusBanner : FenceCard() {

        private val icon = JBLabel(AllIcons.General.InspectionsOK)
        private val title = UiSupport.hero("")
        private val pill = Pill("", UiSupport.allowed)
        private val detail = WrappedText().muted()

        init {
            padding(12, 14, 12, 16)
            icon.verticalAlignment = SwingConstants.TOP
            icon.border = JBUI.Borders.empty(4, 2, 0, 10)
            add(icon, BorderLayout.WEST)
            add(UiSupport.column(UiSupport.TIGHT, UiSupport.row(UiSupport.GAP, title, pill), detail), BorderLayout.CENTER)
        }

        fun render(stats: FenceStats) {
            val posture = describe(stats)
            val (color, headline, state, text) = posture
            accent = color
            icon.icon = posture.icon
            title.text = headline
            title.foreground = UIUtil.getLabelForeground()
            pill.text = state
            pill.color = color
            detail.text = text
            revalidate()
            repaint()
        }

        private data class Posture(
            val color: Color,
            val headline: String,
            val state: String,
            val detail: String,
            val icon: Icon,
        )

        private fun describe(stats: FenceStats): Posture = when {
            !stats.agentInstalled -> Posture(
                UiSupport.high,
                "PluginFence is watching nothing yet",
                "PROTECTION INACTIVE",
                "The JVM agent is not attached, so plugin file, network and process calls are invisible to " +
                    "PluginFence. Attach it with the VM option below and restart the IDE.",
                AllIcons.General.Warning,
            )
            !stats.providerRegistered -> Posture(
                UiSupport.medium,
                "Protection is starting",
                "PROTECTION STARTING",
                "The agent is attached and instrumenting plugin classes. " + stats.agentInfo,
                AllIcons.General.Information,
            )
            !stats.enforcementEnabled -> Posture(
                UiSupport.medium,
                "Recording only - nothing is being blocked",
                "MONITOR ONLY",
                "Every plugin operation is classified and logged, but policies are not enforced. " +
                    "Turn \"Enforce Policies\" back on in the toolbar to start blocking. " + diagnostics(stats),
                AllIcons.General.Information,
            )
            else -> Posture(
                UiSupport.allowed,
                "Third-party plugins are fenced in",
                "PROTECTION ACTIVE",
                "Sensitive files, secret environment variables, outbound connections and process launches are " +
                    "checked against your policy before they happen. " + diagnostics(stats),
                AllIcons.General.InspectionsOK,
            )
        }

        private fun diagnostics(stats: FenceStats): String {
            val d = stats.agentDiagnostics
            if (d.isEmpty()) return stats.agentInfo
            return "${d["classes.instrumented"] ?: "0"} plugin classes instrumented, " +
                "${d["callSites.rewritten"] ?: "0"} call sites guarded, ${d["rules"] ?: "?"} interception rules."
        }
    }

    /**
     * A headline number that is also a shortcut. Every number on this tab answers "where do I look
     * next?", so clicking one takes you to the tab that can act on it.
     */
    private class StatTile(caption: String, footnote: String, onOpen: () -> Unit) : FenceCard() {

        private val value = UiSupport.metric("0")
        private val donut = Donut(40).apply { isVisible = false }

        init {
            padding(10, 14, 10, 12)
            add(
                UiSupport.column(
                    2,
                    UiSupport.sectionLabel(caption),
                    value,
                    UiSupport.hint(footnote),
                ),
                BorderLayout.CENTER,
            )
            add(JPanel(GridBagLayout()).apply { isOpaque = false; add(donut, GridBagConstraints()) }, BorderLayout.EAST)
            onClick(onOpen)
        }

        fun set(text: String, color: Color) {
            value.text = text
            value.foreground = color
        }

        /** Shows what share of all observed operations this number represents. */
        fun share(part: Long, total: Long) {
            if (total <= 0) {
                donut.isVisible = false
                return
            }
            val fraction = part.toFloat() / total
            donut.isVisible = true
            donut.set(fraction, if (part > 0) UiSupport.high else UiSupport.allowed, "${(fraction * 100).toInt()}%")
        }
    }

    /** Shown only when the agent is missing: the exact VM option, one click from the clipboard. */
    private class SetupCard : FenceCard() {

        init {
            tint = UiSupport.high
            padding(12, 14, 12, 14)
            val vmOption = "-javaagent:<path>/plugin-fence-agent.jar"
            add(
                UiSupport.column(
                    UiSupport.TIGHT,
                    UiSupport.subheading("Attach the agent to turn protection on"),
                    WrappedText(
                        "Add this to Help > Edit Custom VM Options and restart the IDE. Keep " +
                            "plugin-fence-bootstrap.jar next to the agent jar.",
                    ).muted(),
                    UiSupport.row(
                        UiSupport.GAP,
                        UiSupport.mono(vmOption).apply { foreground = UiSupport.high },
                        JButton("Copy", AllIcons.Actions.Copy).apply {
                            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(vmOption)) }
                        },
                    ),
                    UiSupport.hint("Developing PluginFence? ./gradlew runFenceIde starts a sandbox IDE with the agent already attached."),
                ),
                BorderLayout.CENTER,
            )
            isVisible = false
        }
    }

    /** Two lines per incident: what happened, and everything needed to triage it. */
    private class IncidentRow : JBPanel<IncidentRow>(BorderLayout(JBUI.scale(UiSupport.GAP), 0)), ListCellRenderer<Incident> {

        private val title = JBLabel().apply { font = JBFont.label().asBold() }
        private val subtitle = JBLabel().apply { font = JBFont.small() }
        private val pill = Pill("", UiSupport.info)
        private var stripe: Color = UiSupport.info

        init {
            isOpaque = true
            border = JBUI.Borders.empty(8, 14, 8, 12)
            add(UiSupport.column(2, title, subtitle), BorderLayout.CENTER)
            add(JPanel(GridBagLayout()).apply { isOpaque = false; add(pill, GridBagConstraints()) }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out Incident>,
            value: Incident,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val foreground = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()
            stripe = UiSupport.riskColor(value.riskLevel)

            title.text = value.title
            title.foreground = foreground
            subtitle.text = buildString {
                append(value.pluginName)
                if (value.pluginVersion.isNotBlank()) append(' ').append(value.pluginVersion)
                append("   ").append(UiSupport.ago(value.timestamp))
                append("   ").append(value.chain.size).append(if (value.chain.size == 1) " step" else " steps")
            }
            subtitle.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()
            pill.text = "${value.riskLevel.label.uppercase()} ${value.riskScore}"
            pill.color = if (selected) foreground else stripe
            return this
        }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            g.color = stripe
            g.fillRect(0, 0, JBUI.scale(3), height)
        }
    }
}

/**
 * One incident, told as a story: what the plugin did, step by step, and how it ended.
 *
 * The chain is drawn on a single vertical rail with numbered nodes so the causal order is obvious
 * at a glance - the whole point of correlation is that step 3 only matters because steps 1 and 2
 * happened first.
 */
class IncidentDetailPanel(private val engine: FenceEngine, private val navigator: FenceNavigator) :
    JBPanel<IncidentDetailPanel>(BorderLayout()) {

    private val content = UiSupport.column(0).apply { border = JBUI.Borders.empty(UiSupport.PAD) }

    private val scroll = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = JBUI.scale(16)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val empty = UiSupport.emptyState(
        "Nothing selected",
        "Pick an incident on the left to replay what the plugin attempted, step by step.",
        AllIcons.General.Information,
    )

    private var showingEmpty = false

    init {
        add(empty, BorderLayout.CENTER)
        showingEmpty = true
    }

    fun show(incident: Incident?) {
        if (incident == null) {
            if (!showingEmpty) {
                remove(scroll)
                add(empty, BorderLayout.CENTER)
                showingEmpty = true
            }
            content.removeAll()
        } else {
            if (showingEmpty) {
                remove(empty)
                add(scroll, BorderLayout.CENTER)
                showingEmpty = false
            }
            content.removeAll()
            render(incident)
        }
        revalidate()
        repaint()
    }

    private fun render(incident: Incident) {
        val color = UiSupport.riskColor(incident.riskLevel)
        val meter = RiskMeter(150, 7).apply { set(incident.riskScore, color) }

        content.add(UiSupport.row(UiSupport.GAP, Pill(incident.riskLevel.label.uppercase(), color), UiSupport.heading(incident.title)))
        content.add(UiSupport.spacer(UiSupport.TIGHT))
        content.add(
            UiSupport.caption(
                buildString {
                    append(incident.pluginName)
                    if (incident.pluginVersion.isNotBlank()) append(' ').append(incident.pluginVersion)
                    append("   ").append(UiSupport.dateTime(incident.timestamp))
                },
            ),
        )
        content.add(UiSupport.spacer(UiSupport.GAP))
        content.add(
            UiSupport.row(
                UiSupport.GAP,
                meter,
                JBLabel("${incident.riskScore} / 100").apply { font = JBFont.label().asBold(); foreground = color },
            ),
        )
        content.add(UiSupport.spacer(UiSupport.GAP))
        content.add(WrappedText(incident.summary))
        content.add(UiSupport.spacer(UiSupport.PAD))

        content.add(UiSupport.sectionLabel("Attack chain"))
        content.add(UiSupport.spacer(UiSupport.GAP))
        incident.chain.forEachIndexed { i, event ->
            content.add(ChainStep(i + 1, i == 0, false, UiSupport.verdictColor(event.verdict), stepCard(event)))
        }
        content.add(ChainOutcome(outcomeColor(incident), outcomeCard(incident)))

        // The analyst sits right under the chain it explains. It is rebuilt with the panel; its
        // state (running, result) lives in AnalysisService, so a refresh never loses an answer.
        content.add(UiSupport.spacer(UiSupport.PAD))
        content.add(
            AnalystCard(engine).apply {
                bind(AnalysisTask.Incident(incident.id, incident.pluginId, incident.pluginName))
            },
        )

        content.add(UiSupport.spacer(UiSupport.PAD))
        content.add(
            UiSupport.row(
                UiSupport.TIGHT + 2,
                JButton("Export as JSON", AllIcons.ToolbarDecorator.Export).apply {
                    addActionListener { IncidentExport.export(incident, engine) }
                },
                JButton("Show plugin activity").apply {
                    addActionListener { navigator.openActivityFor(incident.pluginName) }
                },
            ),
        )
    }

    private fun stepCard(event: FenceEvent): JComponent {
        val color = UiSupport.verdictColor(event.verdict)
        val card = FenceCard().apply {
            accent = color
            padding(8, 12, 8, 10)
        }
        card.add(
            JBLabel(UiSupport.time(event.timestamp)).apply {
                foreground = UIUtil.getContextHelpForeground()
                font = JBFont.small()
                border = JBUI.Borders.emptyRight(UiSupport.GAP)
            },
            BorderLayout.WEST,
        )
        card.add(
            UiSupport.column(
                2,
                UiSupport.row(
                    6,
                    JBLabel(event.actionLabel, UiSupport.operationIcon(event.operation), SwingConstants.LEFT).apply { font = JBFont.label().asBold() },
                    UiSupport.mono(UiSupport.shorten(engine.sensitivePaths.displayPath(event.target), 64)).apply {
                        toolTipText = event.target
                    },
                ),
                UiSupport.hint(event.reason),
            ),
            BorderLayout.CENTER,
        )
        card.add(
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(Pill(UiSupport.verdictShort(event.verdict), color), GridBagConstraints())
            },
            BorderLayout.EAST,
        )
        return card
    }

    private fun outcomeCard(incident: Incident): JComponent {
        val color = outcomeColor(incident)
        val card = FenceCard().apply {
            tint = color
            padding(10, 14, 10, 14)
        }
        val headline = incident.chain.lastOrNull()?.let { UiSupport.verdictShort(it.verdict) } ?: "RECORDED"
        card.add(
            UiSupport.column(
                UiSupport.TIGHT,
                UiSupport.row(
                    UiSupport.GAP,
                    Pill(headline, color, solid = true),
                    JBLabel("Outcome").apply { font = JBFont.label().asBold() },
                ),
                WrappedText(incident.outcome),
            ),
            BorderLayout.CENTER,
        )
        return card
    }

    private fun outcomeColor(incident: Incident): Color = when (incident.chain.lastOrNull()?.verdict) {
        FenceVerdict.BLOCK -> UiSupport.blocked
        FenceVerdict.ASK -> UiSupport.ask
        null -> UiSupport.info
        else -> UiSupport.riskColor(incident.riskLevel)
    }


}
