package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * What changed about a plugin when it updated.
 *
 * The whole tab is one question: "does version N do things version N-1 never did?" So it is laid
 * out as a diff - old version on the left, new on the right, additions called out - rather than as
 * two separate profiles the reader has to compare by eye.
 */
class DriftPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true), FencePanel {

    private data class Choice(val pluginId: String, val label: String) {
        override fun toString() = label
    }

    private data class Row(val group: String, val item: String, val inOld: Boolean, val inNew: Boolean, val sensitive: Boolean) {
        val status: String
            get() = when {
                inOld && inNew -> UNCHANGED
                inNew -> NEW
                else -> REMOVED
            }
    }

    private val pluginChoice = ComboBox<Choice>()

    private val verdict = UiSupport.heading("")
    private val versionFrom = Pill("-", UiSupport.info)
    private val versionTo = Pill("-", UiSupport.info)
    private val arrow = UiSupport.heading("→").apply { foreground = UIUtil.getContextHelpForeground() }
    private val riskMeter = RiskMeter(120, 7)
    private val riskLabel = JBLabel().apply { font = JBFont.small().asBold() }
    private val summary = WrappedText().muted()
    private val headerCard = FenceCard()

    /** The one thing that must be impossible to miss when an update grabs new powers. */
    private val alarm = FenceCard().apply {
        tint = UiSupport.critical
        padding(10, 14, 10, 14)
        isVisible = false
    }
    private val alarmText = WrappedText()

    private val model = ListTableModel<Row>()
    private val table = JBTable(model)
    private val factors = UiSupport.column(4)

    // Renderers are reused: getRenderer() is called for every cell paint, so allocating a Swing
    // component there would churn the EDT during a live demo.
    private val groupRenderer = RowRenderer(muted = true)
    private val itemRenderer = RowRenderer()
    private val presenceRenderer = RowRenderer(center = true, presence = true)
    private val statusRenderer = StatusRenderer()

    private var selectedId: String? = null
    private var updating = false
    private var oldVersionLabel = ""
    private var newVersionLabel = ""

    init {
        toolbar = buildToolbar()

        pluginChoice.addActionListener {
            if (!updating) {
                selectedId = (pluginChoice.selectedItem as? Choice)?.pluginId
                render()
            }
        }

        alarm.add(JBLabel(AllIcons.General.Error).apply { border = JBUI.Borders.empty(2, 0, 0, 10) }, BorderLayout.WEST)
        alarm.add(
            UiSupport.column(
                UiSupport.TIGHT,
                JBLabel("HIGH-RISK BEHAVIOR CHANGE").apply {
                    font = JBFont.label().asBold()
                    foreground = UiSupport.critical
                },
                alarmText,
            ),
            BorderLayout.CENTER,
        )

        headerCard.padding(12, 14, 12, 14)
        headerCard.add(
            UiSupport.column(
                UiSupport.TIGHT + 2,
                UiSupport.row(UiSupport.GAP, verdict),
                UiSupport.row(UiSupport.GAP, versionFrom, arrow, versionTo, riskMeter, riskLabel),
                summary,
            ),
            BorderLayout.CENTER,
        )

        model.columnInfos = columns()
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.rowHeight = JBUI.scale(28)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.emptyText.text = "Nothing recorded for this plugin yet"
        applyColumnWidths()

        val header = UiSupport.column(UiSupport.GAP, alarm, headerCard).apply {
            border = JBUI.Borders.empty(UiSupport.PAD, UiSupport.PAD, UiSupport.GAP, UiSupport.PAD)
        }

        val factorsBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(UiSupport.GAP, UiSupport.PAD, UiSupport.PAD, UiSupport.PAD)
            add(
                UiSupport.sectionLabel("How this rating was reached").apply { border = JBUI.Borders.emptyBottom(6) },
                BorderLayout.NORTH,
            )
            add(factors, BorderLayout.CENTER)
        }

        setContent(
            JPanel(BorderLayout()).apply {
                add(header, BorderLayout.NORTH)
                add(
                    JBScrollPane(table).apply { border = JBUI.Borders.customLineTop(UiSupport.hairline) },
                    BorderLayout.CENTER,
                )
                add(factorsBox, BorderLayout.SOUTH)
            },
        )
        render()
    }

    override fun component(): JComponent = this

    override fun refresh() {
        val drifts = engine.drifts()
        val names = HashMap<String, String>()
        drifts.forEach { names[it.pluginId] = it.pluginName }
        engine.baselines.allProfiles().forEach { names.putIfAbsent(it.pluginId, it.pluginName) }

        val choices = (drifts.map { it.pluginId } + engine.baselines.pluginIds()).distinct()
            .sortedWith(compareBy({ drifts.none { d -> d.pluginId == it } }, { names[it] ?: it }))
            .map { id ->
                val changed = drifts.any { it.pluginId == id && it.hasChanges }
                Choice(id, (names[id] ?: id) + if (changed) "   behaviour changed" else "")
            }

        updating = true
        try {
            val current = (0 until pluginChoice.itemCount).map { pluginChoice.getItemAt(it) }
            if (current != choices) {
                pluginChoice.removeAllItems()
                choices.forEach { pluginChoice.addItem(it) }
            }
            val index = choices.indexOfFirst { it.pluginId == selectedId }
            when {
                index >= 0 -> pluginChoice.selectedIndex = index
                choices.isNotEmpty() -> {
                    pluginChoice.selectedIndex = 0
                    selectedId = choices[0].pluginId
                }
                else -> selectedId = null
            }
        } finally {
            updating = false
        }
        render()
    }

    private fun render() {
        val pluginId = selectedId
        val profiles = if (pluginId == null) emptyList() else engine.profiles(pluginId)
        val drift = if (pluginId == null) null else engine.baselines.drift(pluginId)
        factors.removeAll()

        if (pluginId == null || profiles.isEmpty()) {
            renderHeader(
                color = UiSupport.info,
                headline = "NO BASELINES YET",
                from = "-",
                to = "-",
                score = null,
                text = "Run a plugin, then update it. PluginFence compares what each version was observed doing and " +
                    "flags anything the new one reaches for that the old one never did.",
            )
            model.items = emptyList()
            factors.add(UiSupport.hint("Nothing to explain yet."))
            finishRender()
            return
        }

        val current = profiles.maxByOrNull { it.lastSeen }!!
        val previous = if (drift != null) {
            profiles.firstOrNull { it.version == drift.oldVersion }
        } else {
            profiles.filter { it.version != current.version }.maxByOrNull { it.lastSeen }
        }
        val newest = if (drift != null) profiles.firstOrNull { it.version == drift.newVersion } ?: current else current
        oldVersionLabel = previous?.version ?: "-"
        newVersionLabel = newest.version
        model.columnInfos = columns()
        applyColumnWidths()

        when {
            drift != null && drift.hasChanges -> {
                val color = if (drift.highRisk) UiSupport.critical else UiSupport.high
                val count = drift.newCapabilityCount
                renderHeader(
                    color = color,
                    headline = "$count NEW CAPABILIT${if (count == 1) "Y" else "IES"}",
                    from = drift.oldVersion,
                    to = drift.newVersion,
                    score = drift.riskScore to drift.riskLevel.label.uppercase(),
                    text = "${newest.pluginName} reached for $count thing${if (count == 1) "" else "s"} in " +
                        "${drift.newVersion} that ${drift.oldVersion} never touched. Every addition is listed below, " +
                        "so you can decide whether the update earned it.",
                )
                alarm.isVisible = drift.highRisk
                alarmText.text = "Version ${drift.newVersion} attempts things version ${drift.oldVersion} never did. " +
                    "Review the additions below before trusting this update."
                drift.riskFactors.forEach { factors.add(factorRow(it.points, it.label, color)) }
            }
            previous != null -> {
                renderHeader(
                    color = UiSupport.allowed,
                    headline = "NO BEHAVIOR CHANGE",
                    from = previous.version,
                    to = newest.version,
                    score = null,
                    text = "${newest.pluginName} ${newest.version} has not shown any capability ${previous.version} lacked.",
                )
                factors.add(UiSupport.hint("Nothing new to explain - the update behaves like its predecessor."))
            }
            else -> {
                renderHeader(
                    color = UiSupport.low,
                    headline = "BASELINE ESTABLISHED",
                    from = newest.version,
                    to = newest.version,
                    score = null,
                    text = "${newest.eventCount} operations recorded for ${newest.pluginName} ${newest.version}. " +
                        "Update the plugin and PluginFence will compare the new version against this baseline.",
                )
                factors.add(UiSupport.hint("Normal behaviour recorded. Nothing to compare against yet."))
            }
        }
        model.items = buildRows(previous, newest, drift)
        finishRender()
    }

    private fun renderHeader(color: Color, headline: String, from: String, to: String, score: Pair<Int, String>?, text: String) {
        alarm.isVisible = false
        headerCard.accent = color
        headerCard.tint = if (color == UiSupport.critical) color else null
        verdict.text = headline
        verdict.foreground = color
        versionFrom.text = from
        versionFrom.color = UiSupport.info
        versionTo.text = to
        versionTo.color = color
        val sameVersion = from == to || from == "-"
        arrow.isVisible = !sameVersion
        versionFrom.isVisible = !sameVersion
        riskMeter.isVisible = score != null
        riskLabel.isVisible = score != null
        score?.let { (value, level) ->
            riskMeter.set(value, color)
            riskLabel.text = "$value / 100  $level"
            riskLabel.foreground = color
        }
        summary.text = text
    }

    private fun finishRender() {
        factors.revalidate()
        factors.repaint()
        headerCard.revalidate()
        headerCard.repaint()
    }

    private fun applyColumnWidths() {
        if (table.columnModel.columnCount < COLUMN_COUNT) return
        listOf(140, 330, 110, 110, 120).forEachIndexed { i, w ->
            table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w)
        }
    }

    private fun columns(): Array<ColumnInfo<Row, String>> =
        arrayOf(GroupColumn(), ItemColumn(), OldColumn(), NewColumn(), StatusColumn())

    private fun buildRows(previous: BehaviorProfile?, current: BehaviorProfile, drift: BehaviorDrift?): List<Row> {
        val rows = ArrayList<Row>()
        val oldCaps = previous?.capabilities ?: emptySet()
        for (capability in Capability.values()) {
            val inOld = capability in oldCaps
            val inNew = capability in current.capabilities
            if (inOld || inNew) {
                rows += Row(
                    "Capability",
                    capability.displayName,
                    inOld,
                    inNew,
                    capability == Capability.SENSITIVE_FILES || capability == Capability.SECRET_ENVIRONMENT,
                )
            }
        }
        val oldSensitive = previous?.sensitiveResources ?: emptySet()
        (oldSensitive + current.sensitiveResources).sorted().forEach {
            rows += Row("Sensitive resource", it, it in oldSensitive, it in current.sensitiveResources, true)
        }
        val oldHosts = previous?.networkHosts ?: emptySet()
        (oldHosts + current.networkHosts).sorted().forEach {
            rows += Row("Network", it, it in oldHosts, it in current.networkHosts, drift?.addedHosts?.contains(it) == true)
        }
        val oldProcesses = previous?.processes ?: emptySet()
        (oldProcesses + current.processes).sorted().forEach {
            rows += Row("Process", it, it in oldProcesses, it in current.processes, drift?.addedProcesses?.contains(it) == true)
        }
        // Additions first: they are the only reason to open this tab.
        return rows.sortedWith(compareBy({ it.inOld && it.inNew }, { !it.inNew }))
    }

    private fun factorRow(points: Int, label: String, color: Color): JComponent =
        UiSupport.row(
            8,
            JBLabel("+$points").apply {
                font = JBFont.small().asBold()
                foreground = color
                preferredSize = Dimension(JBUI.scale(28), preferredSize.height)
            },
            RiskMeter(54, 5).apply { set((points * 2).coerceAtMost(100), color) },
            JBLabel(label),
        )

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
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
        val actions = ActionManager.getInstance().createActionToolbar("PluginFenceDrift", group, true)
        actions.targetComponent = this

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10, 5, 4)
            add(UiSupport.row(8, UiSupport.sectionLabel("Plugin"), pluginChoice), BorderLayout.WEST)
            add(actions.component, BorderLayout.EAST)
        }
    }

    // --- columns ------------------------------------------------------------------------------

    private inner class GroupColumn : ColumnInfo<Row, String>("Behaviour") {
        override fun valueOf(item: Row) = item.group

        override fun getRenderer(item: Row?) = groupRenderer
    }

    private inner class ItemColumn : ColumnInfo<Row, String>("") {
        override fun valueOf(item: Row) = item.item

        override fun getRenderer(item: Row?) = itemRenderer
    }

    private inner class OldColumn : ColumnInfo<Row, String>(oldVersionLabel) {
        override fun valueOf(item: Row) = if (item.inOld) PRESENT else ABSENT

        override fun getRenderer(item: Row?) = presenceRenderer
    }

    private inner class NewColumn : ColumnInfo<Row, String>(newVersionLabel) {
        override fun valueOf(item: Row) = if (item.inNew) PRESENT else ABSENT

        override fun getRenderer(item: Row?) = presenceRenderer
    }

    private inner class StatusColumn : ColumnInfo<Row, String>("Status") {
        override fun valueOf(item: Row) = item.status

        override fun getRenderer(item: Row?) = statusRenderer
    }

    /** Additions are washed in their severity colour so the diff reads without checking the legend. */
    private inner class RowRenderer(
        private val muted: Boolean = false,
        private val center: Boolean = false,
        private val presence: Boolean = false,
    ) : DefaultTableCellRenderer() {

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            border = JBUI.Borders.empty(0, 8)
            horizontalAlignment = if (center) CENTER else LEFT
            font = JBFont.label()
            val item = model.getItem(table.convertRowIndexToModel(row))
            if (!isSelected) {
                background = rowBackground(item, table)
                foreground = when {
                    presence && value == ABSENT -> UIUtil.getContextHelpForeground()
                    presence -> UiSupport.allowed
                    muted -> UIUtil.getContextHelpForeground()
                    else -> UIUtil.getLabelForeground()
                }
            }
            if (item.status == NEW && !muted) font = JBFont.label().asBold()
            return this
        }
    }

    /** The verdict for one row, as a pill. */
    private inner class StatusRenderer : JPanel(GridBagLayout()), TableCellRenderer {

        private val pill = Pill("", UiSupport.info)

        init {
            isOpaque = true
            add(pill, GridBagConstraints())
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val item = model.getItem(table.convertRowIndexToModel(row))
            background = if (isSelected) table.selectionBackground else rowBackground(item, table)
            pill.text = item.status
            pill.color = when {
                isSelected -> table.selectionForeground
                item.status == NEW -> if (item.sensitive) UiSupport.critical else UiSupport.high
                item.status == REMOVED -> UiSupport.info
                else -> UiSupport.allowed
            }
            return this
        }
    }

    private fun rowBackground(item: Row, table: JTable): Color = when {
        item.status == NEW && item.sensitive -> com.intellij.ui.ColorUtil.mix(table.background, UiSupport.critical, 0.12)
        item.status == NEW -> com.intellij.ui.ColorUtil.mix(table.background, UiSupport.high, 0.10)
        else -> table.background
    }

    private companion object {
        const val NEW = "NEW"
        const val REMOVED = "REMOVED"
        const val UNCHANGED = "UNCHANGED"
        const val PRESENT = "✓"
        const val ABSENT = "–"
        const val COLUMN_COUNT = 5
    }
}
