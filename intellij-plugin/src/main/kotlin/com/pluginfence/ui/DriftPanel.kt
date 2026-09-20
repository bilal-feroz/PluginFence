package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
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
import com.pluginfence.model.BehaviorDrift
import com.pluginfence.model.BehaviorProfile
import com.pluginfence.model.Capability
import com.pluginfence.model.FenceRisk
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer

/**
 * Side-by-side comparison of what two versions of a plugin were observed doing.
 * Highlights what the newer version does that the previous one never did.
 */
class DriftPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true) {

    private data class Choice(val pluginId: String, val label: String) {
        override fun toString() = label
    }

    private data class Row(val group: String, val item: String, val inOld: Boolean, val inNew: Boolean, val sensitive: Boolean) {
        val status: String get() = when {
            inOld && inNew -> "UNCHANGED"
            inNew -> "NEW"
            else -> "REMOVED"
        }
    }

    private val pluginChoice = ComboBox<Choice>()
    private val banner = JBPanel<JBPanel<*>>(BorderLayout()).apply { isVisible = false }
    private val bannerTitle = JBLabel().apply { font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 3f) }
    private val bannerText = JBLabel()
    private val headline = JBLabel()
    private val versions = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val model = ListTableModel<Row>()
    private val table = JBTable(model)
    private val factors = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private var selectedId: String? = null
    private var updating = false
    private var oldVersionLabel = ""
    private var newVersionLabel = ""

    init {
        pluginChoice.addActionListener {
            if (!updating) {
                selectedId = (pluginChoice.selectedItem as? Choice)?.pluginId
                render()
            }
        }
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(2, 8)
            add(JBLabel("Plugin:")); add(pluginChoice)
        }

        banner.border = BorderFactory.createCompoundBorder(JBUI.Borders.empty(6, 16, 0, 16), JBUI.Borders.customLine(UiSupport.critical, 1))
        val bannerInner = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12)
            add(bannerTitle); add(bannerText)
        }
        banner.add(JBLabel(AllIcons.General.Error).apply { border = JBUI.Borders.empty(0, 12, 0, 0) }, BorderLayout.WEST)
        banner.add(bannerInner, BorderLayout.CENTER)

        val head = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10, 16, 6, 16)
            add(headline.apply { font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 4f); alignmentX = Component.LEFT_ALIGNMENT })
            add(versions.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

        model.columnInfos = arrayOf(GroupColumn(), ItemColumn(), OldColumn(), NewColumn(), StatusColumn())
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(26)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.emptyText.text = "No behaviour recorded for this plugin yet."
        applyColumnWidths()

        val factorsBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 16, 12, 16)
            add(UiSupport.subheading("Why this is rated the way it is").apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(factors, BorderLayout.CENTER)
        }

        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar, BorderLayout.NORTH)
            add(banner, BorderLayout.CENTER)
            add(head, BorderLayout.SOUTH)
        }
        val root = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(factorsBox, BorderLayout.SOUTH)
        }
        setContent(root)
        render()
    }

    fun refresh() {
        val drifts = engine.drifts()
        val withProfiles = engine.baselines.pluginIds()
        val names = HashMap<String, String>()
        drifts.forEach { names[it.pluginId] = it.pluginName }
        engine.baselines.allProfiles().forEach { names.putIfAbsent(it.pluginId, it.pluginName) }
        val choices = (drifts.map { it.pluginId } + withProfiles).distinct().sortedWith(compareBy({ drifts.none { d -> d.pluginId == it } }, { names[it] ?: it }))
            .map { id -> Choice(id, (names[id] ?: id) + if (drifts.any { it.pluginId == id && it.hasChanges }) "  (behaviour changed)" else "") }
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
                choices.isNotEmpty() -> { pluginChoice.selectedIndex = 0; selectedId = choices[0].pluginId }
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
            banner.isVisible = false
            headline.text = "No behaviour baselines yet"
            versions.text = "Run a plugin, then update it: PluginFence compares what each version was observed doing."
            model.items = emptyList()
            factors.add(UiSupport.hint("Nothing to explain yet."))
            factors.revalidate(); factors.repaint()
            return
        }
        val current = profiles.maxByOrNull { it.lastSeen }!!
        val previous = if (drift != null) profiles.firstOrNull { it.version == drift.oldVersion } else profiles.filter { it.version != current.version }.maxByOrNull { it.lastSeen }
        val newest = if (drift != null) profiles.firstOrNull { it.version == drift.newVersion } ?: current else current
        oldVersionLabel = previous?.version ?: "-"
        newVersionLabel = newest.version
        model.columnInfos = arrayOf(GroupColumn(), ItemColumn(), OldColumn(), NewColumn(), StatusColumn())
        applyColumnWidths()

        if (drift != null && drift.hasChanges) {
            headline.text = "${drift.newCapabilityCount} NEW CAPABILIT${if (drift.newCapabilityCount == 1) "Y" else "IES"}"
            headline.foreground = if (drift.highRisk) UiSupport.critical else UiSupport.high
            versions.text = "${newest.pluginName}   ${drift.oldVersion}  →  ${drift.newVersion}   -   risk ${drift.riskScore}/100 ${drift.riskLevel.label.uppercase()}"
            banner.isVisible = drift.highRisk
            bannerTitle.text = "HIGH-RISK BEHAVIOR CHANGE"
            bannerTitle.foreground = UiSupport.critical
            bannerText.text = "Version ${drift.newVersion} attempts things ${drift.oldVersion} never did. Review the new capabilities below before trusting this update."
            drift.riskFactors.forEach { factors.add(factorRow("+${it.points}", it.label)) }
        } else if (previous != null) {
            headline.text = "NO BEHAVIOR CHANGE"
            headline.foreground = UiSupport.allowed
            versions.text = "${newest.pluginName}   ${previous.version}  →  ${newest.version}   -   identical observed behaviour"
            banner.isVisible = false
            factors.add(UiSupport.hint("The newer version has not shown any capability the previous one lacked."))
        } else {
            headline.text = "BASELINE ESTABLISHED"
            headline.foreground = UIUtil.getLabelForeground()
            versions.text = "${newest.pluginName} ${newest.version}   -   ${newest.eventCount} observed operations. Update the plugin to compare versions."
            banner.isVisible = false
            factors.add(UiSupport.hint("Normal behaviour recorded. Nothing to compare against yet."))
        }
        model.items = buildRows(previous, newest, drift)
        factors.revalidate(); factors.repaint()
    }

    private fun applyColumnWidths() {
        if (table.columnModel.columnCount < 5) return
        listOf(130, 320, 110, 110, 110).forEachIndexed { i, w -> table.columnModel.getColumn(i).preferredWidth = JBUI.scale(w) }
    }

    private fun buildRows(previous: BehaviorProfile?, current: BehaviorProfile, drift: BehaviorDrift?): List<Row> {
        val rows = ArrayList<Row>()
        val oldCaps = previous?.capabilities ?: emptySet()
        for (cap in Capability.values()) {
            val inOld = cap in oldCaps
            val inNew = cap in current.capabilities
            if (inOld || inNew) rows += Row("Capability", cap.displayName, inOld, inNew, cap == Capability.SENSITIVE_FILES || cap == Capability.SECRET_ENVIRONMENT)
        }
        val oldSensitive = previous?.sensitiveResources ?: emptySet()
        (oldSensitive + current.sensitiveResources).sorted().forEach { rows += Row("Sensitive resource", it, it in oldSensitive, it in current.sensitiveResources, true) }
        val oldHosts = previous?.networkHosts ?: emptySet()
        (oldHosts + current.networkHosts).sorted().forEach { rows += Row("Network", it, it in oldHosts, it in current.networkHosts, drift?.addedHosts?.contains(it) == true) }
        val oldProcs = previous?.processes ?: emptySet()
        (oldProcs + current.processes).sorted().forEach { rows += Row("Process", it, it in oldProcs, it in current.processes, drift?.addedProcesses?.contains(it) == true) }
        return rows.sortedWith(compareBy({ it.inOld && it.inNew }, { !it.inNew }))
    }

    private fun factorRow(points: String, label: String): Component {
        val p = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        p.add(JBLabel(points).apply { font = JBFont.label().asBold(); foreground = UiSupport.high; preferredSize = java.awt.Dimension(JBUI.scale(36), preferredSize.height) })
        p.add(JBLabel(label))
        return p
    }

    // --- columns ------------------------------------------------------------------------------

    private inner class GroupColumn : ColumnInfo<Row, String>("Behaviour") {
        override fun valueOf(item: Row) = item.group
        override fun getRenderer(item: Row?) = RowRenderer(muted = true)
    }

    private inner class ItemColumn : ColumnInfo<Row, String>("") {
        override fun valueOf(item: Row) = item.item
        override fun getRenderer(item: Row?) = RowRenderer()
    }

    private inner class OldColumn : ColumnInfo<Row, String>(oldVersionLabel) {
        override fun valueOf(item: Row) = if (item.inOld) "✓" else "-"
        override fun getRenderer(item: Row?) = RowRenderer(center = true)
    }

    private inner class NewColumn : ColumnInfo<Row, String>(newVersionLabel) {
        override fun valueOf(item: Row) = if (item.inNew) "✓" else "-"
        override fun getRenderer(item: Row?) = RowRenderer(center = true)
    }

    private inner class StatusColumn : ColumnInfo<Row, String>("Status") {
        override fun valueOf(item: Row) = item.status
        override fun getRenderer(item: Row?) = RowRenderer(status = true)
    }

    private inner class RowRenderer(private val muted: Boolean = false, private val center: Boolean = false, private val status: Boolean = false) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            border = JBUI.Borders.empty(0, 8)
            horizontalAlignment = if (center) CENTER else LEFT
            font = JBFont.label()
            val item = model.getItem(table.convertRowIndexToModel(row))
            if (!isSelected) {
                foreground = when {
                    status && item.status == "NEW" -> if (item.sensitive) UiSupport.critical else UiSupport.high
                    status && item.status == "REMOVED" -> UiSupport.info
                    status -> UiSupport.allowed
                    muted -> UIUtil.getContextHelpForeground()
                    item.status == "NEW" -> UIUtil.getLabelForeground()
                    else -> UIUtil.getLabelForeground()
                }
            }
            if (status || item.status == "NEW" && !muted) font = JBFont.label().asBold()
            return this
        }
    }
}
