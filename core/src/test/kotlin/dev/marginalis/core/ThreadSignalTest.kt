package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadSignalTest {

    private val builder = Author.Agent("Claude · builder", "claude-builder")
    private val user = Author.User("Muhammad")

    private fun thread(vararg said: Message, intent: Intent? = null) =
        CommentThread(file = "a.py", line = 1, anchorText = "x", intent = intent).also { t -> said.forEach(t::addMessage) }

    private fun agreed() = thread(Message(builder, "Rename it?"), Message.agreement(by = user, with = builder))

    private fun agentReply(read: Boolean) = thread(Message(user, "Why?"), Message(builder, "Done.")).also {
        if (read) it.markReadByUser()
    }

    @Test
    fun `a closed thread shows no signal, whatever was said or answered`() {
        val resolved = agentReply(read = false).also { it.resolve(user) }
        val orphaned = agreed().also {
            it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING)
            it.markOrphaned()
        }

        assertNull(ThreadSignal.of(resolved))
        assertNull(ThreadSignal.of(orphaned))
        assertNull(ThreadSignal.onCard(orphaned))
    }

    @Test
    fun `an Agree the agent has not answered shows a thumbs-up`() {
        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(agreed()))
    }

    @Test
    fun `an unanswered Agree shows a thumbs-up even with an earlier agent message marked unread`() {
        val t = agreed().also {
            it.markReadByUser()
            it.markUnreadByUser()
        }

        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(t))
    }

    @Test
    fun `reading a thread never hides a thumbs-up`() {
        val t = agreed().also { it.markReadByUser() }

        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(t))
    }

    @Test
    fun `an Agree the agent took on shows it is on it`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        assertEquals(ThreadSignal.AGENT_ON_IT, ThreadSignal.of(t))
    }

    @Test
    fun `an Agree the agent acknowledged shows no signal`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.ACKNOWLEDGED) }

        assertNull(ThreadSignal.of(t))
    }

    @Test
    fun `an agent reply you have not read shows your move`() {
        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.of(agentReply(read = false)))
    }

    @Test
    fun `an agent reply you read but did not answer shows no signal while the turn stays yours`() {
        val readReply = agentReply(read = true)

        assertNull(ThreadSignal.of(readReply))
        assertEquals(Turn.USER_OWES, readReply.turn())
    }

    @Test
    fun `a new agent message on a read thread brings your move back`() {
        val readReply = agentReply(read = true).also { it.addMessage(Message(builder, "One more thing.")) }

        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.of(readReply))
    }

    @Test
    fun `a thread you answered shows the agent's move`() {
        val answered = agentReply(read = true).also { it.addMessage(Message(user, "Thanks, go on.")) }

        assertEquals(ThreadSignal.AGENTS_MOVE, ThreadSignal.of(answered))
    }

    @Test
    fun `an fyi marked unread shows your move again`() {
        val fyi = thread(Message(builder, "FYI."), intent = Intent.FYI).also {
            it.markReadByUser()
            it.markUnreadByUser()
        }

        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.of(fyi))
    }

    @Test
    fun `a card shows your move whether you have read it or not`() {
        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.onCard(agentReply(read = true)))
        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.onCard(agentReply(read = false)))
    }

    @Test
    fun `a card differs from every other surface only on a read thread that is your move`() {
        val readYourMove = agentReply(read = true)
        val alike = listOf(
            agreed(),
            agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) },
            agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.ACKNOWLEDGED) },
            agentReply(read = false),
            agentReply(read = false).also { it.addMessage(Message(user, "Go on.")) },
            agentReply(read = false).also { it.resolve(user) },
        )

        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.onCard(readYourMove))
        assertNull(ThreadSignal.of(readYourMove))
        assertEquals(alike.map(ThreadSignal::of), alike.map(ThreadSignal::onCard))
    }
}
