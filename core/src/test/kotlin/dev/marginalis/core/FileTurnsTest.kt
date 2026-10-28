package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTurnsTest {

    private val agent = Author.Agent("Claude")
    private val user = Author.User("Muhammad")

    private fun thread(file: String?, lastWord: Author) =
        CommentThread(file = file, line = 1, anchorText = "x")
            .also { it.addMessage(Message(lastWord, "…")) }

    @Test
    fun `an Agree the agent has not answered yet puts no agent's move on its file`() {
        val turns = FileTurns()
        val agreed = thread("a.py", agent).also { it.addMessage(Message.agreement(by = user, with = agent)) }

        turns.track("a.py", listOf(agreed))

        assertNull(turns.of("a.py"))
    }

    @Test
    fun `nothing tracked means an empty snapshot`() {
        val turns = FileTurns()
        assertTrue(turns.isEmpty())
        assertNull(turns.of("a.py"))
    }

    @Test
    fun `tracking a file records whose move it is from that file's threads alone`() {
        val turns = FileTurns()
        val threads = listOf(thread("a.py", user), thread("b.py", agent))
        assertTrue(turns.track("a.py", threads))
        assertEquals(Turn.AGENT_OWES, turns.of("a.py"))
        assertNull(turns.of("b.py"))
        assertEquals(setOf("a.py"), turns.paths())
    }

    @Test
    fun `re-tracking an unchanged turn reports no change`() {
        val turns = FileTurns()
        val threads = listOf(thread("a.py", agent))
        turns.track("a.py", threads)
        assertFalse(turns.track("a.py", threads))
    }

    @Test
    fun `a file whose threads stop asking anything drops out`() {
        val turns = FileTurns()
        val open = thread("a.py", agent)
        turns.track("a.py", listOf(open))
        open.resolve(user)
        assertTrue(turns.track("a.py", listOf(open)))
        assertNull(turns.of("a.py"))
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a file with nothing tracked and nothing asked is no change`() {
        assertFalse(FileTurns().track("a.py", emptyList()))
    }
}
