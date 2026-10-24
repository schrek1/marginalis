package dev.marginalis.core

import dev.marginalis.core.ProjectTabStartup.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTabStartupTest {

    private val agent = Author.Agent("Claude", "claude-main")

    private fun projectThread() =
        CommentThread(file = null, line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    private fun resolvedProjectThread() = projectThread().also { it.resolve(agent) }

    private fun fileThread() =
        CommentThread(file = "src/a.py", line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }

    @Test
    fun `a project's first open without project threads leaves the tab closed`() {
        val outcome = ProjectTabStartup.outcome(emptyList(), wasOpen = false, wasPinned = false, firstOpen = true, showOnOpen = true)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `a project's first open with only resolved project threads and file threads leaves the tab closed`() {
        val threads = listOf(resolvedProjectThread(), fileThread())

        val outcome = ProjectTabStartup.outcome(threads, wasOpen = false, wasPinned = false, firstOpen = true, showOnOpen = true)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `a project's first open with an open project thread opens the tab unpinned`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = false, wasPinned = false, firstOpen = true, showOnOpen = true)

        assertEquals(Outcome.OPEN, outcome)
    }

    @Test
    fun `a tab open and pinned before a plugin reload comes back pinned, even with nothing in it`() {
        val outcome = ProjectTabStartup.outcome(emptyList(), wasOpen = true, wasPinned = true, firstOpen = false, showOnOpen = true)

        assertEquals(Outcome.OPEN_PINNED, outcome)
    }

    @Test
    fun `a tab open before a plugin reload comes back unpinned when it was not pinned`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = true, wasPinned = false, firstOpen = false, showOnOpen = true)

        assertEquals(Outcome.OPEN, outcome)
    }

    @Test
    fun `a later open leaves the tab to the platform, even with open project threads`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = false, wasPinned = false, firstOpen = false, showOnOpen = true)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `with the tab not shown on open, a project's first open with an open project thread leaves the tab closed`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = false, wasPinned = false, firstOpen = true, showOnOpen = false)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `with the tab not shown on open, a tab open and pinned before a plugin reload still comes back pinned`() {
        val outcome = ProjectTabStartup.outcome(emptyList(), wasOpen = true, wasPinned = true, firstOpen = false, showOnOpen = false)

        assertEquals(Outcome.OPEN_PINNED, outcome)
    }
}
