package dev.marginalis.plugin.ui.tab

class ResolvedFoldTest : MarginTabTestCase() {

    fun testResolvedThreadsFoldAwayBelowTheOpenOnes() {
        agent.ask("Still open")
        val first = agent.ask("Settled one")
        val second = agent.ask("Settled two")

        agent.post("comment_resolve", "thread_id" to first)
        agent.post("comment_resolve", "thread_id" to second)

        assertEquals(listOf("✉ Still open ● [expanded]", "▸ 2 resolved"), shown())
    }

    fun testOpeningTheFoldListsResolvedThreadsFoldedAndOneCanBeRead() {
        val first = agent.ask("Settled one")
        agent.reply(first, "Done here")
        agent.post("comment_resolve", "thread_id" to first)

        tab.clickResolvedFold()
        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▾ 1 resolved", "Settled one ● — Claude: Done here"), shown())

        tab.click("Settled one")
        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▾ 1 resolved", "Settled one ● [expanded]"), shown())
        assertTrue(user.shownText(tab.panel("Settled one")).contains("resolved by Claude"))

        tab.click("Settled one")
        tab.clickResolvedFold()
        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▸ 1 resolved"), shown())
    }

    fun testReopeningAResolvedThreadBringsItBackToTheList() {
        val first = agent.ask("Settled one")
        agent.post("comment_resolve", "thread_id" to first)
        tab.clickResolvedFold()

        agent.post("comment_reopen", "thread_id" to first)

        assertEquals(listOf("✉ Settled one ● [expanded]"), shown())
    }

    fun testReopeningFromTheFoldTellsTheAgent() {
        val first = agent.ask("Settled one")
        agent.post("comment_resolve", "thread_id" to first)
        tab.clickResolvedFold()
        tab.click("Settled one")

        user.press(tab.panel("Settled one"), "Reopen")

        assertEquals("open", agent.threads().single { it.id == first }.string("status"))
        assertEquals(listOf("✉ Settled one ● [expanded]"), shown())
    }

    fun testClosingAResolvedThreadFoldsItEvenWithAnUnsentReply() {
        val first = agent.ask("Settled one")
        agent.post("comment_resolve", "thread_id" to first)
        tab.clickResolvedFold()
        tab.click("Settled one")
        user.type(tab.panel("Settled one"), "One more thing")

        user.press(tab.panel("Settled one"), "Close")

        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▾ 1 resolved", "Settled one ●"), shown())
    }
}
