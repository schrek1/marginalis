package dev.marginalis.core

import dev.marginalis.core.ViewHold.Held
import dev.marginalis.core.ViewHold.Span
import dev.marginalis.core.ViewHold.WritingIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewHoldTest {

    private val cards = listOf(
        Span("a", top = 0, bottom = 300),
        Span("b", top = 300, bottom = 500),
        Span("c", top = 500, bottom = 900),
        Span("d", top = 900, bottom = 1200),
    )

    private val writingInC = WritingIn("c", replyBox = Span("c's reply box", top = 700, bottom = 760))

    @Test
    fun `the reply box being written in is held wherever it sits in view`() {
        val held = ViewHold.pick(cards, viewTop = 350, viewHeight = 400, writing = writingInC)

        assertEquals(Held("c's reply box", offsetFromViewTop = 350), held)
    }

    @Test
    fun `the card being written in is held even at the very top`() {
        val held = ViewHold.pick(cards, viewTop = 0, viewHeight = 400, writing = WritingIn("b"))

        assertEquals(Held("b", offsetFromViewTop = 300), held)
    }

    @Test
    fun `a card just clicked wins over the card being written in`() {
        val held = ViewHold.pick(cards, viewTop = 350, viewHeight = 400, clicked = "b", writing = writingInC)

        assertEquals(Held("b", offsetFromViewTop = -50), held)
    }

    @Test
    fun `a card just clicked is held even at the very top`() {
        val held = ViewHold.pick(cards, viewTop = 0, viewHeight = 400, clicked = "b")

        assertEquals(Held("b", offsetFromViewTop = 300), held)
    }

    @Test
    fun `a clicked card out of view yields to the card being written in`() {
        val held = ViewHold.pick(cards, viewTop = 350, viewHeight = 400, clicked = "d", writing = writingInC)

        assertEquals(Held("c's reply box", offsetFromViewTop = 350), held)
    }

    @Test
    fun `the card being written in is held while its reply box is scrolled out of view`() {
        val held = ViewHold.pick(cards, viewTop = 350, viewHeight = 300, writing = writingInC)

        assertEquals(Held("c", offsetFromViewTop = 150), held)
    }

    @Test
    fun `a card being written in but scrolled out of view yields to the topmost visible card`() {
        val held = ViewHold.pick(cards, viewTop = 950, viewHeight = 200, writing = WritingIn("b"))

        assertEquals(Held("d", offsetFromViewTop = -50), held)
    }

    @Test
    fun `reading without writing holds the card straddling the view top`() {
        val held = ViewHold.pick(cards, viewTop = 350, viewHeight = 400)

        assertEquals(Held("b", offsetFromViewTop = -50), held)
    }

    @Test
    fun `a member thread straddling the view top is held instead of its conversation`() {
        val withConversation = listOf(
            Span("conversation:pr-41", top = 0, bottom = 800),
            Span("t1", top = 40, bottom = 400),
            Span("t2", top = 400, bottom = 780),
        )

        val held = ViewHold.pick(withConversation, viewTop = 450, viewHeight = 300)

        assertEquals(Held("t2", offsetFromViewTop = -50), held)
    }

    @Test
    fun `when the view top falls in a gap the first card below it is held`() {
        val gapped = listOf(Span("a", top = 0, bottom = 100), Span("b", top = 120, bottom = 300))

        val held = ViewHold.pick(gapped, viewTop = 110, viewHeight = 200)

        assertEquals(Held("b", offsetFromViewTop = 10), held)
    }

    @Test
    fun `at the very top nothing is held so threads arriving above stay in view`() {
        assertNull(ViewHold.pick(cards, viewTop = 0, viewHeight = 400))
    }

    @Test
    fun `a card above that grew moves the view by the same amount`() {
        val held = Held("c's reply box", offsetFromViewTop = 150)

        val viewTop = ViewHold.viewTopKeeping(held, heldTop = 620, contentHeight = 1320, viewHeight = 400)

        assertEquals(470, viewTop)
    }

    @Test
    fun `the restored view never runs past either end of the content`() {
        val nearTop = Held("a", offsetFromViewTop = 100)
        val nearBottom = Held("d", offsetFromViewTop = -500)

        assertEquals(0, ViewHold.viewTopKeeping(nearTop, heldTop = 20, contentHeight = 1200, viewHeight = 400))
        assertEquals(800, ViewHold.viewTopKeeping(nearBottom, heldTop = 900, contentHeight = 1200, viewHeight = 400))
    }

    @Test
    fun `when the content fits in the view nothing can be held in place`() {
        val held = Held("b", offsetFromViewTop = 100)

        assertEquals(0, ViewHold.viewTopKeeping(held, heldTop = 250, contentHeight = 380, viewHeight = 400))
    }
}
