package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
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
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * The per-plugin permission matrix.
 *
 * Each capability is a card with a three-way switch rather than a row of combo boxes: the whole
 * policy for a plugin is then readable in one pass, and changing it is a single click instead of
 * open-scroll-pick. Changes apply immediately and persist - there is no Apply button to forget.
 */
class PermissionsPanel(private val engine: FenceEngine) : SimpleToolWindowPanel(true, true), FencePanel {

    private val listModel = DefaultListModel<PluginInfo>()
    private val list = JBList(listModel)
    private val detail = JPanel(BorderLayout()).apply { isOpaque = false }
    private var selectedId: String? = null
    private var updating = false
    private var renderedSignature: String? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = PluginRow(engine)
        list.fixedCellHeight = JBUI.scale(46)
        list.border = JBUI.Borders.empty()
        list.emptyText.text = "No third-party plugins seen yet"
        list.emptyText.appendLine("Plugins appear once the agent has watched their code load.")
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !updating) {
                selectedId = list.selectedValue?.pluginId
                renderDetail(list.selectedValue)
            }
        }

        val listBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                UiSupport.sectionLabel("Third-party plugins").apply {
                    border = JBUI.Borders.empty(UiSupport.GAP, UiSupport.PAD - 2, UiSupport.TIGHT, UiSupport.GAP)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(list).apply {
                    border = JBUI.Borders.customLineTop(UiSupport.hairline)
                    viewport.background = UIUtil.getListBackground()
                },
                BorderLayout.CENTER,
            )
        }

        setContent(
            OnePixelSplitter(false, 0.32f).apply {
                firstComponent = listBox
                secondComponent = JBScrollPane(detail).apply {
                    border = JBUI.Borders.empty()
                    verticalScrollBar.unitIncrement = JBUI.scale(16)
                }
            },
        )
        renderDetail(null)
    }

    override fun component(): JComponent = this

    override fun refresh() {
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
                plugins.isNotEmpty() -> {
                    list.selectedIndex = 0
                    selectedId = plugins[0].pluginId
                }
            }
        } finally {
            updating = false
        }
        renderDetail(list.selectedValue)
    }

    /**
     * Rebuilding is deliberately conditional. Events arrive constantly during an attack, and an
     * unconditional rebuild would yank the switch the user is in the middle of clicking.
     */
    private fun renderDetail(plugin: PluginInfo?) {
        val signature = plugin?.let { p -> "${p.pluginId}|${p.version}|${p.trusted}|${engine.policies.policy(p.pluginId)}" } ?: "none"
        if (signature == renderedSignature) return
        renderedSignature = signature
        detail.removeAll()
        if (plugin == null) {
            detail.add(
                UiSupport.emptyState(
                    "No plugin selected",
                    "Pick a plugin to review and change what it is allowed to reach.",
                    AllIcons.Nodes.Plugin,
                ),
                BorderLayout.CENTER,
            )
        } else {
            detail.add(buildDetail(plugin), BorderLayout.NORTH)
        }
        detail.revalidate()
        detail.repaint()
    }

    private fun buildDetail(plugin: PluginInfo): JComponent {
        val panel = UiSupport.column(0).apply { border = JBUI.Borders.empty(UiSupport.PAD) }

        panel.add(UiSupport.heading(plugin.name))
        panel.add(UiSupport.spacer(2))
        panel.add(
            UiSupport.caption(
                buildString {
                    append(plugin.pluginId)
                    if (plugin.version.isNotBlank()) append("   v").append(plugin.version)
                    if (plugin.vendor.isNotBlank()) append("   ").append(plugin.vendor)
                },
            ),
        )

        if (plugin.trusted) {
            panel.add(UiSupport.spacer(UiSupport.GAP))
            val note = FenceCard().apply {
                tint = UiSupport.low
                padding(8, 12, 8, 12)
            }
            note.add(
                UiSupport.column(
                    2,
                    UiSupport.row(UiSupport.GAP, Pill("TRUSTED", UiSupport.low), UiSupport.subheading("Bundled or JetBrains plugin")),
                    WrappedText("Trusted plugins are monitored but not enforced against by default. Set an explicit policy below to enforce anyway.").muted(),
                ),
                BorderLayout.CENTER,
            )
            panel.add(note)
        }

        panel.add(UiSupport.spacer(UiSupport.PAD))
        panel.add(UiSupport.sectionLabel("Capabilities"))
        panel.add(UiSupport.spacer(UiSupport.TIGHT + 2))
        Capability.values().forEach { capability ->
            panel.add(capabilityCard(plugin, capability))
            panel.add(UiSupport.spacer(6))
        }

        panel.add(UiSupport.spacer(UiSupport.GAP))
        panel.add(UiSupport.sectionLabel("Always-allowed targets"))
        panel.add(UiSupport.spacer(UiSupport.TIGHT + 2))
        val approvals = engine.policies.policy(plugin.pluginId)?.approvedTargets
            ?.flatMap { (capability, targets) -> targets.map { capability to it } }
            ?: emptyList()
        if (approvals.isEmpty()) {
            panel.add(UiSupport.hint("None yet. \"Always Allow\" on a prevented operation adds one here."))
        } else {
            approvals.forEach { (capability, target) ->
                panel.add(approvalRow(plugin, capability, target))
                panel.add(UiSupport.spacer(4))
            }
        }

        panel.add(UiSupport.spacer(UiSupport.PAD))
        panel.add(
            UiSupport.row(
                UiSupport.GAP,
                JButton("Reset to Defaults", AllIcons.Actions.Refresh).apply {
                    addActionListener { engine.policies.reset(plugin.pluginId) }
                },
                UiSupport.hint("Changes apply immediately and survive a restart."),
            ),
        )
        return panel
    }

    /** One capability: what it covers, the switch, and whether it still matches the shipped default. */
    private fun capabilityCard(plugin: PluginInfo, capability: Capability): JComponent {
        val default = DefaultPolicies.of(capability)
        val effective = engine.policies.effective(plugin.pluginId, capability)
        val overridden = engine.policies.isOverridden(plugin.pluginId, capability)

        val switch = SegmentedControl(listOf(PolicyDecision.ALLOW, PolicyDecision.ASK, PolicyDecision.BLOCK), { it.name }, ::decisionColor).apply {
            selected = effective
            onSelect = { chosen -> engine.setPolicy(plugin.pluginId, capability, if (chosen == default) null else chosen) }
        }

        val card = FenceCard().apply {
            accent = decisionColor(effective)
            padding(9, 12, 9, 12)
        }
        card.add(
            UiSupport.column(
                2,
                UiSupport.subheading(capability.displayName),
                UiSupport.hint(capability.description),
            ),
            BorderLayout.CENTER,
        )
        card.add(
            UiSupport.row(
                UiSupport.GAP,
                JBLabel(if (overridden) "custom" else "default").apply {
                    font = JBFont.small()
                    foreground = if (overridden) UiSupport.accent else UIUtil.getContextHelpForeground()
                    toolTipText = if (overridden) "Shipped default is $default" else "Matches the shipped default"
                },
                switch,
            ),
            BorderLayout.EAST,
        )
        return card
    }

    private fun approvalRow(plugin: PluginInfo, capability: Capability, target: String): JComponent =
        UiSupport.row(
            UiSupport.GAP,
            Pill(capability.displayName, UiSupport.allowed),
            UiSupport.mono(target),
            JButton("Revoke").apply {
                addActionListener { engine.policies.revoke(plugin.pluginId, capability, target) }
            },
        )

    private fun decisionColor(decision: PolicyDecision): Color = when (decision) {
        PolicyDecision.ALLOW -> UiSupport.allowed
        PolicyDecision.ASK -> UiSupport.ask
        PolicyDecision.BLOCK -> UiSupport.blocked
    }

    /** Two lines: the plugin, and whether you have already said something about it. */
    private class PluginRow(private val engine: FenceEngine) : JBPanel<PluginRow>(BorderLayout(JBUI.scale(UiSupport.GAP), 0)), ListCellRenderer<PluginInfo> {

        private val name = JBLabel().apply { font = JBFont.label().asBold() }
        private val subtitle = JBLabel().apply { font = JBFont.small() }
        private val pill = Pill("", UiSupport.accent)
        private var stripe: Color? = null

        init {
            isOpaque = true
            border = JBUI.Borders.empty(6, 14, 6, 10)
            add(JBLabel(AllIcons.Nodes.Plugin).apply { border = JBUI.Borders.emptyTop(2) }, BorderLayout.WEST)
            add(UiSupport.column(1, name, subtitle), BorderLayout.CENTER)
            add(JPanel(GridBagLayout()).apply { isOpaque = false; add(pill, GridBagConstraints()) }, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out PluginInfo>,
            value: PluginInfo,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            background = if (selected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
            val foreground = if (selected) UIUtil.getListSelectionForeground(true) else UIUtil.getListForeground()

            name.text = value.name
            name.foreground = foreground
            subtitle.text = buildString {
                if (value.version.isNotBlank()) append('v').append(value.version).append("   ")
                append(if (value.trusted) "trusted platform plugin" else "third-party")
            }
            subtitle.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()

            val policy = engine.policies.policy(value.pluginId)
            val customised = policy != null && (policy.overrides.isNotEmpty() || policy.approvedTargets.values.any { it.isNotEmpty() })
            pill.isVisible = customised
            pill.text = "CUSTOM"
            pill.color = if (selected) foreground else UiSupport.accent
            stripe = if (customised) UiSupport.accent else null
            return this
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            stripe?.let {
                g.color = it
                g.fillRect(0, 0, JBUI.scale(3), height)
            }
        }
    }
}
