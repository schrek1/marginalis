package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.core.People
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.Turn
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.MarginalisPalette
import dev.marginalis.plugin.ui.MarkdownRenderer
import dev.marginalis.plugin.ui.ThreadPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Timer

internal class ThreadCard(
    val thread: CommentThread,
    private val onToggle: (ThreadCard) -> Unit,
    private val buildPanel: (ThreadCard) -> ThreadPanel,
) : JPanel(BorderLayout()) {

    private data class Look(
        val expanded: Boolean,
        val dimmed: Boolean,
        val updatedAt: Instant,
        val status: ThreadStatus,
        val turn: Turn?,
        val unread: Int,
        val messages: Int,
        val lastSpeaker: String?,
    )

    private val markLabel = JBLabel()
    private val dot = JBLabel("●").apply {
        foreground = MarginalisPalette.ACCENT
        border = JBUI.Borders.emptyLeft(6)
    }
    private val title = JBLabel()
    private val resolvedJustNow = JBLabel("resolved just now").apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyLeft(8)
    }
    private val preview = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyTop(2)
    }
    private val heading = JPanel(BorderLayout()).apply {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(6, 8)
        val leading = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(markLabel)
            add(Box.createHorizontalStrut(JBUI.scale(6)))
        }
        val trailing = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(resolvedJustNow)
            add(dot)
        }
        add(leading, BorderLayout.WEST)
        add(title, BorderLayout.CENTER)
        add(trailing, BorderLayout.EAST)
        add(preview, BorderLayout.SOUTH)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onToggle(this@ThreadCard)
        })
    }
    private val flash = Timer(FLASH_MILLIS) { outline(JBColor.border()) }.apply { isRepeats = false }
    private var look: Look? = null

    var panel: ThreadPanel? = null
        private set

    init {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(heading, BorderLayout.NORTH)
        outline(JBColor.border())
    }

    fun bind(expanded: Boolean, dimmed: Boolean, people: People) {
        val next = Look(
            expanded = expanded,
            dimmed = dimmed,
            updatedAt = thread.updatedAt,
            status = thread.status,
            turn = thread.turn(),
            unread = thread.unreadByUserCount(),
            messages = thread.messages.size,
            lastSpeaker = thread.messages.lastOrNull()?.speaker(people),
        )
        if (next == look) return
        look = next
        val mark = MarginalisIcons.withLeadingTurnSignal(MarginalisIcons.markOf(listOf(thread)), next.turn)
        markLabel.icon = if (dimmed) IconLoader.getTransparentIcon(mark, DIMMED_ALPHA) else mark
        title.text = MarkdownRenderer.previewText(thread.messages.firstOrNull()?.body.orEmpty())
        title.foreground = if (dimmed) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
        resolvedJustNow.isVisible = dimmed
        dot.isVisible = next.unread > 0
        val last = thread.messages.lastOrNull()?.takeIf { next.messages > 1 }
        preview.text = last?.let { "${next.lastSpeaker}: ${MarkdownRenderer.previewText(it.body)}" }.orEmpty()
        preview.isVisible = !expanded && last != null
        showPanel(expanded)
        revalidate()
        repaint()
    }

    fun flash() {
        outline(MarginalisPalette.ACCENT)
        flash.restart()
    }

    fun stopFlashing() = flash.stop()

    private fun showPanel(expanded: Boolean) {
        val shown = panel
        if (expanded && shown == null) {
            panel = buildPanel(this).also { add(it, BorderLayout.CENTER) }
        } else if (!expanded && shown != null) {
            remove(shown)
            panel = null
        }
    }

    private fun outline(color: Color) {
        border = JBUI.Borders.compound(JBUI.Borders.emptyBottom(8), JBUI.Borders.customLine(color, 1))
        repaint()
    }

    private companion object {
        private const val FLASH_MILLIS = 1000
        private const val DIMMED_ALPHA = 0.4f
    }
}
