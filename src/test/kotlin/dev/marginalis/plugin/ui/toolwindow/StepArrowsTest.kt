package dev.marginalis.plugin.ui.toolwindow

import dev.marginalis.plugin.testing.DisplayOnly
import dev.marginalis.plugin.testing.NeedsADisplay
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
class StepArrowsTest : ToolWindowTestCase() {

    private val arrows = listOf("First Step", "Previous Step", "Next Step", "Last Step")

    private fun enabledArrows(): List<String> = arrows.filter { window.isEnabled(it) }

    private fun shownThread(): String = user.shownText(user.panel()).lines().first { it.startsWith("Step ") }

    fun testWithNothingInTheMarginNoArrowIsEnabled() {
        assertEquals(emptyList<String>(), enabledArrows())
    }

    fun testTheArrowsNameTheirStepsAndStartFromTheTopOfTheFirstSection() {
        interleaved()

        assertEquals("Next Step", window.presentation("Next Step").text)
        assertEquals("Previous Step", window.presentation("Previous Step").text)
        assertNull(window.selected())
        assertEquals(listOf("First Step", "Next Step", "Last Step"), enabledArrows())

        window.press("Next Step")

        assertEquals("✉ (10/18)  L30  Step 10", window.selected())
        assertEquals("OrderController.java", openFile())
        assertEquals(30, caretLine())
        assertEquals("Step 10", shownThread())
        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())

        window.press("Next Step")
        assertEquals(arrows, enabledArrows())
    }

    // FixMe: Known issue - #45 in a Guided section the arrows walk the folder tree, not the step numbers
    fun testNextStepFollowsTheTreeRatherThanTheStepNumbers() {
        interleaved()
        val visited = mutableListOf<String>()

        repeat(5) {
            window.press("Next Step")
            visited += window.selected()!!.substringBefore("  ")
        }

        assertEquals(listOf("✉ (10/18)", "✉ (18/18)", "✉ (2/18)", "✉ (3/18)", "✉ (6/18)"), visited)
        assertEquals("OrderService.java", openFile())
        assertEquals(41, caretLine())
    }

    fun testLastAndFirstJumpToTheEndsOfTheSectionAndDisableThemselvesThere() {
        interleaved()

        window.press("Last Step")
        assertEquals("✉ (8/18)  L10  Step 8", window.selected())
        assertEquals("Order.java", openFile())
        assertEquals(listOf("First Step", "Previous Step"), enabledArrows())

        window.press("Previous Step")
        assertEquals("✉ (14/18)  L40  Step 14", window.selected())
        assertEquals("LoyaltyDiscountPolicy.java", openFile())
        assertEquals(40, caretLine())

        window.press("First Step")
        assertEquals("✉ (10/18)  L30  Step 10", window.selected())
        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())
    }

    @DisplayOnly
    fun testTheArrowsStayInsideTheSectionOfTheSelectedThread() {
        interleaved()
        window.click("Step 18", under = "Open")

        window.press("Next Step")
        assertEquals("✉ L15  Step 7", window.selected())
        assertEquals("OrderRepository.java", openFile())

        window.press("Last Step")
        assertEquals("✉ L58  Step 17", window.selected())
        assertEquals(listOf("Open  17", "acme/backend  17", "shop-orders/src  13", "test/java/com/acme/orders  5", "OrderServiceTest.java ✉3", "✉ L58  Step 17"), window.selectedPath())

        window.press("First Step")
        assertEquals("✉ L8  Step 4", window.selected())
        assertEquals(listOf("Open  17", "acme/backend  17", "shop-common/src/main/java/com/acme/backend/common  4", "discount  3", "LoyaltyDiscountPolicy.java ✉3", "✉ L8  Step 4"), window.selectedPath())
    }

    @DisplayOnly
    fun testFromAFolderNextStepEntersTheFirstThreadOfItsSection() {
        interleaved()
        window.click("resources/db/changelog", under = "Open")

        assertEquals(listOf("First Step", "Next Step", "Last Step"), enabledArrows())
        window.press("Next Step")

        assertEquals("✉ L8  Step 4", window.selected())
    }

    @DisplayOnly
    fun testStepsInTheFoldedResolvedSectionAreReachedAndUnfolded() {
        interleaved()
        window.click("Resolved")
        assertFalse(window.isExpanded("Resolved"))

        window.press("Next Step")

        assertEquals("L12  Step 1", window.selected())
        assertTrue(window.isExpanded("Resolved"))
        assertEquals("OrderController.java", openFile())
        assertEquals(12, caretLine())
        assertEquals(emptyList<String>(), enabledArrows())
    }

    // FixMe: Known issue - #45 an agent's reply mid-walk drops the selection, so Next Step starts over at the top of the first section
    fun testAReplyMidWalkSendsTheNextStepBackToTheTop() {
        val steps = interleaved()
        window.press("Next Step")
        window.press("Next Step")
        window.press("Next Step")
        assertEquals("✉ (2/18)  L20  Step 2", window.selected())

        window.rebuiltBy { agent.reply(steps.getValue(9), "One more thing on step 9.") }
        assertNull(window.selected())
        window.press("Next Step")

        assertEquals("✉ (10/18)  L30  Step 10", window.selected())
        assertEquals("OrderController.java", openFile())
    }

    fun testADeepWalkthroughIsWalkedAcrossItsModules() {
        deep()

        window.press("First Step")
        val first = window.selected()
        window.press("Last Step")
        val last = window.selected()

        assertEquals("✉ (16/33)  L5  Step 16", first)
        assertEquals("✉ (20/33)  L5  Step 20", last)
        assertEquals("CustomerProductFixture.java", openFile())
    }
}
