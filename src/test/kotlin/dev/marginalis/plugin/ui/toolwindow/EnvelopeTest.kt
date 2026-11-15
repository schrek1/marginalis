package dev.marginalis.plugin.ui.toolwindow

class EnvelopeTest : ToolWindowTestCase() {

    private fun askedAndAnswered(): String {
        sample()
        user.comment(SAMPLE, 5, "Why this cap?")
        val id = store.threads.all().single().id
        agent.reply(id, "It keeps the batch under the API limit.")
        return id
    }

    fun testAReplyYouHaveReadLosesItsEnvelopeButStaysAwaitingYou() {
        val id = askedAndAnswered()
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✉1", "✉ L5  Why this cap?"), window.rows())

        window.rebuiltBy { user.read(id) }

        assertEquals(listOf("Open  1", "src  1", "Sample.kt", "L5  Why this cap?"), window.rows())
        window.filterBy("Awaiting You")
        assertEquals(listOf("Open  1", "src  1", "Sample.kt", "L5  Why this cap?"), window.rows())
    }

    fun testAReadThreadSpeaksNoTurn() {
        val id = askedAndAnswered()
        assertTrue(window.spoken("Why this cap?"), window.spoken("Why this cap?").contains("your move"))

        window.rebuiltBy { user.read(id) }

        assertFalse(window.spoken("Why this cap?"), window.spoken("Why this cap?").contains("your move"))
    }

    fun testANewAgentMessageOnAReadThreadBringsTheEnvelopeBack() {
        val id = askedAndAnswered()
        window.rebuiltBy { user.read(id) }

        window.rebuiltBy { agent.reply(id, "One more thing.") }

        assertEquals(listOf("Sample.kt ✉1", "✉ L5  Why this cap?"), window.rows().takeLast(2))
    }

    fun testMarkingAReadQuestionUnreadBringsItsEnvelopeBackAndMarkAsReadClearsItWithoutAnAnswer() {
        val id = askedAndAnswered()
        window.rebuiltBy { user.read(id) }

        window.rebuiltBy { window.chooseFromContextMenu("Why this cap?", "Mark as Unread") }
        assertEquals(listOf("Sample.kt ✉1", "✉ L5  Why this cap?"), window.rows().takeLast(2))

        window.rebuiltBy { window.chooseFromContextMenu("Why this cap?", "Mark as Read") }
        assertEquals(listOf("Sample.kt", "L5  Why this cap?"), window.rows().takeLast(2))
        window.filterBy("Awaiting You")
        assertEquals("L5  Why this cap?", window.rows().last())
    }

    fun testAFileRowCountsOnlyTheUnreadThreadsYouOwe() {
        sample()
        val read = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")

        window.rebuiltBy { user.read(read) }

        assertEquals(listOf("Open  2", "src  2", "Sample.kt ✉1", "L5  first", "✉ L9  second"), window.rows())
    }

    fun testAnAgentsMoveKeepsItsPlaneWhetherReadOrNot() {
        sample()
        val id = agent.addOnLine(5, "body" to "Which name?")
        user.reply(id, "Oak.")
        assertEquals(listOf("Sample.kt ✈1", "✈ L5  Which name?"), window.rows().takeLast(2))

        window.rebuiltBy { window.chooseFromContextMenu("Which name?", "Mark as Unread") }

        assertEquals(1, user.thread(id).unreadByUserCount())
        assertEquals(listOf("Sample.kt ✈1", "✈ L5  Which name?"), window.rows().takeLast(2))
    }
}
