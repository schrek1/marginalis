package dev.marginalis.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveThreadTest {

    private val user = Author.User("Muhammad")
    private val claude = Author.Agent("Claude", "claude-main")
    private val codex = Author.Agent("Codex", "codex")
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")

    private fun thread(vararg said: Message) =
        CommentThread(file = "a.py", line = 1, anchorText = "x").also { t -> said.forEach(t::addMessage) }

    @Test
    fun `live ends when the thread is resolved, orphaned or deleted`() {
        assertFalse(LiveThread.isOver(thread()))
        assertTrue(LiveThread.isOver(thread().also { it.resolve(user) }))
        assertTrue(LiveThread.isOver(thread().also { it.markOrphaned() }))
        assertTrue(LiveThread.isOver(null))
    }

    @Test
    fun `without the project default only a thread switched on is live`() {
        val t = thread()

        assertFalse(LiveThread.isLive(t, byDefault = false, switchedOn = emptySet(), switchedOff = emptySet()))
        assertTrue(LiveThread.isLive(t, byDefault = false, switchedOn = setOf(t.id), switchedOff = emptySet()))
    }

    @Test
    fun `with the project default every open thread is live unless switched off`() {
        val t = thread()

        assertTrue(LiveThread.isLive(t, byDefault = true, switchedOn = emptySet(), switchedOff = emptySet()))
        assertFalse(LiveThread.isLive(t, byDefault = true, switchedOn = emptySet(), switchedOff = setOf(t.id)))
        assertFalse(LiveThread.isLive(t.also { it.resolve(user) }, byDefault = true, switchedOn = emptySet(), switchedOff = emptySet()))
    }

    @Test
    fun `an open thread holds news for an agent until that agent has seen every message`() {
        val followUp = Message(user, "And another thing")
        val t = thread(Message(claude, "Done."), followUp)

        assertTrue(LiveThread.hasUnseen(t, claude.receiptKey))
        followUp.markSeenBy(claude.receiptKey)
        assertFalse(LiveThread.hasUnseen(t, claude.receiptKey))
    }

    @Test
    fun `a closed or deleted thread holds no news`() {
        assertFalse(LiveThread.hasUnseen(thread(Message(user, "?")).also { it.resolve(user) }, claude.receiptKey))
        assertFalse(LiveThread.hasUnseen(null, claude.receiptKey))
    }

    @Test
    fun `an agent is working from its live wake until it replies after it`() {
        val delivered = t0.plusSeconds(10)
        val before = listOf(Message(user, "Go", createdAt = t0), Message(claude, "Earlier", createdAt = t0.plusSeconds(5)))

        assertTrue(LiveThread.isWorking(before, claude.receiptKey, delivered))
        assertTrue(LiveThread.isWorking(before + Message(codex, "Me too", createdAt = t0.plusSeconds(20)), claude.receiptKey, delivered))
        assertFalse(LiveThread.isWorking(before + Message(claude, "Done", createdAt = t0.plusSeconds(20)), claude.receiptKey, delivered))
    }

    @Test
    fun `going live delivers what the agent has not seen, stamped with the thread's last change`() {
        val store = ThreadStore()
        val handBack = HandBack.over(store) { t0 }
        val t = thread(Message(claude, "Done."), Message(user, "One more thing")).also(store::add)
        val waited = handBack.await(since = t0.minusSeconds(60), timeout = Duration.ofHours(1), agent = claude)

        LiveThread.goLive(handBack, t, claude.receiptKey)

        assertEquals(Wake.Live(t.updatedAt, listOf(t.id)), waited.getNow(null))
    }

    @Test
    fun `going live with nothing unseen wakes nobody`() {
        val store = ThreadStore()
        val handBack = HandBack.over(store) { t0 }
        val t = thread(Message(user, "Thoughts?"), Message(claude, "Done.")).also(store::add)
        t.messages.forEach { it.markSeenBy(claude.receiptKey) }
        val waited = handBack.await(since = t0.minusSeconds(60), timeout = Duration.ofHours(1), agent = claude)

        LiveThread.goLive(handBack, t, claude.receiptKey)
        LiveThread.goLive(handBack, t, agentKey = null)

        assertFalse(waited.isDone)
    }

    @Test
    fun `a live submit wakes the agent only when the thread now waits on it`() {
        val store = ThreadStore()
        val handBack = HandBack.over(store) { t0 }
        val t = thread(Message(claude, "Done."), Message(user, "Over to Codex", to = Addressee.Agent("codex"))).also(store::add)
        val waited = handBack.await(since = t0.minusSeconds(60), timeout = Duration.ofHours(1), agent = claude)

        LiveThread.submit(handBack, t, claude.receiptKey)
        assertFalse(waited.isDone)

        t.addMessage(Message(user, "Actually, Claude", to = Addressee.Agent("claude-main")))
        LiveThread.submit(handBack, t, claude.receiptKey)
        assertEquals(Wake.Live(t.updatedAt, listOf(t.id)), waited.getNow(null))
    }

    @Test
    fun `without a delivered live wake nobody is working`() {
        assertFalse(LiveThread.isWorking(listOf(Message(user, "Go")), claude.receiptKey, deliveredAt = null))
    }
}
