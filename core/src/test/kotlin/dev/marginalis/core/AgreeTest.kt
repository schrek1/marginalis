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

        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(t))
        assertTrue(t.awaitsAgreementAnswer)
    }

    @Test
    fun `an agent taking the agreed change on shows it is on it, and nobody owes a word`() {
        val t = agreed()

        assertEquals(AgreementAnswer.Outcome.ANSWERED, t.answerAgreement(by = builder, answer = AgreementAnswer.WORKING))

        assertNull(t.turnFor("claude-builder"))
        assertEquals(ThreadSignal.AGENT_ON_IT, ThreadSignal.of(t))
        assertFalse(t.awaitsAgreementAnswer)
    }

    @Test
    fun `an agent acknowledging an Agree leaves the thread without a sign, open for the user to resolve`() {
        val t = agreed()

        t.answerAgreement(by = builder, answer = AgreementAnswer.ACKNOWLEDGED)

        assertNull(t.turn())
        assertNull(ThreadSignal.of(t))
        assertEquals(ThreadStatus.Open, t.status)
    }

    @Test
    fun `answering an Agree moves the thread, so an updated_after sweep sees it`() {
        val t = agreed()
        val before = t.updatedAt
        Thread.sleep(2)

        t.answerAgreement(by = builder, answer = AgreementAnswer.WORKING)

        assertTrue(t.updatedAt > before)
    }

    @Test
    fun `the next word from either side clears the agent's answer and the turns come back`() {
        val userFollowedUp = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.ACKNOWLEDGED) }
        userFollowedUp.addMessage(Message(user, "One more thing."))

        assertNull(userFollowedUp.agreementAnswer)
        assertEquals(ThreadSignal.AGENTS_MOVE, ThreadSignal.of(userFollowedUp))

        val agentReported = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }
        agentReported.addMessage(Message(builder, "Renamed."))

        assertNull(agentReported.agreementAnswer)
        assertEquals(ThreadSignal.YOUR_MOVE, ThreadSignal.of(agentReported))
    }

    @Test
    fun `a relayed GitHub comment keeps the agent's answer, as it takes no turn either`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        val relayed = Relayed(
            source = Relayed.Source.GITHUB,
            commentId = "1",
            url = "https://github.com/acme/shop/pull/42#issuecomment-1",
            name = "Alice",
            login = "alice",
        )
        t.addRelayedOnce(Message(user, "LGTM", relayed = relayed))

        assertEquals(AgreementAnswer.WORKING, t.agreementAnswer)
    }

    @Test
    fun `resolving clears the agent's answer, so a reopened thread asks for a fresh one`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        t.resolve(builder)
        t.reopen()

        assertNull(t.agreementAnswer)
        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(t))
    }

    @Test
    fun `an orphaned thread keeps its agent's answer and shows it is on it again once rescued`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        t.markOrphaned()
        assertEquals(AgreementAnswer.WORKING, t.agreementAnswer)
        t.rescueTo(line = 3, anchorText = "y")

        assertEquals(ThreadSignal.AGENT_ON_IT, ThreadSignal.of(t))
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
    fun `an answer the thread cannot take is refused and leaves no answer behind`() {
        val reviewer = Author.Agent("Claude · review", "claude-review")
        val notAgreed = thread(Message(builder, "Rename it?"))
        val toAnother = agreed()

        assertEquals(AgreementAnswer.Outcome.REFUSED, notAgreed.answerAgreement(by = builder, answer = AgreementAnswer.WORKING))
        assertEquals(AgreementAnswer.Outcome.REFUSED, toAnother.answerAgreement(by = reviewer, answer = AgreementAnswer.WORKING))
        assertNull(notAgreed.agreementAnswer)
        assertNull(toAnother.agreementAnswer)
    }

    @Test
    fun `an agent may change its answer while the Agree is still the last word`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        t.answerAgreement(by = builder, answer = AgreementAnswer.ACKNOWLEDGED)

        assertEquals(AgreementAnswer.ACKNOWLEDGED, t.agreementAnswer)
    }

    @Test
    fun `answering the same way twice does not move the thread`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }
        val answeredAt = t.updatedAt
        Thread.sleep(2)

        val outcome = t.answerAgreement(by = builder, answer = AgreementAnswer.WORKING)

        assertEquals(AgreementAnswer.Outcome.UNCHANGED, outcome)
        assertEquals(answeredAt, t.updatedAt)
    }

    private fun agreeingAgain(t: CommentThread) {
        t.addMessage(Message(builder, "Renamed it differently, then?"))
        t.addMessage(Message.agreement(by = user, with = builder))
    }

    @Test
    fun `an answer belongs to its Agree, so a follow-up hides it and a later Agree starts unanswered`() {
        val t = agreed().also { it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING) }

        t.addMessage(Message(user, "Actually, wait."))
        assertNull(t.agreementAnswer)
        agreeingAgain(t)

        assertNull(t.agreementAnswer)
        assertTrue(t.awaitsAgreementAnswer)
        assertEquals(ThreadSignal.AGREED, ThreadSignal.of(t))
    }

    @Test
    fun `answering after the user's follow-up has landed is refused and leaves nothing stored`() {
        val t = agreed().also { it.addMessage(Message(user, "Actually, wait.")) }

        assertEquals(AgreementAnswer.Outcome.REFUSED, t.answerAgreement(by = builder, answer = AgreementAnswer.WORKING))

        assertNull(t.agreementAnswer)
        assertEquals(ThreadSignal.AGENTS_MOVE, ThreadSignal.of(t))
        agreeingAgain(t)
        assertNull(t.agreementAnswer)
    }

    @Test
    fun `a resolve landing as the agent answers leaves a resolved thread with no answer`() {
        val answeredThenResolved = agreed().also {
            it.answerAgreement(by = builder, answer = AgreementAnswer.WORKING)
            it.resolve(user)
        }
        val resolvedThenAnswered = agreed().also { it.resolve(user) }

        val lateAnswer = resolvedThenAnswered.answerAgreement(by = builder, answer = AgreementAnswer.WORKING)

        assertEquals(AgreementAnswer.Outcome.REFUSED, lateAnswer)
        listOf(answeredThenResolved, resolvedThenAnswered).forEach {
            assertEquals(ThreadStatus.Resolved(user), it.status)
            assertNull(it.agreementAnswer)
        }
    }

    @Test
    fun `answers parse from their wire names, a missing one says so, and anything else teaches the two`() {
        assertEquals(Parsed.Ok(AgreementAnswer.WORKING), AgreementAnswer.parse("working"))
        assertEquals(Parsed.Ok(AgreementAnswer.ACKNOWLEDGED), AgreementAnswer.parse("Acknowledged"))
        val invalid = AgreementAnswer.parse("done")
        assertTrue(invalid is Parsed.Invalid && "working" in invalid.reason && "acknowledged" in invalid.reason)
        val missing = AgreementAnswer.parse(null)
        assertTrue(missing is Parsed.Invalid && missing.reason.startsWith("missing 'state'") && "null" !in missing.reason)
    }
}
