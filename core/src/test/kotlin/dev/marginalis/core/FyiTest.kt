package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FyiTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Muhammad")

    private fun fyi(vararg said: Message) =
        CommentThread(file = "a.py", line = 1, anchorText = "x", intent = Intent.FYI).also { t -> said.forEach(t::addMessage) }

    @Test
    fun `an unread fyi is the user's move`() {
        val noted = fyi(Message(agent, "Lovely."))

        assertEquals(Turn.USER_OWES, noted.turn())
        assertEquals(Turn.USER_OWES, noted.turnFor("claude-main"))
    }

    @Test
    fun `once the user has read the fyi, nothing is owed`() {
        val noted = fyi(Message(agent, "Lovely."), Message(agent, "Really."))

        noted.markReadByUser()

        assertNull(noted.turn())
        assertNull(noted.turnFor("claude-main"))
        assertNull(Turn.owed(listOf(noted)))
        assertEquals(TurnTally(user = 0, agent = 0), TurnTally.owed(listOf(noted)))
    }

    @Test
    fun `a read fyi leaves the awaiting-user set, an unread one stays in it`() {
        val store = ThreadStore()
        val read = fyi(Message(agent, "Lovely.")).also { it.markReadByUser() }
        val unread = fyi(Message(agent, "Neat."))
        listOf(read, unread).forEach(store::add)

        assertEquals(listOf(unread), store.query(awaiting = Turn.USER_OWES))
        assertEquals(listOf(read, unread), store.query(intent = Intent.FYI))
    }

    @Test
    fun `a reply turns an fyi into an ordinary conversation`() {
        val noted = fyi(Message(agent, "Lovely.")).also { it.markReadByUser() }

        noted.addMessage(Message(user, "Thanks — why this way?"))
        assertEquals(Turn.AGENT_OWES, noted.turn())

        noted.addMessage(Message(agent, "Because."))
        noted.markReadByUser()
        assertEquals(Turn.USER_OWES, noted.turn())
    }

    @Test
    fun `an agent's further word on an fyi is the user's move again, until read`() {
        val noted = fyi(Message(agent, "Lovely.")).also { it.markReadByUser() }

        noted.addMessage(Message(agent, "Also this."))
        assertEquals(Turn.USER_OWES, noted.turn())

        noted.markReadByUser()
        assertNull(noted.turn())
    }

    @Test
    fun `an fyi addressed to another agent is that agent's move until the user has read it`() {
        val noted = fyi(Message(agent, "Nice work, reviewer.", to = Addressee.Agent("claude-review")))

        assertEquals(Turn.AGENT_OWES, noted.turn())
        assertEquals(Turn.AGENT_OWES, noted.turnFor("claude-review"))
    }

    @Test
    fun `only the messages the user was shown become read`() {
        val noted = fyi(Message(agent, "Lovely."))
        val shown = noted.messages
        noted.addMessage(Message(agent, "Arrived after."))

        assertTrue(noted.markReadByUser(shown))

        assertEquals(listOf(true, false), noted.messages.map { it.readByUser })
        assertEquals(Turn.USER_OWES, noted.turn())
    }

    @Test
    fun `reading other intents changes nothing about whose move it is`() {
        val finding = CommentThread(file = "a.py", line = 1, anchorText = "x", intent = Intent.FINDING)
            .also { it.addMessage(Message(agent, "Bug.")) }

        finding.markReadByUser()

        assertEquals(Turn.USER_OWES, finding.turn())
    }

    @Test
    fun `marking read reports only news, and the user has always read their own words`() {
        val noted = fyi(Message(user, "mine"), Message(agent, "Lovely."))

        assertTrue(noted.markReadByUser())
        assertFalse(noted.markReadByUser())
        assertTrue(noted.messages.all { it.readByUser })
    }

    @Test
    fun `reading is not a change to the thread`() {
        val noted = fyi(Message(agent, "Lovely."))
        val before = noted.updatedAt

        noted.markReadByUser()

        assertEquals(before, noted.updatedAt)
    }
}
