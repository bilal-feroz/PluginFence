package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceStats
import com.pluginfence.model.FenceVerdict
import com.pluginfence.model.Incident
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

/** Dashboard: protection status, headline numbers and recent incidents with the attack-chain view. */
class OverviewPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true) {

    private val statusTitle = JBLabel("PLUGINFENCE").apply { font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 5f) }
    private val statusLine = JBLabel()
    private val statusDetail = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
    private val statusIcon = JBLabel(AllIcons.General.InspectionsOK)

    private val monitoredCard = StatCard("Plugins monitored")
    private val blockedCard = StatCard("Blocked attempts")
    private val driftCard = StatCard("Behavior changes")
    private val criticalCard = StatCard("Critical incidents")

    private val incidentModel = DefaultListModel<Incident>()
    private val incidentList = JBList(incidentModel)
    private val detail = IncidentDetailPanel(engine)
    private val setupPanel = SetupPanel()
    private var selectedId: String? = null

    init {
        val header = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(12), 0)).apply {
            border = JBUI.Borders.empty(12, 16, 8, 16)
            val text = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(statusTitle)
                add(statusLine)
                add(statusDetail)
            }
            statusIcon.verticalAlignment = SwingConstants.TOP
            add(statusIcon, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
        val cards = JPanel(GridLayout(1, 4, JBUI.scale(10), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 16, 12, 16)
            add(monitoredCard); add(blockedCard); add(driftCard); add(criticalCard)
        }
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
            add(setupPanel, BorderLayout.SOUTH)
        }

        incidentList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        incidentList.cellRenderer = IncidentRenderer()
        incidentList.emptyText.text = "No incidents recorded. Sensitive sequences will appear here."
        incidentList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedId = incidentList.selectedValue?.id
                detail.show(incidentList.selectedValue)
            }
        }
        val incidentsBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(UiSupport.subheading("Recent incidents").apply { border = JBUI.Borders.empty(6, 16, 4, 16) }, BorderLayout.NORTH)
            add(JBScrollPane(incidentList).apply { border = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()) }, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.42f).apply {
            firstComponent = incidentsBox
            secondComponent = detail
        }
        val root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        setContent(root)
    }

    fun refresh() {
        val stats = engine.stats()
        renderStatus(stats)
        monitoredCard.value(stats.pluginsMonitored.toString(), UIUtil.getLabelForeground())
        blockedCard.value(stats.blockedAttempts.toString(), if (stats.blockedAttempts > 0) UiSupport.high else UIUtil.getLabelForeground())
        driftCard.value(stats.behaviorChanges.toString(), if (stats.behaviorChanges > 0) UiSupport.high else UIUtil.getLabelForeground())
        criticalCard.value(stats.criticalIncidents.toString(), if (stats.criticalIncidents > 0) UiSupport.critical else UIUtil.getLabelForeground())
        setupPanel.isVisible = !stats.agentInstalled

        val incidents = engine.incidents()
        val previouslySelected = selectedId
        incidentModel.clear()
        incidents.forEach { incidentModel.addElement(it) }
        val index = incidents.indexOfFirst { it.id == previouslySelected }
        when {
            index >= 0 -> incidentList.selectedIndex = index
            incidents.isNotEmpty() -> incidentList.selectedIndex = 0
            else -> detail.show(null)
        }
    }

    private fun renderStatus(stats: FenceStats) {
        when {
            !stats.agentInstalled -> {
                statusIcon.icon = AllIcons.General.Warning
                statusLine.text = "Protection INACTIVE - agent not attached"
                statusLine.foreground = UiSupport.high
                statusDetail.text = "Start the IDE with -javaagent:plugin-fence-agent.jar (see setup below)."
            }
            !stats.providerRegistered -> {
                statusIcon.icon = AllIcons.General.Warning
                statusLine.text = "Protection STARTING"
                statusLine.foreground = UiSupport.medium
                statusDetail.text = stats.agentInfo
            }
            !stats.enforcementEnabled -> {
                statusIcon.icon = AllIcons.General.Information
                statusLine.text = "Protection MONITOR ONLY - policies are not enforced"
                statusLine.foreground = UiSupport.medium
                statusDetail.text = "Tools > PluginFence > Enforce Policies to re-enable blocking. " + diagnostics(stats)
            }
            else -> {
                statusIcon.icon = AllIcons.General.InspectionsOK
                statusLine.text = "Protection ACTIVE"
                statusLine.foreground = UiSupport.allowed
                statusDetail.text = diagnostics(stats)
            }
        }
        statusLine.font = JBFont.label().asBold()
    }

    private fun diagnostics(stats: FenceStats): String {
        val d = stats.agentDiagnostics
        if (d.isEmpty()) return stats.agentInfo
        return "${d["classes.instrumented"] ?: "0"} plugin classes instrumented, " +
            "${d["callSites.rewritten"] ?: "0"} call sites guarded, ${d["rules"] ?: "?"} interception rules - ${stats.agentInfo}"
    }

    /** A number with a caption, IntelliJ-flavoured. */
    private class StatCard(caption: String) : JBPanel<StatCard>(BorderLayout()) {
        private val value = JBLabel("0").apply { font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 10f) }
        private val label = JBLabel(caption).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }

        init {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1),
                JBUI.Borders.empty(8, 12),
            )
            background = UIUtil.getPanelBackground()
            add(value, BorderLayout.CENTER)
            add(label, BorderLayout.SOUTH)
        }

        fun value(text: String, color: java.awt.Color) {
            value.text = text
            value.foreground = color
        }
    }

    private inner class IncidentRenderer : ColoredListCellRenderer<Incident>() {
        override fun customizeCellRenderer(list: JList<out Incident>, value: Incident, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = if (value.riskLevel >= FenceRisk.HIGH) AllIcons.General.Error else AllIcons.General.Warning
            append(value.riskLevel.label.uppercase() + "  ", UiSupport.riskAttributes(value.riskLevel))
            append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("   ${value.pluginName} ${value.pluginVersion}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("   ${UiSupport.time(value.timestamp)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            border = JBUI.Borders.empty(4, 8)
        }
    }

    /** Shown only when the agent is missing: exact, copyable setup instructions. */
    private class SetupPanel : JBPanel<SetupPanel>(BorderLayout()) {
        init {
            border = JBUI.Borders.compound(JBUI.Borders.empty(0, 16, 12, 16), JBUI.Borders.customLine(UiSupport.high, 1))
            val text = JBLabel(
                "<html><b>Agent not active.</b> PluginFence can only observe and block plugin operations when its JVM agent is attached.<br>" +
                    "Add the following to the IDE VM options (<i>Help &gt; Edit Custom VM Options</i>) and restart:<br>" +
                    "<code>-javaagent:&lt;path&gt;/plugin-fence-agent.jar</code> (keep <code>plugin-fence-bootstrap.jar</code> next to it)<br>" +
                    "For development: <code>./gradlew runFenceIde</code> starts a sandbox IDE with the agent attached.</html>",
            ).apply { border = JBUI.Borders.empty(8, 10) }
            add(text, BorderLayout.CENTER)
            isVisible = false
        }
    }
}

/** Renders one incident as a vertical attack chain: step, arrow, step, ..., outcome. */
class IncidentDetailPanel(private val engine: FenceEngine) : JBPanel<IncidentDetailPanel>(BorderLayout()) {

    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(12, 16)
    }

    init {
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        show(null)
    }

    fun show(incident: Incident?) {
        content.removeAll()
        if (incident == null) {
            content.add(UiSupport.hint("Select an incident to see the attack chain."))
        } else {
            val title = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(UiSupport.badge(incident.riskLevel.label, UiSupport.riskColor(incident.riskLevel)))
                add(UiSupport.heading(incident.title))
            }
            content.add(title)
            content.add(Box.createVerticalStrut(JBUI.scale(4)))
            content.add(left(JBLabel("${incident.pluginName} ${incident.pluginVersion}   -   ${UiSupport.dateTime(incident.timestamp)}").apply {
                foreground = UIUtil.getContextHelpForeground()
            }))
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            content.add(left(JBLabel("<html><div style='width:420px'>${escape(incident.summary)}</div></html>")))
            content.add(Box.createVerticalStrut(JBUI.scale(14)))

            incident.chain.forEachIndexed { i, step ->
                content.add(left(stepPanel(i + 1, step)))
                content.add(left(arrow()))
            }
            content.add(left(outcomePanel(incident)))
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            content.add(left(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(javax.swing.JButton("Export as JSON", AllIcons.ToolbarDecorator.Export).apply {
                    addActionListener { IncidentExport.export(incident, engine) }
                })
            }))
        }
        content.revalidate()
        content.repaint()
    }

    private fun stepPanel(index: Int, event: FenceEvent): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(10), 0)).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(UiSupport.verdictColor(event.verdict), 0, 3, 0, 0),
                JBUI.Borders.empty(6, 10),
            )
            background = UIUtil.getPanelBackground()
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(64))
        }
        val time = JBLabel(UiSupport.time(event.timestamp)).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
        val what = JBLabel("<html><b>$index. ${event.actionLabel}</b> &nbsp; <code>${escape(UiSupport.shorten(engine.sensitivePaths.displayPath(event.target), 70))}</code></html>")
        val why = JBLabel(event.reason).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() }
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(what); add(why)
        }
        panel.add(time, BorderLayout.WEST)
        panel.add(text, BorderLayout.CENTER)
        panel.add(UiSupport.badge(event.decisionLabel.substringBefore(" "), UiSupport.verdictColor(event.verdict)), BorderLayout.EAST)
        return panel
    }

    private fun arrow(): JComponent = JBLabel("↓").apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.label().deriveFont(JBFont.label().size2D + 4f)
        border = JBUI.Borders.empty(2, 22)
    }

    private fun outcomePanel(incident: Incident): JComponent {
        val last = incident.chain.lastOrNull()
        val color = when {
            last == null -> UiSupport.info
            last.verdict == FenceVerdict.BLOCK -> UiSupport.blocked
            last.verdict == FenceVerdict.ASK -> UiSupport.ask
            else -> UiSupport.info
        }
        val panel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(10), 0)).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(color, 1), JBUI.Borders.empty(8, 12))
            background = UIUtil.getPanelBackground()
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(70))
        }
        val headline = JBLabel(last?.decisionLabel?.substringBefore(" ") ?: "RECORDED").apply {
            font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 2f)
            foreground = color
        }
        val text = JBLabel("<html>${escape(incident.outcome)}<br><b>Risk ${incident.riskScore} / 100 &nbsp; ${incident.riskLevel.label.uppercase()}</b></html>")
        panel.add(headline, BorderLayout.WEST)
        panel.add(text, BorderLayout.CENTER)
        return panel
    }

    private fun left(c: JComponent): JComponent = c.apply { alignmentX = Component.LEFT_ALIGNMENT }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
