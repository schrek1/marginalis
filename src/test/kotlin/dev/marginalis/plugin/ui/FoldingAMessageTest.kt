package dev.marginalis.plugin.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.transport.HttpContractTestCase

class FoldingAMessageTest : HttpContractTestCase() {

    private lateinit var thread: String

    override fun setUp() {
        super.setUp()
        sample()
        thread = agent.addOnLine(1, "body" to FIRST)
        agent.reply(thread, SECOND)
        agent.reply(thread, THIRD)
    }

    private fun bylines(panel: ThreadPanel): List<String> =
        user.shownText(panel).lines().filter { it.matches(Regex("^.+ · \\d{1,2}:\\d{2}.*$")) }

    private fun messageId(body: String): String = user.thread(thread).messages.single { it.body == body }.id

    private fun isRead(body: String): Boolean = user.thread(thread).messages.single { it.body == body }.readByUser

    fun testEveryMessageOffersToFoldItself() {
        val panel = user.open(thread)

        assertEquals(listOf("Fold Message", "Fold Message", "Fold Message"), user.foldToggles(panel))
    }

    fun testAFoldedMessageOffersToExpand() {
        val panel = user.open(thread)

        user.fold(panel, "Second point")

        assertEquals(listOf("Fold Message", "Expand Message", "Fold Message"), user.foldToggles(panel))
    }

    fun testAFoldedMessageShowsOnlyItsOpeningLine() {
        val panel = user.open(thread)

        user.fold(panel, "Second point")

        assertEquals(listOf("Second point: rename it to total."), user.foldedRows(panel))
        assertFalse(user.shownText(panel).contains("Because sum shadows"))
    }

    fun testAFoldedRowShowsItsAuthorAndTime() {
        val panel = user.open(thread)
        val time = user.tooltips(panel).first { it.startsWith("Claude · ") }.substringAfter(" · ")

        user.fold(panel, "Second point")

        val shown = user.shownText(panel).lines()
        val row = shown.indexOf("Second point: rename it to total.")
        assertEquals(listOf("Claude", "Second point: rename it to total.", time), shown.subList(row - 1, row + 2))
    }

    fun testClickingAFoldedRowOpensTheMessage() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        user.clickFoldedRow(panel, "Second point")

        assertEquals(emptyList<String>(), user.foldedRows(panel))
        assertTrue(user.shownText(panel).contains("Because sum shadows"))
    }

    fun testClickingTheAuthorOfAFoldedRowOpensTheMessage() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        user.clickFoldedRow(panel, "Second point", on = "Claude")

        assertEquals(emptyList<String>(), user.foldedRows(panel))
    }

    fun testAnOpeningLineThatLooksLikeMarkupShowsAsText() {
        agent.reply(thread, "<html><b>Bold claim</b> and more")
        val panel = user.open(thread)

        user.fold(panel, "and more")

        assertEquals(listOf("<html><b>Bold claim</b> and more"), user.foldedRows(panel))
        assertEquals(emptyList<String>(), user.foldedRowsShownAsMarkup(panel))
    }

    fun testALongOpeningLineIsCutToThePanelWidth() {
        agent.reply(thread, "A long opening line. ".repeat(40).trim())
        val panel = user.open(thread)

        user.fold(panel, "A long opening line.")

        assertTrue(user.foldedRow(panel, "A long opening line.").preferredSize.width <= panel.preferredSize.width)
    }

    fun testItsChevronOpensTheMessage() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        user.expand(panel, "Second point")

        assertEquals(emptyList<String>(), user.foldedRows(panel))
        assertTrue(user.shownText(panel).contains("Because sum shadows"))
    }

    fun testAFoldedMessageStaysFoldedWhenAReplyArrives() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        agent.reply(thread, "A fourth thought.")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(user.shownText(panel).contains("A fourth thought."))
        assertEquals(listOf("Second point: rename it to total."), user.foldedRows(panel))
    }

    fun testAFoldedMessageStaysFoldedWhenTheThreadIsResolved() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        agent.post("comment_resolve", "thread_id" to thread)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("Second point: rename it to total."), user.foldedRows(panel))
    }

    fun testReopeningTheThreadStartsWithNothingFolded() {
        user.fold(user.open(thread), "Second point")

        val reopened = user.open(thread)

        assertEquals(emptyList<String>(), user.foldedRows(reopened))
    }

    fun testTheSameThreadInTwoPanelsFoldsIndependently() {
        val panel = user.open(thread)
        val other = EditorFactory.getInstance().createEditor(user.editor().document, project)
        try {
            ThreadInlayManager.open(project, other, user.thread(thread))
            val otherPanel = user.panel(other)

            user.fold(panel, "Second point")

            assertEquals(listOf("Second point: rename it to total."), user.foldedRows(panel))
            assertEquals(emptyList<String>(), user.foldedRows(otherPanel))
        } finally {
            ThreadInlayManager.disposeAll()
            EditorFactory.getInstance().releaseEditor(other)
        }
    }

    fun testAnAgentsFollowUpAfterAFoldedRowShowsItsFullHeader() {
        val panel = user.open(thread)
        assertEquals(1, bylines(panel).size)

        user.fold(panel, "Second point")

        assertEquals(2, bylines(panel).size)
        assertEquals(3, user.avatars(panel).size)
    }

    fun testAFoldedGitHubCommentStaysInItsBlock() {
        val id = agent.addOnLine(5, "body" to "Use a constant.", "relayed" to Relays.reviewComment(101))
        agent.reply(id, "Agreed with Mona.", "relayed" to Relays.reviewComment(102, login = "ci[bot]", name = "ci[bot]"))
        val panel = user.open(id)

        user.fold(panel, "Use a constant.")

        val shown = user.shownText(panel).lines()
        assertEquals(1, shown.count { it == "From GitHub · PR #7" })
        assertEquals(listOf("Use a constant."), user.foldedRows(panel))
        assertEquals("From GitHub · PR #7", shown[shown.indexOf("Mona Lisa · via Claude") - 1])
    }

    fun testAFoldedGitHubBotCommentStillSaysItCameFromABot() {
        val id = agent.addOnLine(5, "body" to "Use a constant.", "relayed" to Relays.reviewComment(101))
        agent.reply(id, "Agreed with Mona.", "relayed" to Relays.reviewComment(102, login = "ci[bot]", name = "ci[bot]"))
        val panel = user.open(id)

        user.fold(panel, "Agreed with Mona.")

        val shown = user.shownText(panel).lines()
        assertEquals("bot", shown[shown.indexOf("ci[bot] · via Claude") + 1])
        assertTrue(user.tooltips(panel).contains("@ci[bot] on GitHub"))
    }

    fun testTheMessageBeingEditedCannotBeFolded() {
        user.reply(thread, "My own take.")
        val panel = user.open(thread)

        user.clickLink(panel, "Edit")

        assertEquals(
            listOf("First point.", "Second point: rename it to total.", "Third point."),
            user.messagesWithAFoldToggle(panel).map { it.trim().lines().first() },
        )
    }

    fun testAFoldedUnreadMessageIsNotReadByLookingAtTheThread() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")

        panel.markRead()

        assertTrue(isRead(FIRST))
        assertFalse(isRead(SECOND))
    }

    fun testOpeningAFoldedMessageLetsItBeRead() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")
        panel.markRead()

        user.expand(panel, "Second point")
        panel.markRead()

        assertTrue(isRead(SECOND))
    }

    fun testFollowingAReferenceToAFoldedMessageOpensIt() {
        val panel = user.open(thread)
        user.fold(panel, "Second point")
        val citing = citingPanel(messageId(SECOND))

        user.followLink(citing, "mg:${messageId(SECOND).take(8)}")

        assertEquals(emptyList<String>(), user.foldedRows(panel))
    }

    fun testFollowingAReferenceOpensOnlyThatMessage() {
        val panel = user.open(thread)
        user.fold(panel, "First point")
        user.fold(panel, "Second point")
        val citing = citingPanel(messageId(SECOND))

        user.followLink(citing, "mg:${messageId(SECOND).take(8)}")

        assertEquals(listOf("First point."), user.foldedRows(panel))
    }

    private fun citingPanel(messageId: String): ThreadPanel {
        val citing = agent.addOnLine(9, "body" to "As mg:${messageId.take(8)} says.")
        ThreadInlayManager.open(project, user.editor(), user.thread(citing))
        return user.panels().single { "As mg:" in user.shownText(it) }
    }

    private companion object {
        const val FIRST = "First point."
        const val SECOND = "**Second point**: rename it to `total`.\n\nBecause sum shadows the stdlib function."
        const val THIRD = "Third point."
    }
}
