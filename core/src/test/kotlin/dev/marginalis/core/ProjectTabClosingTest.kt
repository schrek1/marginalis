package dev.marginalis.core

import dev.marginalis.core.ProjectTabStartup.TabState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectTabClosingTest {

    private val agent = Author.Agent("Claude", "claude-main")

    private fun projectThread() =
        CommentThread(file = null, line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    private fun fileThread() =
        CommentThread(file = "src/a.py", line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    @Test
    fun `showing it with something in it, an unpinned tab with nothing in it closes with the project`() {
        assertTrue(ProjectTabClosing.closesTheTab(MarginTabShows.WITH_SOMETHING_IN_IT, emptyList(), TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, resolved project threads and file threads do not keep the tab`() {
        val threads = listOf(projectThread().also { it.resolve(agent) }, fileThread())

        assertTrue(ProjectTabClosing.closesTheTab(MarginTabShows.WITH_SOMETHING_IN_IT, threads, TabState.OPEN))
    }

    @Test
    fun `showing it with something in it, a pinned tab stays with the project`() {
        assertFalse(ProjectTabClosing.closesTheTab(MarginTabShows.WITH_SOMETHING_IN_IT, emptyList(), TabState.PINNED))
    }

    @Test
    fun `showing it with something in it, a tab with an open project thread stays with the project`() {
        assertFalse(ProjectTabClosing.closesTheTab(MarginTabShows.WITH_SOMETHING_IN_IT, listOf(projectThread()), TabState.OPEN))
    }

    @Test
    fun `a closed tab has nothing to close`() {
        assertFalse(ProjectTabClosing.closesTheTab(MarginTabShows.WITH_SOMETHING_IN_IT, emptyList(), TabState.CLOSED))
    }

    @Test
    fun `keeping the tab open, the tab always stays with the project`() {
        assertFalse(ProjectTabClosing.closesTheTab(MarginTabShows.ALWAYS, emptyList(), TabState.OPEN))
    }
}
