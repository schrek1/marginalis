package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgreeTest {

    private val builder = Author.Agent("Claude · builder", "claude-builder")
    private val user = Author.User("Muhammad")

    private fun thread(vararg said: Message) =
        CommentThread(file = "a.py", line = 1, anchorText = "x").also { t -> said.forEach(t::addMessage) }

    @Test
    fun `the agent's latest word is the one to agree with`() {
        val proposal = Message(builder, "Rename it?")
        val t = thread(Message(user, "Thoughts?"), proposal)

        assertSame(proposal, t.agreeable())
    }

    @Test
    fun `nothing to agree with when the user spoke last, nobody spoke, or the thread is closed`() {
        assertNull(thread(Message(builder, "…"), Message(user, "…")).agreeable())
        assertNull(thread().agreeable())
        assertNull(thread(Message(builder, "…")).also { it.resolve(user) }.agreeable())
    }

    @Test
    fun `nothing to agree with when the agent handed the thread to another agent`() {
        val handedOver = thread(Message(user, "…"), Message(builder, "Over to you.", to = Addressee.Agent("claude-review")))

        assertNull(handedOver.agreeable())
    }

    @Test
    fun `an agent's word addressed to the user can be agreed with`() {
        val toUser = Message(builder, "Ready?", to = Addressee.User)

        assertSame(toUser, thread(toUser).agreeable())
    }

    @Test
    fun `a read fyi offers nothing to agree with — it is nobody's turn`() {
        val noted = CommentThread(file = "a.py", line = 1, anchorText = "x", intent = Intent.FYI)
            .also { it.addMessage(Message(builder, "Lovely.")); it.markReadByUser() }

        assertNull(noted.agreeable())
    }

    @Test
    fun `an agreement is a short user reply addressed to the agent it agrees with`() {
        val agreement = Message.agreement(by = user, with = builder)

        assertEquals(user, agreement.author)
        assertEquals("Agreed.", agreement.body)
        assertTrue(agreement.agrees)
        assertEquals(Addressee.Agent("claude-builder"), agreement.to)
    }

    @Test
    fun `agreeing passes the turn to the agent agreed with`() {
        val proposal = Message(builder, "Rename it?")
        val t = thread(proposal)

        t.addMessage(Message.agreement(by = user, with = builder))

        assertEquals(Turn.AGENT_OWES, t.turnFor("claude-builder"))
        assertNull(t.turnFor("claude-review"))
    }

    @Test
    fun `ordinary messages don't agree`() {
        assertFalse(Message(user, "Agreed.").agrees)
    }

    private fun agreed() = thread(Message(builder, "Rename it?"), Message.agreement(by = user, with = builder))

    @Test
    fun `an Agree shows a thumbs-up instead of the agent's move until the agent answers it`() {
        val t = agreed()

        assertNull(TurnSignal.of(t))
        assertEquals(AgreementSignal.AGREED, AgreementSignal.of(t))
        assertTrue(t.awaitsAgreementAnswer)
    }

    @Test
    fun `an agent taking the agreed change on shows it is on it, and nobody owes a word`() {
        val t = agreed()

        t.answerAgreement(by = builder, state = AgentState.WORKING)

        assertNull(t.turnFor("claude-builder"))
        assertNull(TurnSignal.of(t))
        assertEquals(AgreementSignal.AGENT_ON_IT, AgreementSignal.of(t))
        assertFalse(t.awaitsAgreementAnswer)
    }

    @Test
    fun `an agent acknowledging an Agree leaves the thread without a sign, open for the user to resolve`() {
        val t = agreed()

        t.answerAgreement(by = builder, state = AgentState.ACKNOWLEDGED)

        assertNull(t.turn())
        assertNull(AgreementSignal.of(t))
        assertEquals(ThreadStatus.Open, t.status)
    }

    @Test
    fun `answering an Agree moves the thread, so an updated_after sweep sees it`() {
        val t = agreed()
        val before = t.updatedAt
        Thread.sleep(2)

        t.answerAgreement(by = builder, state = AgentState.WORKING)

        assertTrue(t.updatedAt > before)
    }

    @Test
    fun `the next word from either side clears the agent's answer and the turns come back`() {
        val userFollowedUp = agreed().also { it.answerAgreement(by = builder, state = AgentState.ACKNOWLEDGED) }
        userFollowedUp.addMessage(Message(user, "One more thing."))

        assertNull(userFollowedUp.agentState)
        assertEquals(Turn.AGENT_OWES, TurnSignal.of(userFollowedUp))

        val agentReported = agreed().also { it.answerAgreement(by = builder, state = AgentState.WORKING) }
        agentReported.addMessage(Message(builder, "Renamed."))

        assertNull(agentReported.agentState)
        assertEquals(Turn.USER_OWES, TurnSignal.of(agentReported))
    }

    @Test
    fun `a relayed GitHub comment keeps the agent's answer, as it takes no turn either`() {
        val t = agreed().also { it.answerAgreement(by = builder, state = AgentState.WORKING) }

        val relayed = Relayed(
            source = Relayed.Source.GITHUB,
            commentId = "1",
            url = "https://github.com/acme/shop/pull/42#issuecomment-1",
            name = "Alice",
            login = "alice",
        )
        t.addRelayedOnce(Message(user, "LGTM", relayed = relayed))

        assertEquals(AgentState.WORKING, t.agentState)
    }

    @Test
    fun `resolving clears the agent's answer, so a reopened thread asks for a fresh one`() {
        val t = agreed().also { it.answerAgreement(by = builder, state = AgentState.WORKING) }

        t.resolve(builder)
        t.reopen()

        assertNull(t.agentState)
        assertEquals(AgreementSignal.AGREED, AgreementSignal.of(t))
    }

    @Test
    fun `only the agent agreed with can answer, and only while the Agree is the last word`() {
        val reviewer = Author.Agent("Claude · review", "claude-review")

        assertFalse(agreed().canAnswerAgreement(reviewer))
        assertTrue(agreed().canAnswerAgreement(builder))
        assertFalse(thread(Message(builder, "Rename it?")).canAnswerAgreement(builder))
        assertFalse(agreed().also { it.resolve(user) }.canAnswerAgreement(builder))
    }

    @Test
    fun `an agent may change its answer while the Agree is still the last word`() {
        val t = agreed().also { it.answerAgreement(by = builder, state = AgentState.WORKING) }

        t.answerAgreement(by = builder, state = AgentState.ACKNOWLEDGED)

        assertEquals(AgentState.ACKNOWLEDGED, t.agentState)
    }

    @Test
    fun `agent states parse from their wire names, and anything else teaches the two`() {
        assertEquals(Parsed.Ok(AgentState.WORKING), AgentState.parse("working"))
        assertEquals(Parsed.Ok(AgentState.ACKNOWLEDGED), AgentState.parse("Acknowledged"))
        val invalid = AgentState.parse("done")
        assertTrue(invalid is Parsed.Invalid && "working" in invalid.reason && "acknowledged" in invalid.reason)
    }
}
