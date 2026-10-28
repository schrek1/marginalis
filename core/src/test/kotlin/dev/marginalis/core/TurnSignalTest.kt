package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    private fun agentReply(read: Boolean) = thread(user).also {
        it.addMessage(Message(agent, "Done."))
        if (read) it.markReadByUser()
    }

    @Test
    fun `an agent reply you have not read shows your move`() {
        val unreadReply = agentReply(read = false)

        assertEquals(Turn.USER_OWES, TurnSignal.of(unreadReply))
    }

    @Test
    fun `an agent reply you read but did not answer shows no signal while the turn stays yours`() {
        val readReply = agentReply(read = true)

        assertNull(TurnSignal.of(readReply))
        assertEquals(Turn.USER_OWES, readReply.turn())
    }

    @Test
    fun `a new agent message on a read thread brings your move back`() {
        val readReply = agentReply(read = true).also { it.addMessage(Message(agent, "One more thing.")) }

        assertEquals(Turn.USER_OWES, TurnSignal.of(readReply))
    }

    @Test
    fun `a thread you answered shows the agent's move`() {
        val answered = agentReply(read = true).also { it.addMessage(Message(user, "Thanks, go on.")) }

        assertEquals(Turn.AGENT_OWES, TurnSignal.of(answered))
    }

    @Test
    fun `a read fyi shows no signal`() {
        val readFyi = CommentThread(file = "a.py", line = 1, anchorText = "x", intent = Intent.FYI).also {
            it.addMessage(Message(agent, "FYI."))
            it.markReadByUser()
        }

        assertNull(TurnSignal.of(readFyi))
    }

    @Test
    fun `a signalled tally leaves out what you have read but the turn tally keeps it`() {
        val threads = listOf(agentReply(read = false), agentReply(read = true), thread(user))

        assertEquals(TurnTally(user = 1, agent = 1), TurnTally.signalled(threads))
        assertEquals(TurnTally(user = 2, agent = 1), TurnTally.of(threads))
    }

    @Test
    fun `a signalled tally leaves out an Agree the agent has not answered yet`() {
        val builder = Author.Agent("Claude · builder", "claude-builder")
        val agreed = CommentThread(file = "a.py", line = 1, anchorText = "x").also {
            it.addMessage(Message(builder, "Rename it?"))
            it.addMessage(Message.agreement(by = user, with = builder))
        }

        assertEquals(TurnTally(user = 0, agent = 1), TurnTally.of(listOf(agreed)))
        assertEquals(TurnTally(user = 0, agent = 1), TurnTally.signalled(listOf(agreed, thread(user))))
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
