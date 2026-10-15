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
        val outcome = ProjectTabStartup.outcome(emptyList(), wasOpen = false, wasPinned = false, firstOpen = true)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `a project's first open with an open project thread opens the tab unpinned`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = false, wasPinned = false, firstOpen = true)

        assertEquals(Outcome.OPEN, outcome)
    }

    @Test
    fun `a tab left open and pinned comes back pinned while it has an open project thread`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = true, wasPinned = true, firstOpen = false)

        assertEquals(Outcome.OPEN_PINNED, outcome)
    }

    @Test
    fun `a tab left open comes back unpinned when it was not pinned`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = true, wasPinned = false, firstOpen = false)

        assertEquals(Outcome.OPEN, outcome)
    }

    @Test
    fun `a tab left open stays closed when only resolved project threads and file threads remain`() {
        val threads = listOf(resolvedProjectThread(), fileThread())

        val outcome = ProjectTabStartup.outcome(threads, wasOpen = true, wasPinned = true, firstOpen = false)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }

    @Test
    fun `a tab the user closed stays closed even with open project threads`() {
        val outcome = ProjectTabStartup.outcome(listOf(projectThread()), wasOpen = false, wasPinned = false, firstOpen = false)

        assertEquals(Outcome.STAY_CLOSED, outcome)
    }
}
