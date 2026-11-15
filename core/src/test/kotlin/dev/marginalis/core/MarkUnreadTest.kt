package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkUnreadTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Muhammad")

    private fun thread(vararg said: Message, intent: Intent? = null) =
        CommentThread(file = "a.py", line = 1, anchorText = "x", intent = intent).also { t -> said.forEach(t::addMessage) }

    @Test
    fun `marking a read thread unread brings back only its latest message from someone else`() {
        // GIVEN - the user has read the agent's two messages and replied
        val first = Message(agent, "Why this cap?")
        val latest = Message(agent, "And the retry?")
        val read = thread(first, latest, Message(user, "Looking.")).also { it.markReadByUser() }

        // WHEN - the user marks it unread
        val changed = read.markUnreadByUser()

        // THEN - the agent's latest message is new again, the older one stays read
        assertTrue(changed)
        assertEquals(1, read.unreadByUserCount())
        assertFalse(latest.readByUser)
        assertTrue(first.readByUser)
    }

    @Test
    fun `a read fyi marked unread is the user's move again`() {
        // GIVEN - a read fyi owes nothing
        val noted = thread(Message(agent, "Lovely."), intent = Intent.FYI).also { it.markReadByUser() }
        assertNull(noted.turn())

        // WHEN - the user marks it unread
        noted.markUnreadByUser()

        // THEN - it is the user's move again, as when it arrived
        assertEquals(Turn.USER_OWES, noted.turn())
    }

    @Test
    fun `a thread of only the user's own words has nothing to mark unread`() {
        val mine = thread(Message(user, "Note to self."))

        assertFalse(mine.markUnreadByUser())
        assertEquals(0, mine.unreadByUserCount())
    }

    @Test
    fun `marking unread again changes nothing, and opening it reads it as before`() {
        // GIVEN - a thread already marked unread
        val marked = thread(Message(agent, "Why this cap?")).also {
            it.markReadByUser()
            it.markUnreadByUser()
        }

        // WHEN - it is marked unread once more, then read
        val changedAgain = marked.markUnreadByUser()
        val readAgain = marked.markReadByUser()

        // THEN - the second mark was a no-op and reading clears it
        assertFalse(changedAgain)
        assertTrue(readAgain)
        assertEquals(0, marked.unreadByUserCount())
    }

    @Test
    fun `an unread mark survives a save and reload`() {
        val marked = thread(Message(agent, "Why this cap?")).also {
            it.markReadByUser()
            it.markUnreadByUser()
        }

        val reloaded = ThreadsCodec.decode(ThreadsCodec.encode(listOf(marked))).single()

        assertEquals(1, reloaded.unreadByUserCount())
    }

    @Test
    fun `marking an ordinary thread unread leaves whose move it is`() {
        val asked = thread(Message(agent, "Why this cap?")).also { it.markReadByUser() }
        val answered = thread(Message(agent, "Why this cap?"), Message(user, "Memory.")).also { it.markReadByUser() }

        asked.markUnreadByUser()
        answered.markUnreadByUser()

        assertEquals(Turn.USER_OWES, asked.turn())
        assertEquals(Turn.AGENT_OWES, answered.turn())
    }

    @Test
    fun `an fyi the user replied to stays on the reply's turn when marked unread`() {
        val replied = thread(Message(agent, "Lovely."), Message(user, "Thanks!"), intent = Intent.FYI).also { it.markReadByUser() }

        replied.markUnreadByUser()

        assertEquals(Turn.AGENT_OWES, replied.turn())
    }

    @Test
    fun `a resolved thread marked unread owes nothing`() {
        val resolved = thread(Message(agent, "Why this cap?")).also {
            it.markReadByUser()
            it.resolve(user)
        }

        assertTrue(resolved.markUnreadByUser())

        assertNull(resolved.turn())
        assertEquals(1, resolved.unreadByUserCount())
    }

    @Test
    fun `a relayed message can be the one marked unread`() {
        val relayed = Message(agent, "Please rename.", relayed = Relayed(Relayed.Source.GITHUB, "1", "https://github.com/o/r/pull/1#discussion_r1", "Mona", "octocat"))
        val conversation = thread(Message(agent, "Why this cap?"), relayed).also { it.markReadByUser() }

        conversation.markUnreadByUser()

        assertFalse(relayed.readByUser)
        assertEquals(1, conversation.unreadByUserCount())
    }

    @Test
    fun `marking unread leaves when the thread last changed alone`() {
        val read = thread(Message(agent, "Why this cap?")).also { it.markReadByUser() }
        val before = read.updatedAt

        read.markUnreadByUser()

        assertEquals(before, read.updatedAt)
    }

    @Test
    fun `a message marked unread stays marked until it is read`() {
        val latest = Message(agent, "Why this cap?")
        val read = thread(latest).also { it.markReadByUser() }

        read.markUnreadByUser()
        val markedBeforeReading = latest.markedUnreadByUser
        read.markReadByUser()

        assertTrue(markedBeforeReading)
        assertFalse(latest.markedUnreadByUser)
    }

    @Test
    fun `a message that simply arrived is unread without being marked unread`() {
        val arrived = Message(agent, "Why this cap?")

        assertFalse(arrived.readByUser)
        assertFalse(arrived.markedUnreadByUser)
    }
}
