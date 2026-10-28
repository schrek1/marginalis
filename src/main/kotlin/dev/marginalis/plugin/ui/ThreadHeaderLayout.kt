package dev.marginalis.plugin.ui

import com.intellij.ui.components.JBLabel
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JComponent

/**
 * Lays out a thread header in one row: the buttons keep their width while the row has room and the status takes what
 * is left. In a row narrower than both, the status shortens to its minimum first, then the buttons shrink to theirs.
 */
internal class ThreadHeaderLayout(private val status: JComponent, private val buttons: JComponent) : LayoutManager {

    override fun addLayoutComponent(name: String?, component: Component?) = Unit

    override fun removeLayoutComponent(component: Component?) = Unit

    override fun preferredLayoutSize(parent: Container): Dimension = rowSize(parent, status.preferredSize, buttons.preferredSize)

    override fun minimumLayoutSize(parent: Container): Dimension = rowSize(parent, status.minimumSize, buttons.minimumSize)

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = parent.width - insets.left - insets.right
        val height = parent.height - insets.top - insets.bottom
        val buttonsWidth = buttons.preferredSize.width
            .coerceAtMost(width - status.minimumSize.width)
            .coerceAtLeast(buttons.minimumSize.width)
        buttons.setBounds(insets.left + width - buttonsWidth, insets.top, buttonsWidth, height)
        status.setBounds(insets.left, insets.top, (width - buttonsWidth).coerceAtLeast(0), height)
    }

    private fun rowSize(parent: Container, status: Dimension, buttons: Dimension): Dimension {
        val insets = parent.insets
        return Dimension(
            status.width + buttons.width + insets.left + insets.right,
            maxOf(status.height, buttons.height) + insets.top + insets.bottom,
        )
    }
}

/** A thread's status line, which in a narrow header shortens to its first word ("open…"), never to nothing. */
internal class ThreadStatusLabel : JBLabel() {

    override fun getMinimumSize(): Dimension {
        val firstWord = text.orEmpty().substringBefore(" · ")
        if (firstWord == text.orEmpty()) return preferredSize
        val insets = insets
        return Dimension(
            getFontMetrics(font).stringWidth("$firstWord...") + insets.left + insets.right,
            preferredSize.height,
        )
    }
}
