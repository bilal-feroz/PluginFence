package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
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
import com.pluginfence.model.Capability
import com.pluginfence.model.PluginInfo
import com.pluginfence.model.PolicyDecision
import com.pluginfence.policy.DefaultPolicies
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Per-plugin permission matrix: ALLOW / ASK / BLOCK for each capability, applied immediately. */
class PermissionsPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true) {

    private val listModel = DefaultListModel<PluginInfo>()
    private val list = JBList(listModel)
    private val detail = JPanel(BorderLayout()).apply { isOpaque = false }
    private var selectedId: String? = null
    private var updating = false
    private var renderedSignature: String? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = PluginRenderer()
        list.emptyText.text = "No third-party plugins found."
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !updating) {
                selectedId = list.selectedValue?.pluginId
                renderDetail(list.selectedValue)
            }
        }
        val listBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(UiSupport.subheading("Third-party plugins").apply { border = JBUI.Borders.empty(8, 12, 4, 12) }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.32f).apply {
            firstComponent = listBox
            secondComponent = JBScrollPane(detail).apply { border = JBUI.Borders.empty() }
        }
        setContent(splitter)
        renderDetail(null)
    }

    fun refresh() {
        val plugins = engine.governedPlugins()
        updating = true
        try {
            val current = (0 until listModel.size()).map { listModel.get(it) }
            if (current != plugins) {
                listModel.clear()
                plugins.forEach { listModel.addElement(it) }
            }
            val index = plugins.indexOfFirst { it.pluginId == selectedId }
            when {
                index >= 0 -> list.selectedIndex = index
                plugins.isNotEmpty() -> { list.selectedIndex = 0; selectedId = plugins[0].pluginId }
            }
        } finally {
            updating = false
        }
        renderDetail(list.selectedValue)
    }

    private fun renderDetail(plugin: PluginInfo?) {
        // Only rebuild when something relevant changed: events arrive constantly during a demo and a
        // rebuild would close an open combo box under the user's cursor.
        val signature = plugin?.let { p -> "${p.pluginId}|${p.version}|${p.trusted}|${engine.policies.policy(p.pluginId)}" } ?: "none"
        if (signature == renderedSignature) return
        renderedSignature = signature
        detail.removeAll()
        if (plugin == null) {
            detail.add(UiSupport.emptyState("Select a plugin to view and change its permissions.", AllIcons.Nodes.Plugin), BorderLayout.CENTER)
        } else {
            detail.add(buildMatrix(plugin), BorderLayout.NORTH)
        }
        detail.revalidate()
        detail.repaint()
    }

    private fun buildMatrix(plugin: PluginInfo): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
        }
        panel.add(left(UiSupport.heading(plugin.name)))
        panel.add(left(JBLabel(buildString {
            append(plugin.pluginId)
            if (plugin.version.isNotBlank()) append("   -   v").append(plugin.version)
            if (plugin.vendor.isNotBlank()) append("   -   ").append(plugin.vendor)
        }).apply { foreground = UIUtil.getContextHelpForeground() }))
        if (plugin.trusted) {
            panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            panel.add(left(JBLabel(
                "<html><b>Trusted platform plugin.</b> Bundled/JetBrains plugins are monitored but not enforced against; " +
                    "set explicit permissions below to enforce anyway.</html>",
            ).apply { foreground = UIUtil.getContextHelpForeground() }))
        }
        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))

        val grid = JPanel(GridBagLayout()).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val gbc = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = Insets(JBUI.scale(3), 0, JBUI.scale(3), JBUI.scale(16)) }
        header(grid, gbc, "Capability", 0); header(grid, gbc, "Policy", 1); header(grid, gbc, "Default", 2)
        Capability.values().forEachIndexed { i, capability ->
            val row = i + 1
            gbc.gridy = row
            gbc.gridx = 0
            val name = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel(capability.displayName).apply { font = JBFont.label().asBold() })
                add(JBLabel(capability.description).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small() })
            }
            grid.add(name, gbc)

            gbc.gridx = 1
            val combo = ComboBox(PolicyDecision.values())
            combo.selectedItem = engine.policies.effective(plugin.pluginId, capability)
            combo.renderer = DecisionRenderer()
            combo.addActionListener {
                val chosen = combo.selectedItem as PolicyDecision
                val default = DefaultPolicies.of(capability)
                engine.setPolicy(plugin.pluginId, capability, if (chosen == default) null else chosen)
            }
            grid.add(combo, gbc)

            gbc.gridx = 2
            val overridden = engine.policies.isOverridden(plugin.pluginId, capability)
            grid.add(JBLabel(if (overridden) "custom (default ${DefaultPolicies.of(capability)})" else "default").apply {
                foreground = if (overridden) UiSupport.high else UIUtil.getContextHelpForeground()
                font = JBFont.small()
            }, gbc)
        }
        panel.add(grid)

        val approvals = engine.policies.policy(plugin.pluginId)?.approvedTargets?.flatMap { (c, t) -> t.map { c to it } } ?: emptyList()
        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(14)))
        panel.add(left(UiSupport.subheading("Always-allowed targets")))
        if (approvals.isEmpty()) {
            panel.add(left(UiSupport.hint("None. Choose \"Always Allow\" on a permission request to add one.")))
        } else {
            approvals.forEach { (capability, target) ->
                val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
                row.add(JBLabel("${capability.displayName}:").apply { foreground = UIUtil.getContextHelpForeground() })
                row.add(UiSupport.mono(target))
                row.add(JButton("Revoke").apply { addActionListener { engine.policies.revoke(plugin.pluginId, capability, target) } })
                panel.add(row)
            }
        }

        panel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(14)))
        val footer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        footer.add(JButton("Reset to Defaults").apply { addActionListener { engine.policies.reset(plugin.pluginId) } })
        footer.add(UiSupport.hint("  Changes apply immediately and persist across restarts.").apply { border = JBUI.Borders.emptyLeft(8) })
        panel.add(footer)
        return panel
    }

    private fun header(grid: JPanel, gbc: GridBagConstraints, text: String, x: Int) {
        gbc.gridx = x
        gbc.gridy = 0
        grid.add(JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground(); font = JBFont.small().asBold() }, gbc)
    }

    private fun left(c: Component): Component = (c as? javax.swing.JComponent)?.apply { alignmentX = Component.LEFT_ALIGNMENT } ?: c

    private inner class PluginRenderer : ColoredListCellRenderer<PluginInfo>() {
        override fun customizeCellRenderer(list: JList<out PluginInfo>, value: PluginInfo, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = AllIcons.Nodes.Plugin
            append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (value.version.isNotBlank()) append("  ${value.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.trusted) append("  trusted", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            val custom = engine.policies.policy(value.pluginId)?.let { it.overrides.isNotEmpty() || it.approvedTargets.values.any { s -> s.isNotEmpty() } } == true
            if (custom) append("  custom policy", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, UiSupport.high))
            border = JBUI.Borders.empty(3, 6)
        }
    }

    private class DecisionRenderer : ColoredListCellRenderer<PolicyDecision>() {
        override fun customizeCellRenderer(list: JList<out PolicyDecision>, value: PolicyDecision?, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value == null) return
            val color = when (value) {
                PolicyDecision.ALLOW -> UiSupport.allowed
                PolicyDecision.ASK -> UiSupport.ask
                PolicyDecision.BLOCK -> UiSupport.blocked
            }
            append(value.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
        }
    }
}
