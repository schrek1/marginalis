package dev.marginalis.core

import dev.marginalis.core.HistoryShortener.Row
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryShortenerTest {

    private val agent = Author.Agent("Claude", "claude-main")

    private fun read(body: String) = Message(agent, body).also { it.markReadByUser() }

    private fun unread(body: String) = Message(agent, body)

    private fun shape(rows: List<Row>): List<String> = rows.map { row ->
        when (row) {
            is Row.Shown -> row.message.body
            is Row.Hidden -> "hidden(${row.messages.size})"
        }
    }

    @Test
    fun `a thread of four messages or fewer shows them all`() {
        val messages = (1..4).map { read("m$it") }

        assertEquals(listOf("m1", "m2", "m3", "m4"), shape(HistoryShortener.rows(messages, fullHistory = false)))
    }

    @Test
    fun `a longer thread shows the first message and the last two, the read ones between hidden behind one row`() {
        val messages = (1..7).map { read("m$it") }

        assertEquals(listOf("m1", "hidden(4)", "m6", "m7"), shape(HistoryShortener.rows(messages, fullHistory = false)))
    }

    @Test
    fun `an unread message in the middle is never hidden and splits the hidden run around it`() {
        val messages = listOf(read("m1"), read("m2"), unread("m3"), read("m4"), read("m5"), read("m6"), read("m7"))

        assertEquals(
            listOf("m1", "hidden(1)", "m3", "hidden(2)", "m6", "m7"),
            shape(HistoryShortener.rows(messages, fullHistory = false)),
        )
    }

    @Test
    fun `a message the caller keeps, such as the one being edited, is never hidden`() {
        val messages = (1..6).map { read("m$it") }
        val editing = messages[2]

        val rows = HistoryShortener.rows(messages, fullHistory = false, keep = { it === editing })

        assertEquals(listOf("m1", "hidden(1)", "m3", "hidden(1)", "m5", "m6"), shape(rows))
    }

    @Test
    fun `a thread showing its full history shows every message`() {
        val messages = (1..7).map { read("m$it") }

        assertEquals(messages.map { it.body }, shape(HistoryShortener.rows(messages, fullHistory = true)))
    }
}
