package dev.marginalis.core

import dev.marginalis.core.ScrollAnchor.Anchor
import dev.marginalis.core.ScrollAnchor.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrollAnchorTest {

    private val cards = listOf(
        Box("a", top = 0, bottom = 300),
        Box("b", top = 300, bottom = 500),
        Box("c", top = 500, bottom = 900),
        Box("d", top = 900, bottom = 1200),
    )

    @Test
    fun `the card being written in is anchored wherever it sits in view`() {
        val anchor = ScrollAnchor.pick(cards, viewTop = 350, viewHeight = 400, preferred = listOf(null, "c"))

        assertEquals(Anchor("c", offsetFromViewTop = 150), anchor)
    }

    @Test
    fun `the card being written in is anchored even at the very top`() {
        val anchor = ScrollAnchor.pick(cards, viewTop = 0, viewHeight = 400, preferred = listOf("b"))

        assertEquals(Anchor("b", offsetFromViewTop = 300), anchor)
    }

    @Test
    fun `an explicitly named card wins over the one being written in`() {
        val anchor = ScrollAnchor.pick(cards, viewTop = 350, viewHeight = 400, preferred = listOf("b", "c"))

        assertEquals(Anchor("b", offsetFromViewTop = -50), anchor)
    }

    @Test
    fun `a card being written in but scrolled out of view yields to the topmost visible card`() {
        val anchor = ScrollAnchor.pick(cards, viewTop = 950, viewHeight = 200, preferred = listOf("b"))

        assertEquals(Anchor("d", offsetFromViewTop = -50), anchor)
    }

    @Test
    fun `reading without a draft anchors the card straddling the view top`() {
        val anchor = ScrollAnchor.pick(cards, viewTop = 350, viewHeight = 400, preferred = emptyList())

        assertEquals(Anchor("b", offsetFromViewTop = -50), anchor)
    }

    @Test
    fun `a member thread straddling the view top is anchored instead of its conversation`() {
        val withConversation = listOf(
            Box("conversation:pr-41", top = 0, bottom = 800),
            Box("t1", top = 40, bottom = 400),
            Box("t2", top = 400, bottom = 780),
        )

        val anchor = ScrollAnchor.pick(withConversation, viewTop = 450, viewHeight = 300, preferred = emptyList())

        assertEquals(Anchor("t2", offsetFromViewTop = -50), anchor)
    }

    @Test
    fun `when the view top falls in a gap the first card below it is anchored`() {
        val gapped = listOf(Box("a", top = 0, bottom = 100), Box("b", top = 120, bottom = 300))

        val anchor = ScrollAnchor.pick(gapped, viewTop = 110, viewHeight = 200, preferred = emptyList())

        assertEquals(Anchor("b", offsetFromViewTop = 10), anchor)
    }

    @Test
    fun `at the very top nothing is anchored so threads arriving above stay in view`() {
        assertNull(ScrollAnchor.pick(cards, viewTop = 0, viewHeight = 400, preferred = listOf(null)))
    }

    @Test
    fun `a card above that grew moves the view by the same amount`() {
        val anchor = Anchor("c", offsetFromViewTop = 150)

        val viewTop = ScrollAnchor.viewTopKeeping(anchor, anchorTop = 620, contentHeight = 1320, viewHeight = 400)

        assertEquals(470, viewTop)
    }

    @Test
    fun `the restored view never runs past either end of the content`() {
        val nearTop = Anchor("a", offsetFromViewTop = 100)
        val nearBottom = Anchor("d", offsetFromViewTop = -500)

        assertEquals(0, ScrollAnchor.viewTopKeeping(nearTop, anchorTop = 20, contentHeight = 1200, viewHeight = 400))
        assertEquals(800, ScrollAnchor.viewTopKeeping(nearBottom, anchorTop = 900, contentHeight = 1200, viewHeight = 400))
    }
}
