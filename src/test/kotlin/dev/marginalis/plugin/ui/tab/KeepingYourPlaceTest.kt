package dev.marginalis.plugin.ui.tab

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorTextField
import dev.marginalis.plugin.testing.Agent
import io.kotest.assertions.withClue
import io.kotest.matchers.types.shouldBeSameInstanceAs

class KeepingYourPlaceTest : TabInFrontTestCase() {

    // These scenarios are written for the order that puts new threads and fresh activity on top.
    override fun setUp() {
        super.setUp()
        tab.sortBy("Recent activity")
    }

    private fun paragraphs(count: Int): String = (1..count).joinToString("\n\n") { "Paragraph $it." }

    private fun Agent.longAsk(title: String, count: Int = 40): String = ask("$title\n\n${paragraphs(count)}")

    private fun topInTab(text: String): Int = tab.topInView(tab.card(text)) + tab.scrolledTo

    private fun readLongThread(title: String) {
        val id = agent.longAsk("$title question", count = 3)
        (2..7).forEach { agent.reply(id, "$title reply $it\n\n${paragraphs(3)}") }
        val thread = user.thread(id)
        thread.markReadByUser(thread.messages)
        store.threads.notifyChanged(thread)
        tab.reopen(title)
    }

    private fun assertStillWriting(box: EditorTextField, text: String, caret: Int) {
        assertEquals(text, box.text)
        assertEquals(caret, box.editor!!.caretModel.offset)
        assertSame(box.editor!!.contentComponent, user.lastFocusedInWindowOf(box))
    }

    fun testAReplyBoxStaysPutWhenAThreadAboveGrowsPastTheView() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 4)
        assertSame(box, user.composer(tab.panel("Bravo")))
    }

    fun testAReplyBoxStaysPutWhileYouAreInAnotherApp() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        val before = tab.topOnScreen(box)
        tab.switchToAnotherApp()

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        tab.switchToTheIde()
        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 4)
    }

    fun testAReplyBoxStaysPutWhenAThreadAboveGrowsWhileYouLookAtAFile() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)
        tab.openFileInFront(sample())

        agent.reply(alpha, paragraphs(80))
        tab.bringToFront()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
    }

    fun testAReplyBoxScrolledIntoViewStaysPutWhenAThreadAboveGrowsWhileYouLookAtAFile() {
        agent.longAsk("Zulu question")
        agent.ask("Bravo question")
        val alpha = agent.longAsk("Alpha question")
        tab.scrollTo(topInTab("Bravo") - 200)
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)
        tab.openFileInFront(sample())

        agent.reply(alpha, paragraphs(20))
        tab.bringToFront()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
    }

    fun testAReplyBoxStaysPutWhileYouWorkInAToolWindow() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        val before = tab.topOnScreen(box)
        user.focusOn(tab.toolWindow)

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
        assertEquals(4, box.editor!!.caretModel.offset)
    }

    fun testAReplyBoxScrolledIntoViewStaysPutWhileYouWorkInAToolWindow() {
        agent.longAsk("Zulu question")
        agent.ask("Bravo question")
        val alpha = agent.longAsk("Alpha question")
        tab.scrollTo(topInTab("Bravo") - 200)
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)
        user.focusOn(tab.toolWindow)

        agent.reply(alpha, paragraphs(20))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
    }

    fun testBackFromAFileWithFocusInAToolWindowAtTheTopTheAgentsAnswerArrivesInView() {
        agent.longAsk("Alpha question")
        agent.ask("Bravo question")
        val charlie = agent.ask("Charlie question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        tab.openFileInFront(sample())
        tab.bringToFront()
        user.focusOn(tab.toolWindow)

        agent.reply(charlie, paragraphs(40))
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Charlie")) in 0..100)
    }

    fun testAReplyBoxStaysPutWhenTheTabStartsPointingToThreadsInFiles() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)

        agent.addOnLine(5, "body" to "Line five", file = sample())

        assertEquals("Also your move: 1 thread in files ›", tab.alsoYourMove())
        assertEquals(before, tab.topOnScreen(box))
    }

    fun testAMessageYouAreEditingStaysPutWhenAThreadAboveGrowsPastTheView() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        user.submit(tab.panel("Bravo"))
        user.clickLink(tab.panel("Bravo"), "Edit")
        val box = user.composer(tab.panel("Bravo"))
        user.type(tab.panel("Bravo"), "Call it Elm.")
        user.focusOn(box.editor!!.contentComponent)
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Elm.", box.text)
    }

    private fun scrollBoxToMidView(box: EditorTextField) = tab.scrollTo(tab.topInView(box) + tab.scrolledTo - 300)

    fun testAReplyBoxStaysPutWhenTheAgentAnswersInTheThreadYouAreWritingIn() {
        agent.longAsk("Charlie question")
        val bravo = agent.longAsk("Bravo question", count = 30)
        agent.longAsk("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        scrollBoxToMidView(box)
        assertTrue(tab.topInView(tab.card("Bravo")) < 0)
        val before = tab.topOnScreen(box)

        agent.reply(bravo, paragraphs(30))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 4)
    }

    fun testAMessageYouAreEditingStaysPutWhenTheAgentAnswersInItsThread() {
        agent.longAsk("Charlie question")
        val bravo = agent.longAsk("Bravo question", count = 30)
        agent.longAsk("Alpha question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        user.submit(tab.panel("Bravo"))
        user.clickLink(tab.panel("Bravo"), "Edit")
        val box = user.composer(tab.panel("Bravo"))
        user.type(tab.panel("Bravo"), "Call it Elm.")
        user.focusOn(box.editor!!.contentComponent)
        scrollBoxToMidView(box)
        assertTrue(tab.topInView(tab.card("Bravo")) < 0)
        val before = tab.topOnScreen(box)

        agent.reply(bravo, paragraphs(30))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Elm.", box.text)
    }

    fun testTheThreadYouAreWritingInStaysPutWhileYouScrollUpToReReadIt() {
        agent.longAsk("Zulu question")
        agent.longAsk("Bravo question")
        val alpha = agent.longAsk("Alpha question", count = 20)
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        tab.scrollTo(topInTab("Bravo") - 100)
        assertTrue(tab.topInView(box) > tab.viewHeight)
        val before = tab.topOnScreen(tab.card("Bravo"))

        agent.reply(alpha, paragraphs(20))
        tab.settle()

        assertEquals(before, tab.topOnScreen(tab.card("Bravo")))
        assertStillWriting(box, "Call it Oak.", caret = 12)
    }

    fun testAReplyBoxStaysPutWhileYouSelectTextInItsThread() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        user.focusOn(user.messageBody(tab.panel("Bravo"), "Bravo question"))
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
    }

    fun testWhenEverythingFitsAReplyBoxMovesDownByWhatArrivedAbove() {
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(5))
        tab.settle()

        assertFalse(tab.scrolls)
        assertTrue("the reply box stayed at $before", tab.topOnScreen(box) > before)
        assertStillWriting(box, "Call it Oak.", caret = 4)
    }

    fun testAReplyBoxStaysPutWhenAThreadBelowJumpsAboveIt() {
        val charlie = agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        agent.longAsk("Alpha question")
        tab.scrollTo(topInTab("Bravo") - 200)
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)

        agent.reply(charlie, "Back on top")
        tab.settle()

        assertEquals(listOf("Charlie", "Bravo", "Alpha"), shown().map { row -> listOf("Charlie", "Bravo", "Alpha").single { it in row } })
        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 12)
    }

    fun testAReplyBoxStaysPutWhileAThreadAboveReflowsToANarrowerTab() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        agent.ask("Alpha question\n\n" + (1..8).joinToString("\n\n") { "Paragraph $it: " + "word ".repeat(60) })
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)
        val wide = user.messageBody(tab.panel("Alpha"), "Paragraph 1:")

        tab.resize(700, 900)
        PlatformTestUtil.waitWithEventsDispatching(
            "Alpha never re-rendered for the narrower tab",
            { user.messageBody(tab.panel("Alpha"), "Paragraph 1:") !== wide },
            5,
        )
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 12)
    }

    fun testAReplyBoxInAConversationStaysPutWhenACommentAboveItGrows() {
        agent.longAsk("Charlie question")
        agent.relay(2, "Please add a changelog entry")
        val looks = agent.relay(1, "Looks good overall")
        tab.clickConversation("PR #7")
        tab.click("Looks good")
        tab.click("changelog")
        val box = user.writeIn(tab.panel("changelog"), "Added.")
        val before = tab.topOnScreen(box)

        agent.reply(looks, paragraphs(60))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Added.", caret = 6)
    }

    fun testAReplyBoxStaysPutWhenAThreadAboveIsResolved() {
        agent.ask("Bravo question")
        val alpha = agent.longAsk("Alpha question", count = 20)
        agent.longAsk("Zulu question", count = 60)
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        tab.scrollToBottom()
        val before = tab.topOnScreen(box)

        agent.post("comment_resolve", "thread_id" to alpha)
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "Call it Oak.", caret = 12)
    }

    fun testANewThreadsDraftStaysPutAsThreadsArriveAndGrowBelowIt() {
        val alpha = agent.longAsk("Alpha question")
        tab.newProjectThread()
        val box = user.writeIn(tab.draft()!!, "A plan for the release")
        val before = tab.topOnScreen(box)

        agent.longAsk("Bravo question")
        agent.reply(alpha, paragraphs(40))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertStillWriting(box, "A plan for the release", caret = 22)
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAThreadAboveGrows() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        val whiskey = agent.longAsk("Whiskey question")
        tab.scrollTo(topInTab("Xray") + 50)

        agent.reply(whiskey, paragraphs(40))
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAMessageAboveFolds() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        agent.longAsk("Whiskey question")
        tab.scrollTo(topInTab("Xray") + 50)

        user.fold(tab.panel("Whiskey"), "Whiskey question")
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAMessageAboveOpens() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        agent.longAsk("Whiskey question")
        user.fold(tab.panel("Whiskey"), "Whiskey question")
        tab.settle()
        tab.scrollTo(topInTab("Xray") + 50)

        user.expand(tab.panel("Whiskey"), "Whiskey question")
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAStackAboveOpens() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        readLongThread("Whiskey")
        tab.scrollTo(topInTab("Xray") + 50)

        user.clickLink(tab.panel("Whiskey"), "Show 4 more messages")
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAThreadAboveExpandsAll() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        readLongThread("Whiskey")
        tab.scrollTo(topInTab("Xray") + 50)

        user.press(tab.panel("Whiskey"), "Expand All")
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhenAThreadAboveFoldsAll() {
        agent.longAsk("Yankee question")
        agent.longAsk("Xray question")
        readLongThread("Whiskey")
        tab.scrollTo(topInTab("Xray") + 50)

        user.press(tab.panel("Whiskey"), "Fold All")
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    fun testFoldingAMessageKeepsYourFocusOnItsToggle() {
        agent.ask("Whiskey question")
        user.focusOn(user.foldToggle(tab.panel("Whiskey"), "Whiskey question"))

        user.fold(tab.panel("Whiskey"), "Whiskey question")

        assertYourFocusIsOnTheToggleOf("Whiskey", "Whiskey question")
    }

    fun testOpeningAMessageKeepsYourFocusOnItsToggle() {
        agent.ask("Whiskey question")
        user.fold(tab.panel("Whiskey"), "Whiskey question")
        user.focusOn(user.foldToggle(tab.panel("Whiskey"), "Whiskey question"))

        user.expand(tab.panel("Whiskey"), "Whiskey question")

        assertYourFocusIsOnTheToggleOf("Whiskey", "Whiskey question")
    }

    fun testOpeningAnUnreadMessageKeepsYourFocusOnItsToggle() {
        tab.windowActive = false
        val id = agent.ask("Whiskey question")
        agent.reply(id, "Whiskey details")
        user.fold(tab.panel("Whiskey"), "Whiskey details")
        tab.windowActive = true
        user.focusOn(user.foldToggle(tab.panel("Whiskey"), "Whiskey details"))

        user.expand(tab.panel("Whiskey"), "Whiskey details")

        assertTrue(user.thread(id).messages.last().readByUser)
        assertYourFocusIsOnTheToggleOf("Whiskey", "Whiskey details")
    }

    private fun assertYourFocusIsOnTheToggleOf(thread: String, message: String) {
        // Looking a panel up runs pending events, and the tab's resize timer may rebuild its toggles: read both from one look.
        val panel = tab.panel(thread)
        withClue("focus stays on the fold toggle $thread shows for '$message'") {
            user.lastFocusedInWindowOf(panel) shouldBeSameInstanceAs user.foldToggle(panel, message)
        }
    }

    fun testTheThreadYouReadGrowsDownwardsPushingTheNextOneDown() {
        agent.ask("Bravo question")
        val alpha = agent.longAsk("Alpha question")
        tab.scrollToBottom()
        val alphaBefore = tab.topOnScreen(tab.card("Alpha"))
        val bravoBefore = tab.topOnScreen(tab.card("Bravo"))

        agent.reply(alpha, paragraphs(10))
        tab.settle()

        assertEquals(alphaBefore, tab.topOnScreen(tab.card("Alpha")))
        assertTrue(tab.topOnScreen(tab.card("Bravo")) > bravoBefore)
    }

    fun testAtTheTopANewThreadArrivesInView() {
        agent.longAsk("Alpha question", count = 60)
        assertTrue(tab.scrolls)

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Fresh question")) in 0..100)
    }

    fun testAtTheTopANewThreadArrivesInViewWhileYouRestOnAnotherThread() {
        agent.longAsk("Alpha question", count = 60)
        user.focusOn(tab.panel("Alpha"))

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Fresh question")) in 0..100)
    }

    fun testAtTheTopTheAgentsAnswerArrivesInViewAfterYouSendAReply() {
        val charlie = agent.longAsk("Charlie question", count = 60)
        agent.ask("Bravo question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        user.submit(tab.panel("Bravo"))
        user.focusOn(tab.panel("Bravo"))

        agent.reply(charlie, "Oak it is")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Charlie")) in 0..100)
    }

    fun testAtTheTopANewThreadArrivesInViewAfterYouSendAReplyWhileFocusIsElsewhere() {
        agent.longAsk("Charlie question", count = 60)
        agent.ask("Bravo question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        user.focusOn(tab.toolWindow)
        user.submit(tab.panel("Bravo"))

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Fresh question")) in 0..100)
    }

    fun testAtTheTopANewThreadArrivesInViewAfterYouStartOne() {
        agent.longAsk("Alpha question", count = 60)
        tab.newProjectThread()
        user.writeIn(tab.draft()!!, "A plan for the release")
        user.submit(tab.draft()!!)
        user.focusOn(tab.panel("A plan for the release"))

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Fresh question")) in 0..100)
    }

    fun testTheThreadAtTheTopOfTheViewStaysPutWhileYourCaretRestsInAMessageBelow() {
        agent.longAsk("Zulu question")
        agent.ask("Alpha question")
        val xray = agent.longAsk("Xray question", count = 10)
        agent.longAsk("Whiskey question")
        tab.scrollTo(topInTab("Xray") + 50)
        user.focusOn(user.messageBody(tab.panel("Alpha"), "Alpha question"))

        agent.reply(xray, paragraphs(20))
        tab.settle()

        assertEquals(-50, tab.topInView(tab.card("Xray")))
    }

    private fun threeAroundAlpha(): EditorTextField {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        agent.longAsk("Alpha question", count = 15)
        agent.longAsk("Zulu question")
        return user.writeIn(tab.panel("Bravo"), "Call it Oak.")
    }

    fun testFoldingAThreadKeepsItsHeadingWhereYouClicked() {
        threeAroundAlpha()
        tab.scrollTo(topInTab("Alpha") - 50)

        tab.click("Alpha question")

        assertEquals(listOf(false), shown().filter { "Alpha" in it }.map { "[expanded]" in it })
        assertEquals(50, tab.topInView(tab.card("Alpha")))
    }

    fun testUnfoldingAThreadKeepsItsHeadingWhereYouClicked() {
        threeAroundAlpha()
        tab.click("Alpha question")
        tab.scrollTo(topInTab("Alpha") - 50)

        tab.click("Alpha question")

        assertEquals(listOf(true), shown().filter { "Alpha" in it }.map { "[expanded]" in it })
        assertEquals(50, tab.topInView(tab.card("Alpha")))
    }

    fun testAThreadYouClickedOnlyAnchorsTheLayoutItCaused() {
        agent.longAsk("Echo question")
        agent.ask("Delta question")
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        val box = user.writeIn(tab.panel("Delta"), "Call it Oak.")
        user.writeIn(tab.panel("Bravo"), "Or Elm.")
        tab.click("Bravo question")
        tab.click("Bravo question")
        user.focusOn(box.editor!!.contentComponent)
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(60))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
    }

    fun testWhileYouDragTheScrollBarNothingHoldsTheView() {
        agent.longAsk("Charlie question")
        agent.ask("Bravo question")
        val alpha = agent.ask("Alpha question")
        user.writeIn(tab.panel("Bravo"), "Call it Oak.")

        tab.whileDraggingTheScrollBar {
            agent.reply(alpha, paragraphs(80))
        }

        assertEquals(0, tab.scrolledTo)
    }
}
