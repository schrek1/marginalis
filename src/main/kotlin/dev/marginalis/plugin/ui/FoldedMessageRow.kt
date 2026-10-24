package dev.marginalis.plugin.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel

internal class FoldedMessageRow(
    who: JComponent,
    val openingLine: String,
    time: String,
    private val onOpen: () -> Unit,
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        add(who, BorderLayout.WEST)
        add(OpeningLineLabel(openingLine), BorderLayout.CENTER)
        add(
            JBLabel(time).apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyLeft(8)
            },
            BorderLayout.EAST,
        )
        opensOnClick(this)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun opensOnClick(component: Component) {
        if (component is ActionLink) return
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onOpen()
        })
        if (component is Container) component.components.forEach(::opensOnClick)
    }

    private class OpeningLineLabel(openingLine: String) : JBLabel() {
        init {
            putClientProperty("html.disable", true)
            text = openingLine
        }

        override fun getPreferredSize(): Dimension = Dimension(0, super.getPreferredSize().height)

        override fun getMinimumSize(): Dimension = preferredSize
    }
}
