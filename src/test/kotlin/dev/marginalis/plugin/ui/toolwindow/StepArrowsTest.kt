package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import dev.marginalis.plugin.testing.DisplayOnly
import dev.marginalis.plugin.testing.FilesThatWontStayOpen
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

        assertEquals("✉ (2/18)  OrderService.java L20  Step 2", window.selected())
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
        assertEquals("✉ (2/18)  OrderService.java L20  Step 2", window.selected())
        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())
    }

    private fun commentsBesideTheWalkthrough(): List<String> {
        interleaved()
        sample()
        sample("src/deeper/Other.kt")
        return listOf(
            agent.addOnLine(3, "body" to "first comment"),
            agent.addOnLine(9, "body" to "second comment"),
            agent.addOnLine(5, "body" to "deeper comment", file = "src/deeper/Other.kt"),
        )
    }

    @DisplayOnly
    fun testTheArrowsStayInsideTheSectionOfTheSelectedThread() {
        commentsBesideTheWalkthrough()
        window.click("first comment", under = "Open")

        window.press("Next Step")
        assertEquals("✉ L9  second comment", window.selected())
        assertEquals("Sample.kt", openFile())

        window.press("First Step")
        assertEquals(listOf("Open  3", "src  3", "deeper  1", "Other.kt ✉1", "✉ L5  deeper comment"), window.selectedPath())

        window.press("Last Step")
        assertEquals(listOf("Open  3", "src  3", "Sample.kt ✉2", "✉ L9  second comment"), window.selectedPath())
    }

    @DisplayOnly
    fun testFromAFileRowNextStepContinuesTheWalkthroughLastWalked() {
        commentsBesideTheWalkthrough()
        repeat(3) { window.press("Next Step") }
        window.click("Sample.kt", under = "Open")

        assertEquals(arrows, enabledArrows())
        window.press("Next Step")

        assertEquals("✉ (5/18)  LoyaltyDiscountPolicy.java L22  Step 5", window.selected())
        assertEquals("LoyaltyDiscountPolicy.java", openFile())
    }

    @DisplayOnly
    fun testTheArrowsOnAResolvedCommentWalkTheResolvedCommentsPassingTheResolvedStepsBy() {
        commentsBesideTheWalkthrough().take(2).forEach { agent.post("comment_resolve", "thread_id" to it) }
        window.click("Resolved")
        window.press("Expand Section")
        window.click("second comment", under = "Resolved")

        window.press("First Step")
        assertTrue(window.selected()!!.endsWith("first comment"))
        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())

        window.press("Next Step")
        assertTrue(window.selected()!!.endsWith("second comment"))
        assertEquals(listOf("First Step", "Previous Step"), enabledArrows())
    }

    fun testFromTheFoldedResolvedSectionNextStepStartsTheFirstWalkthrough() {
        agent.post("comment_resolve", "thread_id" to commentsBesideTheWalkthrough().first())
        window.click("Resolved")
        assertFalse(window.isExpanded("Resolved"))

        window.press("Next Step")

        assertEquals("✉ (2/18)  OrderService.java L20  Step 2", window.selected())
        assertFalse(window.isExpanded("Resolved"))
        assertEquals("OrderService.java", openFile())
        assertEquals(20, caretLine())
    }

    @DisplayOnly
    fun testOnAResolvedStepTheArrowsWalkTheOpenStepsOfItsWalkthroughFromItsNumber() {
        interleaved()
        window.click("(1/18)")

        assertEquals(listOf("First Step", "Next Step", "Last Step"), enabledArrows())
        window.press("Next Step")

        assertEquals("✉ (2/18)  OrderService.java L20  Step 2", window.selected())
    }

    fun testAReplyMidWalkKeepsTheWalksPlace() {
        val steps = interleaved()
        window.press("Next Step")
        window.press("Next Step")
        window.press("Next Step")
        assertEquals("✉ (4/18)  LoyaltyDiscountPolicy.java L8  Step 4", window.selected())

        window.rebuiltBy { agent.reply(steps.getValue(9), "One more thing on step 9.") }
        assertEquals("✉ (4/18)  LoyaltyDiscountPolicy.java L8  Step 4", window.selected())
        window.press("Next Step")

        assertEquals("✉ (5/18)  LoyaltyDiscountPolicy.java L22  Step 5", window.selected())
        assertEquals("LoyaltyDiscountPolicy.java", openFile())
    }

    fun testResolvingTheSelectedStepSendsNextOnToTheStepAfterIt() {
        interleaved()
        repeat(4) { window.press("Next Step") }
        assertEquals("✉ (5/18)  LoyaltyDiscountPolicy.java L22  Step 5", window.selected())

        window.chooseFromContextMenu("(5/18)", "Resolve")
        window.press("Next Step")

        assertEquals("✉ (6/18)  OrderService.java L41  Step 6", window.selected())
    }

    fun testAReplyThatFiltersTheSelectedStepOutSendsNextOnToTheStepAfterIt() {
        val steps = interleaved()
        window.filterBy("Awaiting You")
        repeat(4) { window.press("Next Step") }
        assertEquals("✉ (5/18)  LoyaltyDiscountPolicy.java L22  Step 5", window.selected())

        window.rebuiltBy { user.reply(steps.getValue(5), "Looks right.") }
        window.press("Next Step")

        assertEquals("✉ (6/18)  OrderService.java L41  Step 6", window.selected())
    }

    fun testUnderAFilterTheArrowsStillVisitEveryOpenStepAndLeaveAHiddenOneUnselected() {
        val steps = interleaved()
        user.reply(steps.getValue(3), "Looks right.")
        window.filterBy("Awaiting You")
        window.click("(2/18)")

        assertEquals(listOf("Next Step", "Last Step"), enabledArrows())
        window.press("Next Step")

        assertNull(window.selected())
        assertEquals("OrderService.java", openFile())
        assertEquals(28, caretLine())
        assertEquals("Step 3", shownThread())
        assertEquals(arrows, enabledArrows())

        window.press("Next Step")
        assertEquals("✉ (4/18)  LoyaltyDiscountPolicy.java L8  Step 4", window.selected())

        window.press("Previous Step")
        assertNull(window.selected())
        assertEquals(28, caretLine())
    }

    fun testAFailedMoveLeavesTheArrowsOnTheStepYouAreOn() {
        interleaved()
        repeat(2) { window.press("Next Step") }
        FilesThatWontStayOpen(project, testRootDisposable).closeJustAfterOpening(loyaltyDiscountPolicy)

        window.press("Next Step")

        assertEquals("✉ (3/18)  OrderService.java L28  Step 3", window.selected())
        window.selectNothing()
        window.press("Previous Step")
        assertEquals("✉ (2/18)  OrderService.java L20  Step 2", window.selected())
    }

    fun testWithNoStepSelectedTheArrowsContinueFromAStepReachedByItsHeaderArrows() {
        interleaved()
        window.press("Next Step")
        user.press(user.panel(), "Next Step")
        window.selectNothing()

        window.press("Next Step")

        assertEquals("✉ (4/18)  LoyaltyDiscountPolicy.java L8  Step 4", window.selected())
    }

    fun testWithNoStepSelectedTheArrowsContinueFromAStepOpenedInTheGutter() {
        interleaved()
        user.clickGutter(orderService, 55)

        window.press("Previous Step")

        assertEquals("✉ (8/18)  Order.java L10  Step 8", window.selected())
        assertEquals("Order.java", openFile())
    }

    private fun tourAroundAProjectStep(): List<String> {
        sample()
        val items = listOf(
            mapOf("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "order" to 1, "walkthrough" to "tour", "body" to "Step one"),
            mapOf("order" to 2, "walkthrough" to "tour", "body" to "Step two is about the project"),
            mapOf("file" to SAMPLE, "line" to 9, "anchor_text" to lineText(9), "order" to 3, "walkthrough" to "tour", "body" to "Step three"),
        )
        return agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results").map { it.asJsonObject["thread_id"].asString }
    }

    fun testWithNoStepSelectedTheArrowsContinueFromAProjectStepReachedByItsHeaderArrows() {
        val steps = tourAroundAProjectStep()
        user.openMarginTab()
        user.press(user.open(steps[0]), "Next Step")
        window.selectNothing()

        window.press("Next Step")

        assertTrue(window.selected().toString(), window.selected()!!.endsWith("(3/3)  Sample.kt L9  Step three"))
    }

    fun testWithNoStepSelectedTheArrowsContinueFromAProjectStepOpenedInTheMarginTab() {
        val steps = tourAroundAProjectStep()
        val tab = user.openMarginTab()
        user.open(steps[0])
        tab.reopen("Step two")
        window.selectNothing()

        window.press("Next Step")

        assertTrue(window.selected().toString(), window.selected()!!.endsWith("(3/3)  Sample.kt L9  Step three"))
    }

    @DisplayOnly
    fun testOnAStepOfAFinishedWalkthroughNoArrowLeadsIntoAnotherWalkthrough() {
        interleaved()
        sample()
        val items = listOf(3, 9).mapIndexed { index, line ->
            mapOf("file" to SAMPLE, "line" to line, "anchor_text" to lineText(line), "order" to index + 1, "walkthrough" to "done", "body" to "Done ${index + 1}")
        }
        agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results")
            .forEach { agent.post("comment_resolve", "thread_id" to it.asJsonObject["thread_id"].asString) }
        listOf("Resolved", "done").forEach { window.toggleFolding(it, under = "Resolved") }
        window.click("Done 1", under = "Resolved")

        assertEquals(emptyList<String>(), enabledArrows())
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

    private val loyaltyDiscountPolicy = "acme/backend/shop-common/src/main/java/com/acme/backend/common/discount/LoyaltyDiscountPolicy.java"

    private val orderService = "acme/backend/shop-orders/src/main/java/com/acme/orders/OrderService.java"

    private val history get() = IdeDocumentHistory.getInstance(project)

    private fun pressAsANavigation(arrow: String) {
        CommandProcessor.getInstance().executeCommand(project, {
            history.includeCurrentCommandAsNavigation()
            window.press(arrow)
        }, arrow, null)
    }

    private fun place(): String = "${openFile()}:${caretLine()}"
}
