package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StripeBadgeTest {

    private val agent = Author.Agent("Claude")
    private val user = Author.User("Muhammad")

    private fun thread(lastWord: Author, severity: Severity? = null) =
        CommentThread(file = "a.py", line = 1, anchorText = "x", severity = severity)
            .also { it.addMessage(Message(lastWord, "…")) }

    @Test
    fun `nothing open means no stripe badge`() {
        assertNull(StripeBadge.of(emptyList()))
        assertNull(StripeBadge.of(listOf(thread(agent).also { it.resolve(user) })))
    }

    @Test
    fun `the agent's move alone raises no stripe badge`() {
        assertNull(StripeBadge.of(listOf(thread(user))))
    }

    @Test
    fun `something awaiting you raises the envelope`() {
        assertEquals(StripeBadge.AWAITING_YOU, StripeBadge.of(listOf(thread(user), thread(agent))))
    }

    @Test
    fun `an open blocker wins over the envelope`() {
        assertEquals(StripeBadge.BLOCKER, StripeBadge.of(listOf(thread(agent), thread(user, Severity.BLOCKER))))
    }

    @Test
    fun `a resolved blocker is not an open one`() {
        val concluded = thread(user, Severity.BLOCKER).also { it.resolve(user) }
        assertEquals(StripeBadge.AWAITING_YOU, StripeBadge.of(listOf(thread(agent), concluded)))
    }
}
