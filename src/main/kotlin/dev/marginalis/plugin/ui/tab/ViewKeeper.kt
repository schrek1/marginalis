package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBScrollPane
import dev.marginalis.core.ViewHold
import dev.marginalis.core.ViewHold.Held
import dev.marginalis.core.ViewHold.Span
import dev.marginalis.core.ViewHold.WritingIn
import dev.marginalis.plugin.ui.ThreadPanel
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal data class StackedCard(val key: String, val component: JComponent, val panel: ThreadPanel?)

internal class ViewKeeper(
    private val tab: JComponent,
    private val stack: JComponent,
    private val stackedCards: () -> List<StackedCard>,
    private val holdsDraft: (String) -> Boolean,
) : Disposable {

    private sealed interface Target {
        val card: String
    }

    private data class WholeCard(override val card: String) : Target

    private data class ReplyBox(override val card: String) : Target

    private val focus = WritingFocus()
    private var clicked: WholeCard? = null
    private var scrollTopInTab = 0

    val scroll: JBScrollPane = object : JBScrollPane(stack) {
        // Held around the scroll pane's layout, not the stack's: the viewport's layout moves the view to fit the new content before the stack lays out.
        override fun validateTree() {
            val held = heldAsShown()
            super.validateTree()
            val topInTab = SwingUtilities.convertPoint(this, 0, 0, tab).y
            held?.let { keepInPlace(it, viewMovedDownBy = topInTab - scrollTopInTab) }
            scrollTopInTab = topInTab
        }

        override fun addNotify() {
            super.addNotify()
            focus.tabShownAgain()
        }
    }

    fun keepingClickedInPlace(card: String, change: () -> Unit) {
        clicked = WholeCard(card)
        try {
            change()
            scroll.validate()
        } finally {
            clicked = null
        }
    }

    override fun dispose() = focus.stopFollowing()

    private fun heldAsShown(): Held<Target>? {
        val view = scroll.viewport.viewRect
        if (view.height <= 0 || scroll.verticalScrollBar.valueIsAdjusting) return null
        val shown = shownCards()
        return ViewHold.pick(shown.map { span(WholeCard(it.key), it.component) }, view.y, view.height, clicked, focus.writingIn(shown))
    }

    private fun keepInPlace(held: Held<Target>, viewMovedDownBy: Int) {
        val component = locate(held.key) ?: return
        val viewport = scroll.viewport
        val onScreen = held.copy(offsetFromViewTop = held.offsetFromViewTop - viewMovedDownBy)
        val viewTop = ViewHold.viewTopKeeping(onScreen, topInStack(component), stack.height, viewport.extentSize.height)
        if (viewTop != viewport.viewPosition.y) viewport.viewPosition = Point(viewport.viewPosition.x, viewTop)
    }

    private fun locate(target: Target): JComponent? {
        val card = shownCards().firstOrNull { it.key == target.card } ?: return null
        return when (target) {
            is WholeCard -> card.component
            is ReplyBox -> replyBoxOf(card)
        }
    }

    private fun replyBoxOf(card: StackedCard): JComponent? = card.panel?.composer?.takeIf(::isShown)

    private fun shownCards(): List<StackedCard> = stackedCards().filter { isShown(it.component) }

    private fun isShown(component: JComponent): Boolean = component.height > 0 && SwingUtilities.isDescendingFrom(component, stack)

    private fun span(target: Target, component: JComponent): Span<Target> {
        val top = topInStack(component)
        return Span(target, top, top + component.height)
    }

    private fun topInStack(component: JComponent): Int = SwingUtilities.convertPoint(component, 0, 0, stack).y

    private inner class WritingFocus {
        private var lastFocusedCard: String? = null
        private var shownAgainBeforeFocusMoved = false
        private val onFocusMoved = PropertyChangeListener { event ->
            val from = (event.oldValue as? Component)?.takeIf(::inTab)
            when {
                from != null -> remember(from)
                shownAgainBeforeFocusMoved -> forget()
            }
            shownAgainBeforeFocusMoved = false
        }

        init {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(PERMANENT_FOCUS_OWNER, onFocusMoved)
        }

        fun writingIn(shown: List<StackedCard>): WritingIn<Target>? {
            val focused = SwingUtilities.getWindowAncestor(tab)?.mostRecentFocusOwner?.takeIf(::inTab)
            val card = (if (focused != null) cardOf(focused, shown) else shown.firstOrNull { it.key == lastFocusedCard }) ?: return null
            if (!isWrittenIn(card, focused)) return null
            return WritingIn(WholeCard(card.key), replyBoxOf(card)?.let { span(ReplyBox(card.key), it) })
        }

        fun tabShownAgain() {
            shownAgainBeforeFocusMoved = true
        }

        fun stopFollowing() =
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(PERMANENT_FOCUS_OWNER, onFocusMoved)

        private fun remember(from: Component) {
            lastFocusedCard = cardOf(from, shownCards())?.key
        }

        private fun forget() {
            lastFocusedCard = null
        }

        private fun cardOf(focused: Component, shown: List<StackedCard>): StackedCard? {
            val byComponent = shown.associateBy { it.component }
            return generateSequence(focused) { it.parent }.firstNotNullOfOrNull { byComponent[it] }
        }

        private fun isWrittenIn(card: StackedCard, focused: Component?): Boolean {
            val inReplyBox = focused != null && replyBoxOf(card)?.let { SwingUtilities.isDescendingFrom(focused, it) } == true
            return holdsDraft(card.key) || card.panel?.isEditing == true || inReplyBox
        }

        private fun inTab(component: Component): Boolean = SwingUtilities.isDescendingFrom(component, tab)
    }

    private companion object {
        const val PERMANENT_FOCUS_OWNER = "permanentFocusOwner"
    }
}
