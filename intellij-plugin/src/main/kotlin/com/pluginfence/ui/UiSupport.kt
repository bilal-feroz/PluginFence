package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/** Shared look: platform colours, risk badges, time formatting. No hard-coded ugly Swing defaults. */
object UiSupport {

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    val critical: Color = JBColor(Color(0xDB3B4B), Color(0xF26D74))
    val high: Color = JBColor(Color(0xE0731A), Color(0xF2A05A))
    val medium: Color = JBColor(Color(0xC7A200), Color(0xE6C85A))
    val low: Color = JBColor(Color(0x3F8FD1), Color(0x6FB4F0))
    val info: Color = JBColor(Color(0x7A7E85), Color(0x9DA0A8))
    val allowed: Color = JBColor(Color(0x2E8B46), Color(0x6BBF7B))
    val blocked: Color = critical
    val ask: Color = high
    val muted: Color get() = UIUtil.getContextHelpForeground()

    fun time(ts: Long): String = timeFormat.format(Instant.ofEpochMilli(ts))
    fun dateTime(ts: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ts))

    fun riskColor(risk: FenceRisk): Color = when (risk) {
        FenceRisk.CRITICAL -> critical
        FenceRisk.HIGH -> high
        FenceRisk.MEDIUM -> medium
        FenceRisk.LOW -> low
        FenceRisk.INFO -> info
    }

    fun verdictColor(verdict: FenceVerdict): Color = when (verdict) {
        FenceVerdict.BLOCK -> blocked
        FenceVerdict.ASK -> ask
        FenceVerdict.ALLOW -> allowed
        FenceVerdict.MONITOR -> info
    }

    fun verdictIcon(verdict: FenceVerdict): Icon = when (verdict) {
        FenceVerdict.BLOCK -> AllIcons.General.Error
        FenceVerdict.ASK -> AllIcons.General.Warning
        FenceVerdict.ALLOW -> AllIcons.RunConfigurations.TestPassed
        FenceVerdict.MONITOR -> AllIcons.General.Information
    }

    fun operationIcon(op: FenceOperation): Icon = when (op) {
        FenceOperation.FILE_READ, FenceOperation.FILE_WRITE -> AllIcons.FileTypes.Any_type
        FenceOperation.ENV_READ, FenceOperation.ENV_ENUMERATE -> AllIcons.Nodes.Variable
        FenceOperation.PROCESS_EXEC -> AllIcons.Actions.Execute
        FenceOperation.NETWORK_CONNECT -> AllIcons.Nodes.PpWeb
    }

    fun riskAttributes(risk: FenceRisk): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, riskColor(risk))

    fun verdictAttributes(verdict: FenceVerdict): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, verdictColor(verdict))

    /** Small coloured pill, e.g. "CRITICAL" or "BLOCKED". */
    fun badge(text: String, color: Color): JComponent {
        val label = JBLabel(text.uppercase())
        label.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D - 1f)
        label.foreground = color
        label.border = JBUI.Borders.empty(1, 6)
        label.isOpaque = false
        return RoundedBorderPanel(color).also { it.add(label, BorderLayout.CENTER) }
    }

    fun heading(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 3f)
    }

    fun subheading(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().asBold()
        it.foreground = UIUtil.getLabelForeground()
    }

    fun hint(text: String): JBLabel = JBLabel(text).also {
        it.foreground = UIUtil.getContextHelpForeground()
        it.font = JBFont.small()
    }

    fun mono(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))
    }

    /** A vertical stack panel with consistent spacing. */
    fun stack(vararg components: Component, gap: Int = 6): JPanel {
        val panel = JBPanel<JBPanel<*>>(GridBagLayout())
        panel.isOpaque = false
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.insets = Insets(0, 0, gap, 0)
        components.forEachIndexed { i, c ->
            gbc.gridy = i
            panel.add(c, gbc)
        }
        gbc.gridy = components.size
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        panel.add(JPanel().also { it.isOpaque = false }, gbc)
        return panel
    }

    fun emptyState(text: String, icon: Icon = AllIcons.General.InspectionsOK): JComponent {
        val label = JBLabel(text, icon, SwingConstants.CENTER)
        label.foreground = UIUtil.getContextHelpForeground()
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    fun shorten(text: String, max: Int = 80): String =
        if (text.length <= max) text else text.take(max / 2 - 2) + "..." + text.takeLast(max / 2 - 1)
}

/** Rounded outline used for badges; background stays transparent so it works in both themes. */
class RoundedBorderPanel(private val color: Color) : JBPanel<RoundedBorderPanel>(BorderLayout()) {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        try {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(color.red, color.green, color.blue, 28)
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = Color(color.red, color.green, color.blue, 140)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
