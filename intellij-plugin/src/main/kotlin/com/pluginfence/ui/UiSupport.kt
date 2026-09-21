package com.pluginfence.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.pluginfence.model.FenceOperation
import com.pluginfence.model.FenceRisk
import com.pluginfence.model.FenceVerdict
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The PluginFence design language.
 *
 * Every colour, font and surface is derived from the active IntelliJ theme, so the tool window
 * looks native in light and dark, at any IDE zoom level, and under custom themes. Panels are
 * assembled exclusively from the small component set below ([FenceCard], [Pill], [RiskMeter],
 * [SegmentedControl], ...) so spacing, radii and weight stay consistent across the four tabs.
 */
object UiSupport {

    // --- spacing scale -----------------------------------------------------------------------
    // One scale, used everywhere: PAD frames a panel, GAP separates siblings, TIGHT pairs a label
    // with its value.
    const val PAD = 16
    const val GAP = 10
    const val TIGHT = 4

    fun scale(n: Int): Int = JBUI.scale(n)

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    // --- semantic palette --------------------------------------------------------------------

    val critical: Color = JBColor(Color(0xD03040), Color(0xF26D74))
    val high: Color = JBColor(Color(0xD4711A), Color(0xF2A05A))
    val medium: Color = JBColor(Color(0xB08900), Color(0xE6C85A))
    val low: Color = JBColor(Color(0x3F8FD1), Color(0x6FB4F0))
    val info: Color = JBColor(Color(0x76797F), Color(0x9DA0A8))
    val allowed: Color = JBColor(Color(0x27855C), Color(0x62C08A))
    val blocked: Color = critical
    val ask: Color = high
    val accent: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
    val muted: Color get() = UIUtil.getContextHelpForeground()

    /** Card face: one step away from the panel background so cards read as raised, never as boxes. */
    val surface: Color
        get() {
            val base = UIUtil.getPanelBackground()
            return if (ColorUtil.isDark(base)) ColorUtil.brighter(base, 1) else JBColor.WHITE
        }

    val surfaceHover: Color
        get() {
            val base = surface
            return if (ColorUtil.isDark(base)) ColorUtil.brighter(base, 1) else ColorUtil.darker(base, 1)
        }

    /** Hairline that separates surfaces without drawing attention to itself. */
    val hairline: Color
        get() = ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.14)

    /** A wash of [color] suitable as a card background; stays readable in both themes. */
    fun tint(color: Color, alpha: Int = 26): Color = ColorUtil.toAlpha(color, alpha)

    // --- formatting --------------------------------------------------------------------------

    fun time(ts: Long): String = timeFormat.format(Instant.ofEpochMilli(ts))

    fun dateTime(ts: Long): String = dateTimeFormat.format(Instant.ofEpochMilli(ts))

    /** "just now" / "4m ago" / "3h ago", falling back to a date once it stops being recent. */
    fun ago(ts: Long): String {
        val seconds = (System.currentTimeMillis() - ts) / 1000
        return when {
            seconds < 0 -> time(ts)
            seconds < 10 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 604_800 -> "${seconds / 86_400}d ago"
            else -> dateTime(ts).substringBefore(' ')
        }
    }

    fun shorten(text: String, max: Int = 80): String =
        if (text.length <= max) text else text.take(max / 2 - 2) + "..." + text.takeLast(max / 2 - 1)

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // --- semantic lookups --------------------------------------------------------------------

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

    /** The one-word form used on pills and in tables: BLOCKED / ASK / ALLOWED / MONITORED. */
    fun verdictShort(verdict: FenceVerdict): String = verdict.label.substringBefore(" ")

    fun operationIcon(op: FenceOperation): Icon = when (op) {
        FenceOperation.FILE_READ, FenceOperation.FILE_WRITE -> AllIcons.FileTypes.Any_type
        FenceOperation.ENV_READ, FenceOperation.ENV_ENUMERATE -> AllIcons.Nodes.Variable
        FenceOperation.PROCESS_EXEC -> AllIcons.Actions.Execute
        FenceOperation.NETWORK_CONNECT -> AllIcons.Nodes.PpWeb
    }

    // --- typography --------------------------------------------------------------------------

    fun hero(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 6f)
    }

    /** The big number on a stat tile - larger than a heading, and never used for prose. */
    fun metric(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 11f)
    }

    fun heading(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().deriveFont(Font.BOLD, JBFont.label().size2D + 3f)
    }

    fun subheading(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.label().asBold()
        it.foreground = UIUtil.getLabelForeground()
    }

    /** Small, upper-case, muted: the label above a group of things. */
    fun sectionLabel(text: String): JBLabel = JBLabel(text.uppercase()).also {
        it.font = JBFont.small().asBold()
        it.foreground = UIUtil.getContextHelpForeground()
    }

    fun hint(text: String): JBLabel = JBLabel(text).also {
        it.foreground = UIUtil.getContextHelpForeground()
        it.font = JBFont.small()
    }

    fun caption(text: String): JBLabel = JBLabel(text).also {
        it.foreground = UIUtil.getContextHelpForeground()
    }

    fun mono(text: String): JBLabel = JBLabel(text).also {
        it.font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size))
    }

    // --- layout helpers ----------------------------------------------------------------------

    /** A transparent row with consistent gaps; children keep their preferred size. */
    fun row(gap: Int, vararg components: Component): JPanel =
        Stack(FlowLayout(FlowLayout.LEFT, scale(gap), 0)).apply {
            components.forEach { add(it) }
        }

    /** A transparent vertical stack; every child is left-aligned and separated by [gap]. */
    fun column(gap: Int, vararg components: Component): JPanel =
        Stack().apply {
            components.forEachIndexed { i, c ->
                (c as? JComponent)?.alignmentX = Component.LEFT_ALIGNMENT
                if (i > 0) addSpaced(scale(gap), c) else add(c)
            }
        }

    /** A fixed vertical gap that behaves inside a BoxLayout column. */
    fun spacer(height: Int): JComponent = object : JComponent() {
        init {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        override fun getPreferredSize(): Dimension = Dimension(1, scale(height))

        override fun getMinimumSize(): Dimension = Dimension(1, scale(height))

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, scale(height))
    }

    /** A friendly empty state: icon, headline and an optional next step. */
    fun emptyState(headline: String, detail: String? = null, icon: Icon = AllIcons.General.InspectionsOK): JComponent {
        val stack = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel(icon).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(scale(8)))
            add(JBLabel(headline, SwingConstants.CENTER).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                font = JBFont.label().asBold()
                foreground = UIUtil.getLabelForeground()
            })
            if (detail != null) {
                add(Box.createVerticalStrut(scale(TIGHT)))
                add(JBLabel(detail, SwingConstants.CENTER).apply {
                    alignmentX = Component.CENTER_ALIGNMENT
                    foreground = UIUtil.getContextHelpForeground()
                    font = JBFont.small()
                })
            }
        }
        return JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = false
            add(stack, GridBagConstraints())
        }
    }

}

// =================================================================================================
// Components
// =================================================================================================

/**
 * The single surface primitive: a rounded card that can be tinted by severity, carry a left accent
 * stripe, react to hover and act as a button. Painted rather than bordered so the corners stay
 * crisp at every IDE scale.
 */
open class FenceCard(private val arc: Int = 12) : JBPanel<FenceCard>(BorderLayout()) {

    /** Vertical stripe down the left edge - used to carry a verdict or risk colour. */
    var accent: Color? = null

    /** When set, the card is filled with a wash of this colour instead of the neutral surface. */
    var tint: Color? = null

    /** Overrides the hairline outline. */
    var outline: Color? = null

    /** Draws no fill at all - for cards that sit directly on a list background. */
    var flat: Boolean = false

    private var hovered = false
    private var clickable = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(12, 14)
    }

    fun padding(top: Int, left: Int, bottom: Int, right: Int): FenceCard = apply {
        border = JBUI.Borders.empty(top, left, bottom, right)
    }

    fun onClick(action: () -> Unit): FenceCard = apply {
        clickable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) = action()
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            val r = JBUI.scale(arc)
            val lift = clickable && hovered

            if (!flat) {
                val fill = tint?.let { UiSupport.tint(it, if (lift) 46 else 30) }
                    ?: if (lift) UiSupport.surfaceHover else UiSupport.surface
                g2.color = fill
                g2.fillRoundRect(0, 0, w - 1, h - 1, r, r)
            }

            accent?.let { stripe ->
                val saved = g2.clip
                g2.clip(RoundRectangle2D.Float(0f, 0f, (w - 1).toFloat(), (h - 1).toFloat(), r.toFloat(), r.toFloat()))
                g2.color = stripe
                g2.fillRect(0, 0, JBUI.scale(3), h)
                g2.clip = saved
            }

            g2.color = outline ?: tint?.let { UiSupport.tint(it, if (lift) 130 else 95) } ?: UiSupport.hairline
            g2.drawRoundRect(0, 0, w - 1, h - 1, r, r)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    /** Cards are never stretched vertically by a column; they grow only to fit their content. */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** A transparent container that fills the width it is given but never more height than it needs. */
class Stack(layout: LayoutManager? = null) : JPanel() {

    /** Child -> the strut that precedes it, so a hidden child takes its spacing with it. */
    private val spacers = LinkedHashMap<Component, Component>()

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        if (layout == null) setLayout(BoxLayout(this, BoxLayout.Y_AXIS)) else setLayout(layout)
    }

    /** Adds [c] preceded by a [gap]-high strut that is hidden whenever [c] is. */
    fun addSpaced(gap: Int, c: Component) {
        val strut = Box.createVerticalStrut(gap)
        spacers[c] = strut
        add(strut)
        add(c)
    }

    override fun doLayout() {
        // Cards such as the agent-setup prompt and the drift alarm are shown conditionally; without
        // this their spacing would stay behind as a stray gap.
        spacers.forEach { (child, strut) -> if (strut.isVisible != child.isVisible) strut.isVisible = child.isVisible }
        super.doLayout()
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * A compact rounded label used for risk levels, verdicts and status. Self-sizing, so it behaves in
 * flow layouts where a bordered JLabel would collapse or stretch.
 */
class Pill(text: String, color: Color, private val solid: Boolean = false) : JComponent() {

    var text: String = text
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    var color: Color = color
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        font = JBFont.small().asBold()
        alignmentX = Component.LEFT_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        return Dimension(fm.stringWidth(text) + JBUI.scale(16), fm.height + JBUI.scale(6))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val h = height
            g2.color = if (solid) color else UiSupport.tint(color, 38)
            g2.fillRoundRect(0, 0, width - 1, h - 1, h, h)
            if (!solid) {
                g2.color = UiSupport.tint(color, 110)
                g2.drawRoundRect(0, 0, width - 1, h - 1, h, h)
            }
            g2.font = font
            val fm = g2.fontMetrics
            g2.color = if (solid) contrastOn(color) else color
            g2.drawString(text, (width - fm.stringWidth(text)) / 2, (h - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }

    private fun contrastOn(background: Color): Color = if (ColorUtil.isDark(background)) JBColor.WHITE else JBColor.BLACK
}

/**
 * A 0-100 risk score drawn as a rounded track with a coloured fill. A bar is read faster than a
 * number, so wherever there is room PluginFence shows both.
 */
class RiskMeter(private val trackWidth: Int = 72, private val thickness: Int = 6) : JComponent() {

    private var score = 0
    private var color: Color = UiSupport.info

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    fun set(score: Int, color: Color) {
        this.score = score.coerceIn(0, 100)
        this.color = color
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(trackWidth), JBUI.scale(thickness))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(thickness)
            val y = (height - h) / 2
            g2.color = UiSupport.tint(UIUtil.getLabelForeground(), 30)
            g2.fillRoundRect(0, y, width - 1, h, h, h)
            val filled = ((width - 1) * score / 100f).toInt().coerceAtLeast(if (score > 0) JBUI.scale(4) else 0)
            if (filled > 0) {
                g2.color = color
                g2.fillRoundRect(0, y, filled, h, h, h)
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * A three-way ALLOW / ASK / BLOCK switch. A column of combo boxes turns a permission matrix into a
 * form; a segmented switch keeps the whole policy readable at a glance and one click to change.
 */
class SegmentedControl<T : Any>(
    private val options: List<T>,
    private val label: (T) -> String,
    private val colorOf: (T) -> Color,
) : JComponent() {

    var selected: T = options.first()
        set(value) {
            field = value
            repaint()
        }

    var onSelect: ((T) -> Unit)? = null

    private var hoverIndex = -1

    init {
        isOpaque = false
        font = JBFont.small().asBold()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        alignmentX = Component.LEFT_ALIGNMENT
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val i = indexAt(e.x)
                if (i != hoverIndex) {
                    hoverIndex = i
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                hoverIndex = -1
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                val i = indexAt(e.x)
                if (i >= 0 && options[i] != selected) {
                    selected = options[i]
                    onSelect?.invoke(options[i])
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    private fun segmentWidth(): Int = (width - 1) / options.size.coerceAtLeast(1)

    private fun indexAt(x: Int): Int {
        val w = segmentWidth()
        if (w <= 0) return -1
        return (x / w).coerceIn(0, options.size - 1)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val widest = options.maxOf { fm.stringWidth(label(it)) }
        return Dimension((widest + JBUI.scale(20)) * options.size + 1, fm.height + JBUI.scale(10))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val h = height - 1
            val arc = JBUI.scale(8)
            val w = segmentWidth()

            g2.color = UiSupport.tint(UIUtil.getLabelForeground(), 18)
            g2.fillRoundRect(0, 0, w * options.size, h, arc, arc)

            val index = options.indexOf(selected).coerceAtLeast(0)
            val active = colorOf(selected)
            g2.color = UiSupport.tint(active, 60)
            g2.fillRoundRect(index * w, 0, w, h, arc, arc)
            g2.color = UiSupport.tint(active, 170)
            g2.drawRoundRect(index * w, 0, w, h, arc, arc)

            g2.font = font
            val fm = g2.fontMetrics
            options.forEachIndexed { i, option ->
                val text = label(option)
                g2.color = when {
                    i == index -> colorOf(option)
                    i == hoverIndex -> UIUtil.getLabelForeground()
                    else -> UIUtil.getContextHelpForeground()
                }
                g2.drawString(text, i * w + (w - fm.stringWidth(text)) / 2, (h - fm.height) / 2 + fm.ascent)
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * One step of an attack chain: a numbered node on a vertical rail, with the operation, its target
 * and the verdict beside it. The rail is painted per-step so the chain stays connected however the
 * surrounding scroll pane lays it out.
 */
class ChainStep(
    private val index: Int,
    private val first: Boolean,
    private val last: Boolean,
    private val color: Color,
    body: JComponent,
) : JBPanel<ChainStep>(BorderLayout()) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyLeft(GUTTER + 8)
        add(body, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val cx = JBUI.scale(GUTTER) / 2
            val cy = JBUI.scale(NODE_CENTER_Y)
            val radius = JBUI.scale(9)

            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.color = UiSupport.tint(UIUtil.getLabelForeground(), 60)
            if (!first) g2.drawLine(cx, 0, cx, cy - radius)
            if (!last) g2.drawLine(cx, cy + radius, cx, height)

            g2.color = UiSupport.tint(color, 45)
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2)
            g2.color = color
            g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2)

            g2.font = JBFont.small().asBold()
            val text = index.toString()
            val fm = g2.fontMetrics
            g2.drawString(text, cx - fm.stringWidth(text) / 2, cy - fm.height / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        const val GUTTER = 26
        private const val NODE_CENTER_Y = 20
    }
}

/**
 * Terminal node of an attack chain: the outcome. Same rail, filled node, so the eye reads the chain
 * and its result as one object.
 */
class ChainOutcome(private val color: Color, body: JComponent) : JBPanel<ChainOutcome>(BorderLayout()) {

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyLeft(ChainStep.GUTTER + 8)
        add(body, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val cx = JBUI.scale(ChainStep.GUTTER) / 2
            val cy = JBUI.scale(22)
            val radius = JBUI.scale(6)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            g2.color = UiSupport.tint(UIUtil.getLabelForeground(), 60)
            g2.drawLine(cx, 0, cx, cy - radius)
            g2.color = color
            g2.fillOval(cx - radius, cy - radius, radius * 2, radius * 2)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * Plain text that wraps to whatever width it is given.
 *
 * Swing has no such component: an HTML `JLabel` only wraps at a width baked into the markup, which
 * is why the previous revision hard-coded `width:420px` and clipped on narrow tool windows. This
 * measures and re-flows on resize, so summaries and reasons stay readable at any layout.
 */
class WrappedText(text: String = "") : JComponent() {

    var text: String = text
        set(value) {
            field = value
            invalidateLayoutCache()
        }

    private var cachedWidth = -1
    private var cachedLines: List<String> = emptyList()

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        font = JBFont.label()
        foreground = UIUtil.getLabelForeground()
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (width != cachedWidth) invalidateLayoutCache()
            }
        })
    }

    fun muted(): WrappedText = apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.small()
    }

    private fun invalidateLayoutCache() {
        cachedWidth = -1
        revalidate()
        repaint()
    }

    private fun lines(width: Int): List<String> {
        if (width == cachedWidth) return cachedLines
        val fm = getFontMetrics(font)
        val result = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && fm.stringWidth(candidate) > width) {
                    result.add(line.toString())
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(candidate)
                }
            }
            result.add(line.toString())
        }
        cachedWidth = width
        cachedLines = result
        return result
    }

    override fun getPreferredSize(): Dimension {
        val w = if (width > 0) width else JBUI.scale(420)
        return Dimension(w, lines(w).size * getFontMetrics(font).height)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            g2.color = foreground
            val fm = g2.fontMetrics
            var y = fm.ascent
            for (line in lines(if (width > 0) width else JBUI.scale(420))) {
                g2.drawString(line, 0, y)
                y += fm.height
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * A ring gauge for a proportion. Used for the share of prevented operations on the overview, where
 * a bare count says nothing about how much of the plugin's activity it represents.
 */
class Donut(private val diameter: Int = 46) : JComponent() {

    private var fraction = 0f
    private var color: Color = UiSupport.allowed
    private var centerText = ""

    init {
        isOpaque = false
    }

    fun set(fraction: Float, color: Color, centerText: String) {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.color = color
        this.centerText = centerText
        repaint()
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(diameter), JBUI.scale(diameter))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val stroke = JBUI.scale(5)
            val d = minOf(width, height) - stroke - JBUI.scale(1)
            val x = (width - d) / 2
            val y = (height - d) / 2
            g2.stroke = BasicStroke(stroke.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.color = UiSupport.tint(UIUtil.getLabelForeground(), 30)
            g2.drawOval(x, y, d, d)
            if (fraction > 0f) {
                g2.color = color
                g2.drawArc(x, y, d, d, 90, -(360 * fraction).toInt())
            }
            g2.font = JBFont.small().asBold()
            val fm = g2.fontMetrics
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(centerText, (width - fm.stringWidth(centerText)) / 2, (height - fm.height) / 2 + fm.ascent)
        } finally {
            g2.dispose()
        }
    }
}
