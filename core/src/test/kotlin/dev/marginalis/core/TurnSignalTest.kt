package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnSignalTest {

    private val agent = Author.Agent("Claude")
    private val user = Author.User("Muhammad")

    private fun thread(lastWord: Author) =
        CommentThread(file = "a.py", line = 1, anchorText = "x")
            .also { it.addMessage(Message(lastWord, "…")) }

    @Test
    fun `a tally counts only open threads, each on the side whose move it is`() {
        val resolved = thread(agent).also { it.resolve(user) }
        val orphaned = thread(user).also { it.markOrphaned() }
        val tally = TurnTally.of(listOf(thread(agent), thread(agent), thread(user), resolved, orphaned))
        assertEquals(TurnTally(user = 2, agent = 1), tally)
    }

    @Test
    fun `a turn is spoken in words for assistive technology`() {
        assertEquals("your move", TurnSignal.spoken(Turn.USER_OWES))
        assertEquals("agent's move", TurnSignal.spoken(Turn.AGENT_OWES))
    }

    @Test
    fun `a tally is spoken with its counts and silent about empty sides`() {
        assertEquals("your move 2, agent's move 1", TurnSignal.spoken(TurnTally(user = 2, agent = 1)))
        assertEquals("agent's move 3", TurnSignal.spoken(TurnTally(user = 0, agent = 3)))
        assertEquals("", TurnSignal.spoken(TurnTally(user = 0, agent = 0)))
    }

    @Test
    fun `a tally is shown as turn glyphs with its counts and silent about empty sides`() {
        assertEquals("✉ 2 · ✈ 1", TurnSignal.glyphs(TurnTally(user = 2, agent = 1)))
        assertEquals("✈ 3", TurnSignal.glyphs(TurnTally(user = 0, agent = 3)))
        assertEquals("", TurnSignal.glyphs(TurnTally(user = 0, agent = 0)))
    }
}
