package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
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
import com.intellij.util.ui.components.BorderLayoutPanel
import com.pluginfence.engine.FenceEngine
import com.pluginfence.model.FenceEvent
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer

/** Firewall-style activity log: filterable table plus a details pane with risk factors and actions. */
class ActivityPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true) {

    private val search = SearchTextField(false)
    private val pluginFilter = ComboBox(arrayOf(ALL))
    private val operationFilter = ComboBox(arrayOf(ALL) + FenceOperation.values().map { it.label })
    private val severityFilter = ComboBox(arrayOf(ALL, "High and above", "Medium and above"))
    private val preventedOnly = JBCheckBox("Prevented only")
    private val clearButton = JButton("Clear", AllIcons.Actions.GC)

    private val model = ListTableModel<FenceEvent>(TimeColumn, PluginColumn, ActionColumn, TargetColumn, DecisionColumn, RiskColumn)
    private val table = JBTable(model)
    private val details = EventDetailsPanel(engine)
    private var selectedId: Long? = null
    private var allEvents: List<FenceEvent> = emptyList()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(2, 8)
            add(search.apply { preferredSize = java.awt.Dimension(JBUI.scale(200), preferredSize.height) })
            add(JBLabel("Plugin:")); add(pluginFilter)
            add(JBLabel("Action:")); add(operationFilter)
            add(JBLabel("Risk:")); add(severityFilter)
            add(preventedOnly)
            add(clearButton)
        }
        search.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilters()
        })
        pluginFilter.addActionListener { applyFilters() }
        operationFilter.addActionListener { applyFilters() }
        severityFilter.addActionListener { applyFilters() }
        preventedOnly.addActionListener { applyFilters() }
        clearButton.addActionListener { engine.clearHistory() }

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(24)
        table.emptyText.text = "No activity yet. Run a plugin action to see it here."
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(70)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(90)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(360)
        table.columnModel.getColumn(4).preferredWidth = JBUI.scale(110)
        table.columnModel.getColumn(5).preferredWidth = JBUI.scale(90)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = table.selectedRow
                val event = if (row >= 0) model.getItem(table.convertRowIndexToModel(row)) else null
                selectedId = event?.id
                details.show(event)
            }
        }

        val splitter = OnePixelSplitter(true, 0.6f).apply {
            firstComponent = JBScrollPane(table)
            secondComponent = details
        }
        val root = BorderLayoutPanel().addToTop(toolbar).addToCenter(splitter)
        setContent(root)
    }

    fun refresh() {
        allEvents = engine.events()
        val plugins = allEvents.map { it.displayPlugin }.distinct().sorted()
        val current = pluginFilter.selectedItem as? String ?: ALL
        val items = listOf(ALL) + plugins
        if ((0 until pluginFilter.itemCount).map { pluginFilter.getItemAt(it) } != items) {
            pluginFilter.removeAllItems()
            items.forEach { pluginFilter.addItem(it) }
            pluginFilter.selectedItem = if (current in items) current else ALL
        }
        applyFilters()
    }

    private fun applyFilters() {
        val query = search.text.trim().lowercase()
        val plugin = pluginFilter.selectedItem as? String ?: ALL
        val op = operationFilter.selectedItem as? String ?: ALL
        val minRisk = when (severityFilter.selectedItem as? String) {
            "High and above" -> FenceRisk.HIGH
            "Medium and above" -> FenceRisk.MEDIUM
            else -> FenceRisk.INFO
        }
        val filtered = allEvents.filter { e ->
            (plugin == ALL || e.displayPlugin == plugin) &&
                (op == ALL || e.actionLabel == op) &&
                e.riskLevel >= minRisk &&
                (!preventedOnly.isSelected || e.prevented) &&
                (query.isEmpty() || e.target.lowercase().contains(query) || e.displayPlugin.lowercase().contains(query) ||
                    e.reason.lowercase().contains(query) || e.api.lowercase().contains(query))
        }
        model.items = filtered
        val index = filtered.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            table.setRowSelectionInterval(index, index)
        } else if (filtered.isNotEmpty() && selectedId == null) {
            table.setRowSelectionInterval(0, 0)
        } else if (filtered.isEmpty()) {
            details.show(null)
        }
    }

    // --- columns ----------------------------------------------------------------------------

    private object TimeColumn : ColumnInfo<FenceEvent, String>("Time") {
        override fun valueOf(item: FenceEvent) = UiSupport.time(item.timestamp)
        override fun getRenderer(item: FenceEvent?) = MutedRenderer
    }

    private object PluginColumn : ColumnInfo<FenceEvent, String>("Plugin") {
        override fun valueOf(item: FenceEvent) = item.displayPlugin + if (item.pluginVersion.isNotBlank()) " ${item.pluginVersion}" else ""
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
        override fun valueOf(item: FenceEvent) = item.decisionLabel
        override fun getRenderer(item: FenceEvent?) = DecisionRenderer
    }

    private object RiskColumn : ColumnInfo<FenceEvent, String>("Risk") {
        override fun valueOf(item: FenceEvent) = item.riskLevel.label
        override fun getRenderer(item: FenceEvent?) = RiskRenderer
        override fun getComparator(): Comparator<FenceEvent> = compareBy { it.riskScore }
    }

    private abstract class EventRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            icon = null
            font = JBFont.label()
            border = JBUI.Borders.empty(0, 6)
            val event = (table.model as? ListTableModel<*>)?.getItem(table.convertRowIndexToModel(row)) as? FenceEvent
            if (event != null) customize(event, isSelected)
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
            if (event.sensitiveCategory != null && !selected) foreground = UiSupport.high
        }
    }

    private object DecisionRenderer : EventRenderer() {
        override fun customize(event: FenceEvent, selected: Boolean) {
            icon = UiSupport.verdictIcon(event.verdict)
            font = JBFont.label().asBold()
            if (!selected) foreground = UiSupport.verdictColor(event.verdict)
        }
    }

    private object RiskRenderer : EventRenderer() {
        override fun customize(event: FenceEvent, selected: Boolean) {
            font = JBFont.label().asBold()
            text = "${event.riskLevel.label}  ${event.riskScore}"
            if (!selected) foreground = UiSupport.riskColor(event.riskLevel)
        }
    }

    companion object {
        private const val ALL = "All"
    }
}

/** Key/value details for one event, including the deterministic risk breakdown and permission actions. */
class EventDetailsPanel(private val engine: FenceEngine) : JBPanel<EventDetailsPanel>(BorderLayout()) {

    private val content = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(10, 14)
    }

    init {
        add(JBScrollPane(content).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        show(null)
    }

    fun show(event: FenceEvent?) {
        content.removeAll()
        if (event == null) {
            content.add(UiSupport.hint("Select an event to see details."))
        } else {
            val head = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                add(UiSupport.badge(event.decisionLabel.substringBefore(" "), UiSupport.verdictColor(event.verdict)))
                add(UiSupport.badge("${event.riskLevel.label} ${event.riskScore}", UiSupport.riskColor(event.riskLevel)))
                add(UiSupport.subheading("${event.actionLabel} by ${event.displayPlugin}"))
            }
            content.add(head)
            content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))

            val rows = mutableListOf(
                "Plugin" to "${event.displayPlugin}  (${event.pluginId}${if (event.pluginVersion.isNotBlank()) ", v" + event.pluginVersion else ""})",
                "Time" to UiSupport.dateTime(event.timestamp),
                "Target" to engine.sensitivePaths.displayPath(event.target),
            )
            if (event.metadata["args"]?.isNotBlank() == true) rows += "Arguments" to event.metadata["args"]!!
            if (event.metadata["url"]?.isNotBlank() == true) rows += "URL" to event.metadata["url"]!!
            if (event.sensitiveCategory != null) rows += "Sensitive category" to event.sensitiveCategory
            if (event.pathRelation != null) rows += "Location" to event.pathRelation.name.lowercase().replace('_', ' ')
            rows += "Capability" to (event.capability?.displayName ?: "not governed")
            rows += "Decision" to "${event.decisionLabel} - ${event.reason}  [${event.ruleId}]"
            rows += "API" to event.api
            rows += "Source class" to event.sourceClass
            rows.forEach { (k, v) -> content.add(row(k, v)) }

            content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)))
            content.add(left(UiSupport.subheading("Risk factors")))
            if (event.riskFactors.isEmpty()) {
                content.add(row("", "none - routine operation"))
            } else {
                event.riskFactors.forEach { f -> content.add(row("+${f.points}", f.label)) }
            }

            if (event.verdict == FenceVerdict.ASK || (event.verdict == FenceVerdict.BLOCK && event.capability != null)) {
                content.add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
                val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(JButton("Allow Once").apply { addActionListener { engine.allowOnce(event) } })
                    add(JButton("Always Allow ${event.approvalTarget}").apply { addActionListener { engine.alwaysAllow(event) } })
                    add(UiSupport.hint("Then retry the plugin action."))
                }
                content.add(actions)
            }
        }
        content.revalidate()
        content.repaint()
    }

    private fun row(key: String, value: String): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(22))
        }
        val k = JBLabel(key).apply {
            foreground = UIUtil.getContextHelpForeground()
            preferredSize = java.awt.Dimension(JBUI.scale(110), preferredSize.height)
        }
        val v = JBLabel(value).apply { toolTipText = value }
        panel.add(k, BorderLayout.WEST)
        panel.add(v, BorderLayout.CENTER)
        return panel
    }

    private fun left(c: JComponent): JComponent = c.apply { alignmentX = Component.LEFT_ALIGNMENT }
}
