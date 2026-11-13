package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadMarkTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Muhammad")

    private fun thread(vararg said: Message) =
        CommentThread(file = "a.py", line = 1, anchorText = "x").also { t -> said.forEach(t::addMessage) }

    private fun read(vararg said: Message) = thread(*said).also { it.markReadByUser() }

    @Test
    fun `anything new in the selection marks it all read`() {
        val selection = listOf(read(Message(agent, "Seen.")), thread(Message(agent, "New.")))

        assertEquals(ReadMark.READ, ReadMark.of(selection))
    }

    @Test
    fun `a selection with nothing new marks it unread`() {
        val selection = listOf(read(Message(agent, "Seen.")), read(Message(agent, "Also seen.")))

        assertEquals(ReadMark.UNREAD, ReadMark.of(selection))
    }

    @Test
    fun `threads of only your own words offer no read mark`() {
        assertNull(ReadMark.of(listOf(thread(Message(user, "Note to self.")), thread(Message(user, "Another.")))))
    }

    @Test
    fun `one thread someone else wrote in is enough to offer a mark`() {
        val selection = listOf(thread(Message(user, "Note to self.")), read(Message(agent, "Seen.")))

        assertEquals(ReadMark.UNREAD, ReadMark.of(selection))
    }

    @Test
    fun `applying the mark reads or unreads every thread and says which changed`() {
        val seen = read(Message(agent, "Seen."))
        val fresh = thread(Message(agent, "New."))

        val changed = listOf(seen, fresh).filter(ReadMark.READ::applyTo)

        assertEquals(listOf(fresh), changed)
        assertEquals(0, fresh.unreadByUserCount())
        assertTrue(ReadMark.UNREAD.applyTo(seen))
        assertEquals(1, seen.unreadByUserCount())
    }
}
