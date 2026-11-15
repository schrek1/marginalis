package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnTest {

    private val agent = Author.Agent("Claude")
    private val user = Author.User("Muhammad")

    private fun thread(lastWord: Author) =
        CommentThread(file = "a.py", line = 1, anchorText = "x").also { it.addMessage(Message(lastWord, "…")) }

    @Test
    fun `no open threads means no one's turn — concluded and orphaned threads ask nothing`() {
        val resolved = thread(agent).also { it.resolve(user) }
        val orphaned = thread(agent).also { it.markOrphaned() }
        assertNull(Turn.owed(emptyList()))
        assertNull(Turn.owed(listOf(resolved, orphaned)))
    }

    @Test
    fun `an open thread where the agent spoke last is the user's turn`() {
        assertEquals(Turn.USER_OWES, Turn.owed(listOf(thread(agent))))
    }

    @Test
    fun `open threads where the user spoke last are the agent's turn`() {
        assertEquals(Turn.AGENT_OWES, Turn.owed(listOf(thread(user), thread(user))))
    }

    @Test
    fun `what the user owes outranks what the agent owes`() {
        assertEquals(Turn.USER_OWES, Turn.owed(listOf(thread(user), thread(agent), thread(user))))
    }

    @Test
    fun `a single thread's turn is the other party's once someone has spoken`() {
        assertEquals(Turn.USER_OWES, thread(agent).turn())
        assertEquals(Turn.AGENT_OWES, thread(user).turn())
    }

    @Test
    fun `a concluded or orphaned thread is nobody's turn`() {
        assertNull(thread(user).also { it.resolve(agent) }.turn())
        assertNull(thread(user).also { it.markOrphaned() }.turn())
    }

    @Test
    fun `the awaiting vocabulary names whose turn it is, case-insensitively`() {
        assertEquals(Turn.AGENT_OWES, parsed("agent"))
        assertEquals(Turn.USER_OWES, parsed("USER"))
        assertNull(parsed(null))
    }

    @Test
    fun `an unknown awaiting value is taught, never silently ignored`() {
        for (raw in listOf("me", "you", "human", "claude", "")) {
            val parsed = Turn.parse(raw)
            assertIs<Parsed.Invalid>(parsed, "'$raw' must be rejected")
            assertTrue(parsed.reason.contains("'agent'") && parsed.reason.contains("'user'"))
            assertTrue(parsed.reason.contains("fyi"), "the user's debt excludes a read fyi")
            assertTrue(parsed.reason.contains("not relayed"), "relays never take the turn")
            assertTrue(parsed.reason.contains("another agent"), "a message addressed elsewhere is not your debt")
        }
    }

    private val builder = Author.Agent("Claude · builder", "claude-builder")
    private val reviewer = Author.Agent("Claude · review", "claude-review")

    private fun thread(vararg said: Message) =
        CommentThread(file = "a.py", line = 1, anchorText = "x").also { t -> said.forEach(t::addMessage) }

    @Test
    fun `an unaddressed word from the user is every agent's debt`() {
        val asked = thread(Message(user, "…"))

        assertEquals(Turn.AGENT_OWES, asked.turnFor("claude-builder"))
        assertEquals(Turn.AGENT_OWES, asked.turnFor("claude-review"))
    }

    @Test
    fun `a word addressed to one agent is that agent's debt alone`() {
        val asked = thread(Message(user, "…", to = Addressee.Agent("claude-review")))

        assertEquals(Turn.AGENT_OWES, asked.turn())
        assertEquals(Turn.AGENT_OWES, asked.turnFor("claude-review"))
        assertNull(asked.turnFor("claude-builder"))
    }

    @Test
    fun `an agent addressing another agent hands the turn to that agent, not the user`() {
        val handedOver = thread(Message(user, "…"), Message(builder, "…", to = Addressee.Agent("claude-review")))

        assertEquals(Turn.AGENT_OWES, handedOver.turn())
        assertEquals(Turn.AGENT_OWES, handedOver.turnFor("claude-review"))
        assertNull(handedOver.turnFor("claude-builder"))
    }

    @Test
    fun `a word addressed to the user awaits the user, whoever wrote it and whoever asks`() {
        val toUser = thread(Message(user, "…", to = Addressee.User))

        assertEquals(Turn.USER_OWES, toUser.turn())
        assertEquals(Turn.USER_OWES, toUser.turnFor("claude-builder"))
        assertEquals(Turn.USER_OWES, thread(Message(builder, "…")).turnFor("claude-review"))
    }

    @Test
    fun `no calling identity reads the broadcast turn`() {
        val asked = thread(Message(user, "…", to = Addressee.Agent("claude-review")))

        assertEquals(asked.turn(), asked.turnFor(null))
        assertEquals(Turn.AGENT_OWES, asked.turnFor(null))
    }

    @Test
    fun `a concluded thread is nobody's debt, addressed or not`() {
        val resolved = thread(Message(user, "…", to = Addressee.Agent("claude-builder"))).also { it.resolve(user) }

        assertNull(resolved.turnFor("claude-builder"))
    }

    @Test
    fun `awaiting is computed for the calling identity`() {
        val store = ThreadStore()
        val everyones = thread(Message(user, "…"))
        val reviewers = thread(Message(user, "…", to = Addressee.Agent("claude-review")))
        val users = thread(Message(reviewer, "…"))
        listOf(everyones, reviewers, users).forEach(store::add)

        assertEquals(setOf(everyones), store.query(awaiting = Turn.AGENT_OWES, awaitingFor = "claude-builder").toSet())
        assertEquals(setOf(everyones, reviewers), store.query(awaiting = Turn.AGENT_OWES, awaitingFor = "claude-review").toSet())
        assertEquals(setOf(users), store.query(awaiting = Turn.USER_OWES, awaitingFor = "claude-builder").toSet())
        assertEquals(setOf(everyones, reviewers), store.query(awaiting = Turn.AGENT_OWES).toSet())
    }

    @Test
    fun `the store knows whether anything awaits an agent`() {
        val store = ThreadStore()
        store.add(thread(Message(user, "…", to = Addressee.Agent("claude-review"))))

        assertTrue(store.hasAwaiting(reviewer))
        assertFalse(store.hasAwaiting(builder))
    }

    private fun parsed(raw: String?): Turn? = (Turn.parse(raw) as Parsed.Ok).value
}
