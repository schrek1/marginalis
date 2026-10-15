package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.settings.MarginalisSettings

class ProjectThreadsTest : MarginTabTestCase() {

    // FixMe: Known issue - #48 the Margin tab lists threads newest first, the opposite of how a thread reads
    fun testAnAgentsQuestionsArriveExpandedWithTheNewestOnTop() {
        agent.ask("Alpha question")
        agent.ask("Bravo question")
        agent.ask("Charlie question")

        assertEquals(
            listOf("✉ Charlie question ● [expanded]", "✉ Bravo question ● [expanded]", "✉ Alpha question ● [expanded]"),
            shown(),
        )
        assertEquals("✉ 3", tab.title())
        assertEquals("3 open · 3 your move · 0 agent's move · 3 new", tab.counts())
    }

    // FixMe: Known issue - #48 the Margin tab lists threads by latest activity, so a reply moves its thread to the top
    fun testAReplyMovesItsThreadToTheTop() {
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")

        agent.reply(alpha, "More on alpha")

        assertEquals(listOf("✉ Alpha question ● [expanded]", "✉ Bravo question ● [expanded]"), shown())
    }

    fun testAReplyWrittenInTheTabReachesTheAgentAndPassesTheTurn() {
        val alpha = agent.ask("Which release name?")
        val panel = tab.panel("Which release name?")

        user.type(panel, "Call it Oak.")
        user.submit(panel)

        val thread = agent.threads().single { it.id == alpha }
        assertEquals(listOf("Which release name?", "Call it Oak."), thread.messages().map { it.string("body") })
        assertEquals(listOf("✈ Which release name? ● [expanded]"), shown())
        assertEquals("✈ 1", tab.title())
    }

    fun testClickingAThreadFoldsItToItsLatestMessageAndClickingAgainOpensIt() {
        MarginalisSettings.getInstance().state.displayName = "Ada"
        agent.ask("Which release name?")
        tab.panel("Which release name?").let {
            user.type(it, "Call it Oak.")
            user.submit(it)
        }

        tab.click("Which release name?")
        assertEquals(listOf("✈ Which release name? ● — Ada: Call it Oak."), shown())

        tab.click("Which release name?")
        assertEquals(listOf("✈ Which release name? ● [expanded]"), shown())
    }

    fun testAFoldedThreadThatBecomesYourMoveOpensItself() {
        val alpha = agent.ask("Which release name?")
        tab.panel("Which release name?").let {
            user.type(it, "Call it Oak.")
            user.submit(it)
        }
        tab.click("Which release name?")

        agent.reply(alpha, "Oak is taken. Another?")

        assertEquals(listOf("✉ Which release name? ● [expanded]"), shown())
    }

    fun testWithAutoExpandOffAFoldedThreadStaysFoldedWhenItBecomesYourMove() {
        user.changeProjectTabSettings { expandWhenItBecomesYourMove(false) }
        val alpha = agent.ask("Which release name?")
        tab.panel("Which release name?").let {
            user.type(it, "Call it Oak.")
            user.submit(it)
        }
        tab.click("Which release name?")

        agent.reply(alpha, "Oak is taken. Another?")

        assertEquals(listOf("✉ Which release name? ● — Claude: Oak is taken. Another?"), shown())
    }

    fun testAThreadHoldingAnUnsentReplyKeepsItsPlace() {
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        agent.ask("Charlie question")
        user.type(tab.panel("Charlie question"), "Half-written answer")

        agent.reply(alpha, "More on alpha")

        assertEquals(
            listOf("✉ Charlie question ● [expanded]", "✉ Alpha question ● [expanded]", "✉ Bravo question ● [expanded]"),
            shown(),
        )
    }

    fun testAResolvedThreadHoldingAnUnsentReplyStaysInTheListUntilTheReplyIsGone() {
        val alpha = agent.ask("Alpha question")
        user.type(tab.panel("Alpha question"), "Half-written answer")

        agent.post("comment_resolve", "thread_id" to alpha)

        assertEquals(listOf("Alpha question ● [expanded]"), shown())
        user.type(tab.panel("Alpha question"), "")
        tab.refreshedBy { agent.ask("Bravo question") }
        assertEquals(listOf("✉ Bravo question ● [expanded]", "▸ 1 resolved"), shown())
    }

    // FixMe: Known issue - #49 a long thread in the Margin tab shows every message in full, burying the latest answers
    fun testALongThreadShowsEveryMessageInFull() {
        val long = agent.ask("Message 1")
        tab.click("Message 1")
        (2..7).forEach { agent.reply(long, "Message $it") }
        tab.click("Message 1")

        val text = user.shownText(tab.panel("Message 1"))

        (1..7).forEach { assertTrue(text, text.lines().contains("Message $it")) }
    }

    fun testClosingAnOpenThreadFoldsIt() {
        agent.ask("Which release name?")

        user.press(tab.panel("Which release name?"), "Close")

        assertEquals(listOf("✉ Which release name? ●"), shown())
    }

    fun testClosingAThreadWithAnUnsentReplyKeepsItOpen() {
        agent.ask("Which release name?")
        val panel = tab.panel("Which release name?")
        user.type(panel, "Half-written")

        user.press(panel, "Close")

        assertEquals(listOf("✉ Which release name? ● [expanded]"), shown())
        assertEquals("Half-written", user.composer(tab.panel("Which release name?")).text)
    }

    fun testResolvingInTheTabFoldsTheThreadAwayAndTellsTheAgent() {
        val alpha = agent.ask("Which release name?")

        user.press(tab.panel("Which release name?"), "Resolve")

        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▸ 1 resolved"), shown())
        assertEquals("resolved", agent.threads("status" to "resolved").single { it.id == alpha }.string("status"))
        assertEquals("Margin", tab.title())
    }

    fun testDeletingInTheTabRemovesTheThreadForTheAgentToo() {
        agent.ask("Keep me")
        val doomed = agent.ask("Delete me")

        user.pressDelete(tab.panel("Delete me"), confirm = true)

        assertEquals(listOf("✉ Keep me ● [expanded]"), shown())
        assertFalse(agent.ids().contains(doomed))
    }

    fun testAgreeingInTheTabAnswersTheAgent() {
        val alpha = agent.ask("Shall we call it Oak?")

        user.clickLink(tab.panel("Shall we call it Oak?"), "Agree")

        assertEquals("Agreed.", agent.threads().single { it.id == alpha }.messages().last().string("body"))
        assertEquals(listOf("✈ Shall we call it Oak? ● [expanded]"), shown())
    }

    fun testFileThreadsNeverShowInTheTab() {
        sample()
        agent.addOnLine(5, "body" to "About line five")
        agent.add("file" to SAMPLE, "body" to "About the file")
        agent.ask("About the project")

        assertEquals(listOf("✉ About the project ● [expanded]"), shown())
    }
}
