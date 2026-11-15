package dev.marginalis.core

import dev.marginalis.core.ProjectTabStartup.Outcome
import dev.marginalis.core.ProjectTabStartup.TabState
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTabStartupTest {

    private val agent = Author.Agent("Claude", "claude-main")

    private fun projectThread() =
        CommentThread(file = null, line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    private fun resolvedProjectThread() = projectThread().also { it.resolve(agent) }

    private fun fileThread() =
        CommentThread(file = "src/a.py", line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    private fun outcome(
        shows: MarginTabShows,
        threads: List<CommentThread> = emptyList(),
        firstOpen: Boolean = false,
        beforeReload: TabState = TabState.CLOSED,
        restored: TabState = TabState.CLOSED,
    ) = ProjectTabStartup.outcome(shows, threads, firstOpen, beforeReload, restored)

    @Test
    fun `keeping the tab open, a project's first open opens it pinned even with nothing in it`() {
        assertEquals(Outcome.OPEN_PINNED, outcome(MarginTabShows.ALWAYS, firstOpen = true))
    }

    @Test
    fun `keeping the tab open, a plugin reload brings the tab back as it was`() {
        assertEquals(Outcome.OPEN_PINNED, outcome(MarginTabShows.ALWAYS, beforeReload = TabState.PINNED))
        assertEquals(Outcome.OPEN, outcome(MarginTabShows.ALWAYS, beforeReload = TabState.OPEN))
    }

    @Test
    fun `keeping the tab open, a later open leaves a restored empty tab where the platform put it`() {
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.ALWAYS, restored = TabState.OPEN))
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.ALWAYS))
    }

    @Test
    fun `showing it with something in it, a project's first open without project threads leaves the tab closed`() {
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, firstOpen = true))
    }

    @Test
    fun `showing it with something in it, resolved project threads and file threads do not count`() {
        val threads = listOf(resolvedProjectThread(), fileThread())

        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, threads, firstOpen = true))
        assertEquals(Outcome.CLOSE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, threads, restored = TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, a project's first open with an open project thread opens the tab unpinned`() {
        assertEquals(Outcome.OPEN, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, listOf(projectThread()), firstOpen = true))
    }

    @Test
    fun `showing it with something in it, a restored unpinned tab with nothing in it closes`() {
        assertEquals(Outcome.CLOSE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, restored = TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, a restored tab stays when it has an open project thread`() {
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, listOf(projectThread()), restored = TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, a restored tab the user pinned stays even with nothing in it`() {
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, restored = TabState.PINNED))
    }

    @Test
    fun `showing it with something in it, a plugin reload brings the tab back as it was`() {
        assertEquals(Outcome.OPEN_PINNED, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, beforeReload = TabState.PINNED))
        assertEquals(Outcome.OPEN, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, beforeReload = TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, a later open leaves a closed tab closed even with open project threads`() {
        assertEquals(Outcome.LEAVE, outcome(MarginTabShows.WITH_SOMETHING_IN_IT, listOf(projectThread())))
    }
}
