package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

/**
 * The firewall log: every intercepted operation, what PluginFence decided, and why.
 *
 * The filter bar is the point of the tab - a security log is only useful if you can narrow it to
 * "what did this plugin try that I stopped?" in two clicks - so filters sit above the table and
 * report how much of the log they are hiding.
 */
class ActivityPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true), FencePanel {

    private val search = SearchTextField(false)
    private val pluginFilter = ComboBox(arrayOf(ALL_PLUGINS))
    private val operationFilter = ComboBox(arrayOf(ALL_ACTIONS) + FenceOperation.values().map { it.label })
    private val riskFilter = SegmentedControl(listOf(RISK_ALL, RISK_MEDIUM, RISK_HIGH), { it }) {
        when (it) {
            RISK_HIGH -> UiSupport.high
            RISK_MEDIUM -> UiSupport.medium
            else -> UiSupport.accent
        }
    }
    private val preventedOnly = JBCheckBox("Prevented only")
    private val counter = JBLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }

    private val model = ListTableModel<FenceEvent>(TimeColumn, PluginColumn, ActionColumn, TargetColumn, DecisionColumn, RiskColumn)
    private val table = JBTable(model)
    private val details = EventDetailsPanel(engine)
    private var selectedId: Long? = null
    private var allEvents: List<FenceEvent> = emptyList()

    init {
        toolbar = buildFilterBar()

        riskFilter.onSelect = { applyFilters() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilters()
        })
        pluginFilter.addActionListener { applyFilters() }
        operationFilter.addActionListener { applyFilters() }
        preventedOnly.addActionListener { applyFilters() }

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.rowHeight = JBUI.scale(28)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.setAutoCreateRowSorter(true)
        table.emptyText.text = "No activity yet"
        table.emptyText.appendLine("Every file, environment, process and network call a third-party plugin makes shows up here.")
        listOf(72, 170, 104, 380, 118, 110).forEachIndexed { i, width ->
            table.columnModel.getColumn(i).preferredWidth = JBUI.scale(width)
        }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val event = eventAt(table, table.selectedRow)
                selectedId = event?.id
                details.show(event)
            }
        }

        val splitter = OnePixelSplitter(true, 0.58f).apply {
            firstComponent = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
            secondComponent = details
        }
        setContent(splitter)
    }

    override fun component(): JComponent = this

    override fun refresh() {
        allEvents = engine.events()
        val plugins = allEvents.map { it.displayPlugin }.distinct().sorted()
        val current = pluginFilter.selectedItem as? String ?: ALL_PLUGINS
        val items = listOf(ALL_PLUGINS) + plugins
        if ((0 until pluginFilter.itemCount).map { pluginFilter.getItemAt(it) } != items) {
            pluginFilter.removeAllItems()
            items.forEach { pluginFilter.addItem(it) }
            pluginFilter.selectedItem = if (current in items) current else ALL_PLUGINS
        }
        applyFilters()
    }

    /** Entry point for the Overview tiles: show me only what PluginFence actually stopped. */
    fun showPreventedOnly() {
        search.text = ""
        pluginFilter.selectedItem = ALL_PLUGINS
        operationFilter.selectedItem = ALL_ACTIONS
        riskFilter.selected = RISK_ALL
        preventedOnly.isSelected = true
        applyFilters()
    }

    /** Entry point from an incident: show me everything this plugin did, prevented or not. */
    fun showPlugin(pluginName: String) {
        search.text = ""
        operationFilter.selectedItem = ALL_ACTIONS
        riskFilter.selected = RISK_ALL
        preventedOnly.isSelected = false
        val known = (0 until pluginFilter.itemCount).any { pluginFilter.getItemAt(it) == pluginName }
        pluginFilter.selectedItem = if (known) pluginName else ALL_PLUGINS
        applyFilters()
    }

    private fun applyFilters() {
        val query = search.text.trim().lowercase()
        val plugin = pluginFilter.selectedItem as? String ?: ALL_PLUGINS
        val op = operationFilter.selectedItem as? String ?: ALL_ACTIONS
        val minRisk = when (riskFilter.selected) {
            RISK_HIGH -> FenceRisk.HIGH
            RISK_MEDIUM -> FenceRisk.MEDIUM
            else -> FenceRisk.INFO
        }
        val filtered = allEvents.filter { e ->
            (plugin == ALL_PLUGINS || e.displayPlugin == plugin) &&
                (op == ALL_ACTIONS || e.actionLabel == op) &&
                e.riskLevel >= minRisk &&
                (!preventedOnly.isSelected || e.prevented) &&
                (
                    query.isEmpty() || e.target.lowercase().contains(query) || e.displayPlugin.lowercase().contains(query) ||
                        e.reason.lowercase().contains(query) || e.api.lowercase().contains(query)
                    )
        }
        model.items = filtered
        renderCounter(filtered.size, allEvents.size)

        val modelIndex = filtered.indexOfFirst { it.id == selectedId }
        when {
            modelIndex >= 0 -> select(modelIndex)
            filtered.isNotEmpty() && selectedId == null -> select(0)
            filtered.isEmpty() -> details.show(null)
        }
    }

    private fun select(modelIndex: Int) {
        val viewIndex = runCatching { table.convertRowIndexToView(modelIndex) }.getOrDefault(-1)
        if (viewIndex >= 0) table.setRowSelectionInterval(viewIndex, viewIndex)
    }

    private fun renderCounter(shown: Int, total: Int) {
        counter.text = if (shown == total) {
            if (total == 1) "1 operation" else "$total operations"
        } else {
            "$shown of $total"
        }
        counter.foreground = if (shown == total) UIUtil.getContextHelpForeground() else UiSupport.accent
    }

    private fun buildFilterBar(): JComponent {
        search.preferredSize = Dimension(JBUI.scale(190), search.preferredSize.height)
        search.textEditor.emptyText.text = "Search path, host, reason or API"

        val group = DefaultActionGroup()
        group.add(object : AnAction("Clear Activity History", "Remove recorded events and incidents", AllIcons.Actions.GC), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun actionPerformed(e: AnActionEvent) = engine.clearHistory()
        })
        val actions = ActionManager.getInstance().createActionToolbar("PluginFenceActivity", group, true)
        actions.targetComponent = this

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10, 5, 4)
            add(
                UiSupport.row(8, search, riskFilter, pluginFilter, operationFilter, preventedOnly),
                BorderLayout.WEST,
            )
            add(
                UiSupport.row(6, counter, actions.component),
                BorderLayout.EAST,
            )
        }
    }

    // --- columns ------------------------------------------------------------------------------

    private object TimeColumn : ColumnInfo<FenceEvent, String>("Time") {
        override fun valueOf(item: FenceEvent) = UiSupport.time(item.timestamp)

        override fun getRenderer(item: FenceEvent?) = MutedRenderer
    }

    private object PluginColumn : ColumnInfo<FenceEvent, String>("Plugin") {
        override fun valueOf(item: FenceEvent) =
            item.displayPlugin + if (item.pluginVersion.isNotBlank()) " ${item.pluginVersion}" else ""
    }

    private object ActionColumn : ColumnInfo<FenceEvent, String>("Action") {
        override fun valueOf(item: FenceEvent) = item.actionLabel

        override fun getRenderer(item: FenceEvent?) = ActionRenderer
    }

    private object TargetColumn : ColumnInfo<FenceEvent, String>("Target") {
        override fun valueOf(item: FenceEvent) = item.target

        override fun getRenderer(item: FenceEvent?) = TargetRenderer
    }

    private object DecisionColumn : ColumnInfo<FenceEvent, String>("Decision") {
        private val renderer = PillRenderer()

        override fun valueOf(item: FenceEvent) = UiSupport.verdictShort(item.verdict)

        override fun getRenderer(item: FenceEvent?) = renderer
    }

    /**
     * Sorted by the numeric score, not by the label, so "sort by risk" puts the worst thing the
     * plugin did at the top rather than grouping alphabetically.
     */
    private object RiskColumn : ColumnInfo<FenceEvent, Int>("Risk") {
        override fun valueOf(item: FenceEvent) = item.riskScore

        private val renderer = RiskCellRenderer()

        override fun getColumnClass(): Class<*> = Int::class.javaObjectType

        override fun getRenderer(item: FenceEvent?) = renderer
    }

    // --- renderers ----------------------------------------------------------------------------

    private abstract class EventRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            icon = null
            font = JBFont.label()
            border = JBUI.Borders.empty(0, 8)
            eventAt(table, row)?.let { customize(it, isSelected) }
            return this
        }

        abstract fun customize(event: FenceEvent, selected: Boolean)
    }

    private object MutedRenderer : EventRenderer() {
        override fun customize(event: FenceEvent, selected: Boolean) {
            if (!selected) foreground = UIUtil.getContextHelpForeground()
        }
    }

    private object ActionRenderer : EventRenderer() {
        override fun customize(event: FenceEvent, selected: Boolean) {
            icon = UiSupport.operationIcon(event.operation)
        }
    }

    private object TargetRenderer : EventRenderer() {
        override fun customize(event: FenceEvent, selected: Boolean) {
            toolTipText = event.target
            font = monoFont()
            if (event.sensitiveCategory != null && !selected) foreground = UiSupport.high
        }
    }

    /** The verdict as a pill: the one column people scan down, so it gets the strongest shape. */
    private class PillRenderer : JPanel(GridBagLayout()), TableCellRenderer {

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
            background = if (isSelected) table.selectionBackground else table.background
            val event = eventAt(table, row)
            pill.text = event?.let { UiSupport.verdictShort(it.verdict) } ?: value?.toString().orEmpty()
            pill.color = when {
                isSelected -> table.selectionForeground
                event != null -> UiSupport.verdictColor(event.verdict)
                else -> UiSupport.info
            }
            return this
        }
    }

    /** Risk as a bar plus the score: proportion first, precision second. */
    private class RiskCellRenderer : JPanel(BorderLayout(JBUI.scale(8), 0)), TableCellRenderer {

        private val meter = RiskMeter(46, 6)
        private val label = JBLabel().apply { font = JBFont.small().asBold() }

        init {
            isOpaque = true
            border = JBUI.Borders.empty(0, 8)
            add(meter, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            background = if (isSelected) table.selectionBackground else table.background
            val event = eventAt(table, row)
            val color = event?.let { UiSupport.riskColor(it.riskLevel) } ?: UiSupport.info
            meter.set(event?.riskScore ?: 0, color)
            label.text = "${event?.riskScore ?: 0}  ${event?.riskLevel?.label?.uppercase().orEmpty()}"
            label.foreground = if (isSelected) table.selectionForeground else color
            return this
        }
    }

    companion object {
        private const val ALL_PLUGINS = "All plugins"
        private const val ALL_ACTIONS = "All actions"
        private const val RISK_ALL = "All risk"
        private const val RISK_MEDIUM = "Medium+"
        private const val RISK_HIGH = "High+"

        /** Recomputed rather than cached: the label font follows the IDE's font-size setting. */
        private fun monoFont(): Font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))

        private fun eventAt(table: JTable, row: Int): FenceEvent? {
            if (row < 0) return null
            val model = table.model as? ListTableModel<*> ?: return null
            val index = runCatching { table.convertRowIndexToModel(row) }.getOrDefault(-1)
            if (index < 0 || index >= model.rowCount) return null
            return model.getItem(index) as? FenceEvent
        }
    }
}

/**
 * Everything known about one operation, in the order a reviewer asks for it: what was decided,
 * what was touched, who touched it, and how the score was arrived at.
 *
 * The risk breakdown is the important part - a number nobody can explain is a number nobody
 * trusts, so every point is attributed to a named factor.
 */
class EventDetailsPanel(private val engine: FenceEngine) : JBPanel<EventDetailsPanel>(BorderLayout()) {

    private val content = UiSupport.column(0).apply { border = JBUI.Borders.empty(UiSupport.PAD - 2, UiSupport.PAD) }

    private val scroll = JBScrollPane(content).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = JBUI.scale(16)
    }

    private val empty = UiSupport.emptyState(
        "Nothing selected",
        "Select an operation above to see its target, its rule and how its risk score was built.",
        AllIcons.General.Information,
    )

    private var showingEmpty = true

    init {
        add(empty, BorderLayout.CENTER)
    }

    fun show(event: FenceEvent?) {
        if (event == null) {
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
            render(event)
        }
        revalidate()
        repaint()
    }

    private fun render(event: FenceEvent) {
        val verdictColor = UiSupport.verdictColor(event.verdict)
        val riskColor = UiSupport.riskColor(event.riskLevel)

        content.add(
            UiSupport.row(
                UiSupport.GAP,
                Pill(UiSupport.verdictShort(event.verdict), verdictColor, solid = true),
                Pill("${event.riskLevel.label.uppercase()} ${event.riskScore}", riskColor),
                UiSupport.subheading("${event.actionLabel} by ${event.displayPlugin}"),
            ),
        )
        content.add(UiSupport.spacer(UiSupport.GAP))

        content.add(field("Target", engine.sensitivePaths.displayPath(event.target), mono = true))
        event.metadata["args"]?.takeIf { it.isNotBlank() }?.let { content.add(field("Arguments", it, mono = true)) }
        event.metadata["url"]?.takeIf { it.isNotBlank() }?.let { content.add(field("URL", it, mono = true)) }
        content.add(
            field(
                "Plugin",
                buildString {
                    append(event.displayPlugin)
                    if (event.pluginVersion.isNotBlank()) append(" v").append(event.pluginVersion)
                    append("  (").append(event.pluginId).append(')')
                },
            ),
        )
        content.add(field("Time", UiSupport.dateTime(event.timestamp)))
        event.sensitiveCategory?.let { content.add(field("Sensitive", it)) }
        event.pathRelation?.let { content.add(field("Location", it.name.lowercase().replace('_', ' '))) }
        content.add(field("Capability", event.capability?.displayName ?: "not governed"))
        content.add(field("Rule", "${event.ruleId}  -  ${event.reason}"))
        content.add(field("API", event.api, mono = true))
        content.add(field("Source", event.sourceClass, mono = true))

        content.add(UiSupport.spacer(UiSupport.PAD))
        content.add(UiSupport.sectionLabel("Why this scored ${event.riskScore}"))
        content.add(UiSupport.spacer(UiSupport.TIGHT + 2))
        if (event.riskFactors.isEmpty()) {
            content.add(UiSupport.hint("No risk factors - a routine operation for this plugin."))
        } else {
            event.riskFactors.forEach { factor ->
                content.add(
                    UiSupport.row(
                        8,
                        JBLabel("+${factor.points}").apply {
                            font = JBFont.small().asBold()
                            foreground = riskColor
                            preferredSize = Dimension(JBUI.scale(28), preferredSize.height)
                        },
                        RiskMeter(54, 5).apply { set((factor.points * 2).coerceAtMost(100), riskColor) },
                        JBLabel(factor.label),
                    ),
                )
            }
        }

        if (event.verdict == FenceVerdict.ASK || (event.verdict == FenceVerdict.BLOCK && event.capability != null)) {
            content.add(UiSupport.spacer(UiSupport.PAD))
            val actions = FenceCard().apply {
                tint = verdictColor
                padding(10, 12, 10, 12)
            }
            actions.add(
                UiSupport.column(
                    UiSupport.TIGHT + 2,
                    UiSupport.subheading("This operation was prevented"),
                    UiSupport.hint("Grant access here, then retry the plugin action - PluginFence never replays it for you."),
                    UiSupport.row(
                        6,
                        JButton("Allow Once").apply { addActionListener { engine.allowOnce(event) } },
                        JButton("Always Allow ${UiSupport.shorten(event.approvalTarget, 36)}").apply {
                            addActionListener { engine.alwaysAllow(event) }
                        },
                    ),
                ),
                BorderLayout.CENTER,
            )
            content.add(actions)
        }
    }

    private fun field(key: String, value: String, mono: Boolean = false): JComponent {
        val label = UiSupport.sectionLabel(key).apply {
            preferredSize = Dimension(JBUI.scale(94), preferredSize.height)
            border = JBUI.Borders.emptyTop(2)
        }
        val text = (if (mono) UiSupport.mono(value) else JBLabel(value)).apply { toolTipText = value }
        return Stack(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyBottom(JBUI.scale(3))
            add(label, BorderLayout.WEST)
            add(text, BorderLayout.CENTER)
        }
    }
}
