package dev.marginalis.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.Author
import dev.marginalis.core.Message
import dev.marginalis.plugin.transport.HttpContractTestCase

class LongThreadsOpenFoldedTest : HttpContractTestCase() {

    private lateinit var thread: String

    override fun setUp() {
        super.setUp()
        sample()
        thread = longThread(line = 1, count = 7)
    }

    fun testAnUnreadThreadOpensEveryMessage() {
        val panel = user.open(thread)

        assertEquals((1..7).toList(), opened(panel))
        assertEquals(emptyList<Int>(), folded(panel))
    }

    fun testAReadThreadOpensWithOnlyItsLastTwoMessagesOpen() {
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testTheFirstReadMessageFoldsToItsOpeningLine() {
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf(1), folded(panel))
    }

    fun testMoreThanThreeFoldedMessagesInARowStackBehindOneLink() {
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf("Show 4 more messages"), stacks(panel))
    }

    fun testThreeFoldedMessagesInARowStayAsFoldedRows() {
        val short = longThread(line = 3, count = 5)
        user.read(short)

        val panel = user.open(short)

        assertEquals(listOf(1, 2, 3), folded(panel))
        assertEquals(emptyList<String>(), stacks(panel))
    }

    fun testTheLinkRevealsTheStackAsFoldedRows() {
        user.read(thread)
        val panel = user.open(thread)

        user.clickLink(panel, "Show 4 more messages")

        assertEquals(listOf(1, 2, 3, 4, 5), folded(panel))
        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testAnUnreadMessageAmongReadOnesStaysOpen() {
        readElsewhere(except = 4)

        val panel = user.open(thread)

        assertEquals(listOf(4, 6, 7), opened(panel))
        assertEquals(listOf(1, 2, 3, 5), folded(panel))
    }

    fun testNothingFoldsUnderTheReader() {
        val panel = user.open(thread)

        readElsewhere()
        panel.refresh()

        assertEquals((1..7).toList(), opened(panel))
    }

    fun testRepliesArrivingWhileTheThreadIsOpenFoldWhatCameBeforeTheLastTwo() {
        user.read(thread)
        val panel = user.open(thread)

        agent.reply(thread, body(8))
        agent.reply(thread, body(9))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        readElsewhere()
        panel.refresh()

        assertEquals(listOf(8, 9), opened(panel))
        assertEquals(listOf("Show 6 more messages"), stacks(panel))
    }

    fun testEachReplyKeepsOnlyTheLastTwoOpen() {
        user.read(thread)
        val panel = user.open(thread)

        (8..10).forEach {
            agent.reply(thread, body(it))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            readElsewhere()
        }

        assertEquals(listOf(9, 10), opened(panel))
    }

    fun testYourOwnReplyFoldsWhatCameBeforeTheMessageYouAnswer() {
        user.read(thread)
        val panel = user.open(thread)

        user.type(panel, body(8))
        user.submit(panel)

        assertEquals(listOf(7, 8), opened(panel))
    }

    fun testRepliesKeepOpenAMessageYouOpened() {
        user.read(thread)
        val panel = user.open(thread)
        user.clickLink(panel, "Show 4 more messages")
        user.expand(panel, "Point 3.")

        agent.reply(thread, body(8))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        readElsewhere()

        assertEquals(listOf(3, 7, 8), opened(panel))
    }

    fun testRepliesReadElsewhereBeforeThisPanelCatchesUpStillArriveOpen() {
        user.read(thread)
        val panel = user.open(thread)

        val replied = user.thread(thread)
        (8..10).forEach { replied.addMessage(Message(Author.Agent("Claude", "claude-main"), body(it))) }
        readElsewhere()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(8, 9, 10), opened(panel))
    }

    fun testRepliesReadWhileAllAreExpandedStayOpenBackInTheDefaultView() {
        user.read(thread)
        val panel = user.open(thread)
        user.press(panel, EXPAND_ALL)

        (8..10).forEach { agent.reply(thread, body(it)) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        panel.markRead()
        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf(6, 7, 8, 9, 10), opened(panel))
    }

    fun testReopeningFoldsWhatWasReadWhileOpen() {
        user.open(thread)
        readElsewhere()

        val reopened = user.open(thread)

        assertEquals(listOf(6, 7), opened(reopened))
    }

    fun testFollowingALinkIntoTheStackOpensOnlyThatMessage() {
        user.read(thread)

        val panel = afterFollowingALinkTo(thread, message = 4)

        assertEquals(listOf(4, 6, 7), opened(panel))
        assertEquals(listOf(1, 2, 3, 5), folded(panel))
        assertEquals(emptyList<String>(), stacks(panel))
    }

    fun testFollowingALinkIntoTheStackLeavesALongRemainingRunStacked() {
        val long = longThread(line = 3, count = 10)
        user.read(long)

        val panel = afterFollowingALinkTo(long, message = 2)

        assertEquals(listOf(2, 9, 10), opened(panel))
        assertEquals(listOf(1), folded(panel))
        assertEquals(listOf("Show 6 more messages"), stacks(panel))
    }

    fun testTheHeaderOffersToFoldAllAndExpandAll() {
        val panel = user.open(thread)

        assertEquals(mapOf(FOLD_ALL to true, EXPAND_ALL to true), levelActions(panel))
    }

    fun testFoldAndExpandShowTheirIcons() {
        val panel = user.open(thread)

        assertEquals(AllIcons.Actions.Collapseall, user.toolbar(panel).getValue(FOLD_ALL).icon)
        assertEquals(AllIcons.Actions.Expandall, user.toolbar(panel).getValue(EXPAND_ALL).icon)
    }

    fun testFoldAllFoldsEveryMessageAndStacksTheRun() {
        val panel = user.open(thread)

        user.press(panel, FOLD_ALL)

        assertEquals(emptyList<Int>(), opened(panel))
        assertEquals(listOf(1), folded(panel))
        assertEquals(listOf("Show 6 more messages"), stacks(panel))
    }

    fun testFoldAllLeavesUnreadMessagesUnread() {
        val panel = user.open(thread)

        user.press(panel, FOLD_ALL)
        panel.markRead()

        assertEquals(List(7) { false }, user.thread(thread).messages.map { it.readByUser })
    }

    fun testWhenAllAreFoldedExpandGoesBackToTheDefaultView() {
        val panel = user.open(thread)

        user.press(panel, FOLD_ALL)

        assertEquals(mapOf(FOLD_ALL to false, BACK_TO_DEFAULT to true), levelActions(panel))
    }

    fun testBackToTheDefaultViewFromAllFolded() {
        user.read(thread)
        val panel = user.open(thread)
        user.press(panel, FOLD_ALL)

        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf(6, 7), opened(panel))
        assertEquals(listOf("Show 4 more messages"), stacks(panel))
    }

    fun testExpandAllOpensEveryMessage() {
        user.read(thread)
        val panel = user.open(thread)

        user.press(panel, EXPAND_ALL)

        assertEquals((1..7).toList(), opened(panel))
        assertEquals(emptyList<String>(), stacks(panel))
    }

    fun testWhenAllAreExpandedFoldGoesBackToTheDefaultView() {
        val panel = user.open(thread)

        user.press(panel, EXPAND_ALL)

        assertEquals(mapOf(BACK_TO_DEFAULT to true, EXPAND_ALL to false), levelActions(panel))
    }

    fun testBackToTheDefaultViewFromAllExpanded() {
        user.read(thread)
        val panel = user.open(thread)
        user.press(panel, EXPAND_ALL)

        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testChangingTheViewForgetsTheMessagesYouOpened() {
        user.read(thread)
        val panel = user.open(thread)
        user.expand(panel, "Point 1.")

        user.press(panel, EXPAND_ALL)
        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf(1), folded(panel))
    }

    fun testChangingTheViewForgetsTheMessagesYouFolded() {
        user.read(thread)
        val panel = user.open(thread)
        user.fold(panel, "Point 7.")

        user.press(panel, FOLD_ALL)
        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testChangingTheViewStacksARevealedRunAgain() {
        user.read(thread)
        val panel = user.open(thread)
        user.clickLink(panel, "Show 4 more messages")

        user.press(panel, FOLD_ALL)
        user.press(panel, BACK_TO_DEFAULT)

        assertEquals(listOf("Show 4 more messages"), stacks(panel))
    }

    fun testOneMessageOpensWhileAllAreFolded() {
        val panel = user.open(thread)
        user.press(panel, FOLD_ALL)

        user.expand(panel, "Point 1.")

        assertEquals(listOf(1), opened(panel))
    }

    fun testOneMessageFoldsWhileAllAreExpanded() {
        val panel = user.open(thread)
        user.press(panel, EXPAND_ALL)

        user.fold(panel, "Point 3.")

        assertEquals(listOf(3), folded(panel))
    }

    fun testReopeningReturnsToTheDefaultView() {
        user.read(thread)
        user.press(user.open(thread), EXPAND_ALL)

        val reopened = user.open(thread)

        assertEquals(listOf(6, 7), opened(reopened))
    }

    fun testTheMessageBeingEditedStaysOpenWhenAllFold() {
        user.reply(thread, "My own take.\n\nWith a reason.")
        val panel = user.open(thread)
        user.clickLink(panel, "Edit")

        user.press(panel, FOLD_ALL)

        assertTrue(user.shownText(panel).lines().contains("editing below ↓"))
        assertFalse(user.foldedRows(panel).contains("My own take."))
    }

    fun testAnAgreementStaysInViewWhenAllFold() {
        user.agree(thread)
        val panel = user.open(thread)

        user.press(panel, FOLD_ALL)

        assertTrue(user.shownText(panel).lines().any { " agreed · " in it })
    }

    fun testAnAgreementIsNotOneOfTheLastTwoMessages() {
        user.agree(thread)
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testTheSameThreadInTwoEditorsHasItsOwnView() {
        val panel = user.open(thread)
        val other = EditorFactory.getInstance().createEditor(user.editor().document, project)
        try {
            ThreadInlayManager.open(project, other, user.thread(thread))
            val otherPanel = user.panel(other)

            user.press(panel, FOLD_ALL)

            assertEquals((1..7).toList(), opened(otherPanel))
        } finally {
            ThreadInlayManager.disposeAll()
            EditorFactory.getInstance().releaseEditor(other)
        }
    }

    private fun longThread(line: Int, count: Int): String {
        val id = agent.addOnLine(line, "body" to body(1))
        (2..count).forEach { agent.reply(id, body(it)) }
        return id
    }

    private fun afterFollowingALinkTo(threadId: String, message: Int): ThreadPanel {
        val panel = user.open(threadId)
        val reference = "mg:${user.thread(threadId).messages[message - 1].id.take(8)}"
        val citing = agent.addOnLine(9, "body" to "As $reference says.")
        ThreadInlayManager.open(project, user.editor(), user.thread(citing))
        user.followLink(user.panels().single { "As mg:" in user.shownText(it) }, reference)
        return panel
    }

    private fun readElsewhere(except: Int? = null) {
        val read = user.thread(thread)
        read.markReadByUser(read.messages.filterIndexed { index, _ -> index + 1 != except })
        store.threads.notifyChanged(read)
    }

    private fun opened(panel: ThreadPanel): List<Int> =
        user.shownText(panel).lines().mapNotNull { DETAIL.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    private fun folded(panel: ThreadPanel): List<Int> =
        user.foldedRows(panel).mapNotNull { POINT.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    private fun stacks(panel: ThreadPanel): List<String> = user.visibleLinks(panel).filter { STACK.matches(it) }

    private fun levelActions(panel: ThreadPanel): Map<String, Boolean> = user.toolbar(panel)
        .filterKeys { it in setOf(FOLD_ALL, EXPAND_ALL, BACK_TO_DEFAULT) }
        .filterValues { it.isVisible }
        .mapValues { it.value.isEnabled }

    private companion object {
        fun body(n: Int) = "Point $n.\n\nDetail $n."
        val DETAIL = Regex("""Detail (\d+)\.""")
        val POINT = Regex("""Point (\d+)\.""")
        val STACK = Regex("""Show \d+ more messages""")
        const val FOLD_ALL = "Fold All"
        const val EXPAND_ALL = "Expand All"
        const val BACK_TO_DEFAULT = "Back to Default View"
    }
}
