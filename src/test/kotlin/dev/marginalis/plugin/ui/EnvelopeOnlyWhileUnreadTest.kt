package dev.marginalis.plugin.ui

import dev.marginalis.core.StripeBadge
import dev.marginalis.core.Turn
import dev.marginalis.plugin.ui.toolwindow.ToolWindowTestCase

class EnvelopeOnlyWhileUnreadTest : ToolWindowTestCase() {

    private val ids = mutableMapOf<String, String>()

    override fun setUp() {
        super.setUp()
        sample()
        ids["A"] = agent.addOnLine(1, "body" to "A · a reply you have not read yet")
        ids["D"] = agent.addOnLine(2, "body" to "D · an FYI you have already read", "intent" to "fyi")
        ids["B"] = agent.addOnLine(5, "body" to "B · a reply you read but did not answer")
        ids["C"] = agent.addOnLine(9, "body" to "C · you answered, the agent is on it")
        ids["Project B"] = agent.add("body" to "Project B · a project note you read, not answered")
        user.reply(ids.getValue("C"), "Go ahead.")
        listOf("B", "C", "D", "Project B").forEach(::userReads)
        ids["Project A"] = agent.add("body" to "Project A · a new project note, unread")
        user.closePanels()
    }

    fun testTheTreeShowsTheEnvelopeOnlyOnThreadsHoldingSomethingUnread() {
        assertEquals(
            """
            Open  6
              Project  2
                project  Project B · a project note you read, not answered
                ✉ project  Project A · a new project note, unread
              src  4
                Sample.kt ✉1 ✈1
                  ✉ L1  A · a reply you have not read yet
                  L2  D · an FYI you have already read
                  L5  B · a reply you read but did not answer
                  ✈ L9  C · you answered, the agent is on it
            """.trimIndent(),
            section("Open"),
        )
    }

    fun testOnceEverythingIsReadTheFileRowCountsOnlyTheAgentsMove() {
        readEverything()

        assertEquals("Sample.kt ✈1", window.rows().single { it.startsWith("Sample.kt") })
    }

    fun testANewAgentMessageOnAReadThreadBringsItsEnvelopeBack() {
        readEverything()

        window.rebuiltBy { agent.reply(ids.getValue("B"), "One more thing about B.") }

        assertEquals("✉ L5  B · a reply you read but did not answer", window.rows().single { it.contains("B · a reply") })
    }

    fun testReadThreadsYouHaveNotAnsweredStillAwaitYouForTheAgent() {
        val awaitingYou = agent.ids("awaiting" to "user", "summary" to true)

        assertTrue(awaitingYou.toString(), awaitingYou.containsAll(listOf(ids["B"], ids["Project B"])))
    }

    fun testTheFilesEditorTabKeepsFollowingYourMoveWhetherOrNotItsThreadsAreRead() {
        val whileAIsUnread = store.fileTurns.of(SAMPLE)
        readEverything()

        assertEquals(Turn.USER_OWES to Turn.USER_OWES, whileAIsUnread to store.fileTurns.of(SAMPLE))
    }

    fun testTheMarginTabKeepsTheEnvelopeOnlyOnTheUnreadProjectThreadsCard() {
        val tab = user.openMarginTab()
        assertUnread("Project A")

        val cards = tab.rows()

        assertEquals(listOf(true, false), listOf("Project A", "Project B").map { name -> cards.single { it.contains("$name ·") }.startsWith("✉ ") })
    }

    fun testTheMarginTabHeaderStillCountsEveryProjectThreadThatIsYourMove() {
        val tab = user.openMarginTab()

        assertTrue(tab.counts(), tab.counts().contains("2 your move"))
    }

    fun testTheMarginTabTitleCountsOnlyTheUnreadProjectThread() {
        val tab = user.openMarginTab()
        assertUnread("Project A")

        assertEquals("✉ 1", tab.title())
    }

    fun testOnceEverythingIsReadTheMarginTabIsTitledMargin() {
        val tab = user.openMarginTab()

        readEverything()

        assertEquals("Margin", tab.title())
    }

    fun testTheStripeButtonKeepsTheEnvelopeBadgeWhileAThreadIsUnread() {
        assertBadge(StripeBadge.AWAITING_YOU)
    }

    fun testOnceEverythingIsReadTheStripeButtonIsPlain() {
        readEverything()

        assertBadge(null)
    }

    fun testTheStripeEnvelopeReturnsWithANewAgentMessage() {
        readEverything()

        agent.reply(ids.getValue("B"), "One more thing about B.")

        assertBadge(StripeBadge.AWAITING_YOU)
    }

    fun testAnOpenBlockersRedBadgeWinsOverTheEnvelope() {
        agent.addOnLine(3, "body" to "A blocker: this must change before merge.", "severity" to "blocker")

        assertBadge(StripeBadge.BLOCKER)
    }

    private fun readEverything() = window.rebuiltBy { listOf("A", "Project A").forEach(::userReads) }

    // How a look at the thread reads it; the panel itself marks read only while it is on screen.
    private fun userReads(name: String) {
        val thread = store.threads.byId(ids.getValue(name))!!
        thread.markReadByUser(thread.messages)
        store.threads.notifyChanged(thread)
    }

    private fun assertUnread(name: String) = assertTrue("$name was read already", store.threads.byId(ids.getValue(name))!!.unreadByUserCount() > 0)

    private fun assertBadge(badge: StripeBadge?) {
        window.settle()
        assertSame(MarginalisIcons.toolWindow(badge, projectLive = false), window.window.shownIcon)
    }
}
