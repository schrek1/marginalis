package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
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

    fun testTheArrowsNameTheirStepsAndStartFromTheFirstStepOfTheFirstSection() {
        interleaved()

        assertEquals("Next Step", window.presentation("Next Step").text)
        assertEquals("Previous Step", window.presentation("Previous Step").text)
        assertNull(window.selected())
        assertEquals(listOf("First Step", "Next Step", "Last Step"), enabledArrows())

        window.press("Next Step")

        assertEquals("✉ (2/18)  L20  Step 2", window.selected())
        assertEquals("OrderService.java", openFile())
        assertEquals(20, caretLine())
        assertEquals("Step 2", shownThread())
        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())

        window.press("Next Step")
        assertEquals(arrows, enabledArrows())
    }

    fun testNextStepFollowsTheStepNumbersRatherThanTheTree() {
        interleaved()
        val visited = mutableListOf<String>()

        repeat(5) {
            window.press("Next Step")
            visited += window.selected()!!.substringBefore("  ")
        }

        assertEquals(listOf("✉ (2/18)", "✉ (3/18)", "✉ (4/18)", "✉ (5/18)", "✉ (6/18)"), visited)
        assertEquals("OrderService.java", openFile())
        assertEquals(41, caretLine())
    }

    fun testLastAndFirstJumpToTheEndsOfTheSectionAndDisableThemselvesThere() {
        interleaved()

        window.press("Last Step")
        assertEquals("✉ (18/18)  OrderController.java L45  Step 18", window.selected())
        assertEquals("OrderController.java", openFile())
        assertEquals(listOf("First Step", "Previous Step"), enabledArrows())

        window.press("Previous Step")
        assertEquals("✉ (17/18)  OrderServiceTest.java L58  Step 17", window.selected())
        assertEquals("OrderServiceTest.java", openFile())
        assertEquals(58, caretLine())

        window.press("First Step")
        assertEquals("✉ (2/18)  L20  Step 2", window.selected())
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

    fun testAReplyMidWalkKeepsTheWalksPlace() {
        val steps = interleaved()
        window.press("Next Step")
        window.press("Next Step")
        window.press("Next Step")
        assertEquals("✉ (4/18)  L8  Step 4", window.selected())

        window.rebuiltBy { agent.reply(steps.getValue(9), "One more thing on step 9.") }
        assertEquals("✉ (4/18)  L8  Step 4", window.selected())
        window.press("Next Step")

        assertEquals("✉ (5/18)  L22  Step 5", window.selected())
        assertEquals("LoyaltyDiscountPolicy.java", openFile())
    }

    // FixMe: Known issue - #64 resolving the selected step sends Next back to the first open step instead of the one after it
    fun testResolvingTheSelectedStepSendsNextBackToTheFirstOpenStep() {
        val steps = interleaved()
        repeat(4) { window.press("Next Step") }
        assertEquals("✉ (5/18)  L22  Step 5", window.selected())

        window.rebuiltBy { user.resolve(steps.getValue(5)) }
        window.press("Next Step")

        assertEquals("✉ (2/18)  L20  Step 2", window.selected())
    }

    // FixMe: Known issue - #64 a reply that filters the selected step out of view sends Next back to the first step left in view
    fun testAReplyThatFiltersTheSelectedStepOutSendsNextBackToTheFirstStepInView() {
        val steps = interleaved()
        window.filterBy("Awaiting You")
        repeat(4) { window.press("Next Step") }
        assertEquals("✉ (5/18)  L22  Step 5", window.selected())

        window.rebuiltBy { user.reply(steps.getValue(5), "Looks right.") }
        window.press("Next Step")

        assertEquals("✉ (2/18)  L20  Step 2", window.selected())
    }

    fun testBackAndForwardRetraceTheStepsWalkedAcrossAReply() {
        val steps = interleaved()
        repeat(3) { pressAsANavigation("Next Step") }
        window.rebuiltBy { agent.reply(steps.getValue(9), "One more thing on step 9.") }
        pressAsANavigation("Next Step")
        assertEquals("LoyaltyDiscountPolicy.java:22", place())

        val retraced = (1..3).map {
            history.back()
            window.settle()
            place()
        }
        history.forward()
        window.settle()

        assertEquals(listOf("LoyaltyDiscountPolicy.java:8", "OrderService.java:28", "OrderService.java:20"), retraced)
        assertEquals("OrderService.java:28", place())
    }

    fun testADeepWalkthroughIsWalkedAcrossItsModules() {
        deep()

        window.press("First Step")
        val first = window.selected()
        window.press("Last Step")
        val last = window.selected()

        assertEquals("✉ (8/33)  CustomerSettingsProvider.java L5  Step 8", first)
        assertEquals("✉ (33/33)  InvoiceSettingsConfigurationTest.java L5  Step 33", last)
        assertEquals("InvoiceSettingsConfigurationTest.java", openFile())
    }

    private val history get() = IdeDocumentHistory.getInstance(project)

    private fun pressAsANavigation(arrow: String) {
        CommandProcessor.getInstance().executeCommand(project, {
            history.includeCurrentCommandAsNavigation()
            window.press(arrow)
        }, arrow, null)
    }

    private fun place(): String = "${openFile()}:${caretLine()}"
}
