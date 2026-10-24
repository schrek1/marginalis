package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.Relays

class FilterTest : MarginTabTestCase() {

    fun testFindNarrowsTheTabToThreadsThatMentionTheSearch() {
        agent.ask("Release notes for 0.3")
        agent.ask("Flaky build on CI")
        reviewer.ask("Who reviews the release?")

        tab.find("release")

        assertEquals("release", tab.filterText())
        assertEquals(listOf("✉ Who reviews the release? ● [expanded]", "✉ Release notes for 0.3 ● [expanded]"), shown())
    }

    fun testFindMatchesWhoSpoke() {
        agent.ask("From the main agent")
        reviewer.ask("From the reviewer")

        tab.find("review")

        assertEquals(listOf("✉ From the reviewer ● [expanded]"), shown())
    }

    fun testASearchWithoutMatchesSaysSo() {
        agent.ask("Release notes for 0.3")

        tab.find("zebra")

        assertEquals(listOf("No open project thread mentions “zebra”."), shown())
    }

    fun testEscapeClearsTheSearchAndHidesTheField() {
        agent.ask("Release notes for 0.3")
        agent.ask("Flaky build on CI")
        tab.find("release")

        tab.escapeFilter()

        assertNull(tab.filterText())
        assertEquals(2, shown().size)
    }

    fun testTheSearchNarrowsTheResolvedFoldToo() {
        val notes = agent.ask("Release notes for 0.3")
        val build = agent.ask("Flaky build on CI")
        agent.post("comment_resolve", "thread_id" to notes)
        agent.post("comment_resolve", "thread_id" to build)
        tab.clickResolvedFold()

        tab.find("flaky")

        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▾ 2 resolved", "Flaky build on CI ●"), shown())
    }

    fun testFindingAPullRequestShowsItsConversation() {
        agent.add("body" to "Looks good", "relayed" to Relays.conversationComment(1))
        agent.ask("Unrelated")

        tab.find("PR #7")

        assertEquals(1, shown().size)
        assertTrue(shown().single().startsWith("From GitHub · PR #7"))
    }
}
