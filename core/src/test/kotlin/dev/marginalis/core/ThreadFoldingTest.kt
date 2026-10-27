package dev.marginalis.core

import dev.marginalis.core.ThreadFolding.Level
import dev.marginalis.core.ThreadFolding.Row
import dev.marginalis.core.ThreadFolding.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThreadFoldingTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Ada")

    private fun read(body: String) = Message(agent, body, id = body).also { it.markReadByUser() }

    private fun unread(body: String) = Message(agent, body, id = body)

    private fun agreement(id: String) = Message(user, "Agreed.", id = id, agrees = true)

    private fun reads(count: Int) = (1..count).map { read("m$it") }

    private fun shape(messages: List<Message>, view: View = View(), prefs: FoldingPrefs = FoldingPrefs()): List<String> =
        ThreadFolding.rows(messages, view, prefs).map { row ->
            when (row) {
                is Row.Open -> row.message.title
                is Row.Folded -> "(${row.message.title})"
                is Row.Stack -> "stack(${row.messages.joinToString(",") { it.title }})"
            }
        }

    private val Message.title get() = body.substringBefore('\n')

    private fun long(id: String) = Message(agent, "$id\n" + "line\n".repeat(3), id = id).also { it.markReadByUser() }

    private fun shortAndLong(): List<Message> = listOf(read("m1"), long("L2"), read("m3"), long("L4"), read("m5"), read("m6"))

    private val foldLong = FoldingPrefs(atDefault = LongThreadFolding.FOLD_LONG_READ_MESSAGES)
    private val foldNothing = FoldingPrefs(atDefault = LongThreadFolding.FOLD_NOTHING)
    private val firstOpen = FoldingPrefs(keepFirstOpen = true)

    @Test
    fun `a read message folds while the last two stay open`() {
        assertEquals(listOf("(m1)", "(m2)", "m3", "m4"), shape(reads(4)))
    }

    @Test
    fun `a thread of two read messages keeps both open`() {
        assertEquals(listOf("m1", "m2"), shape(reads(2)))
    }

    @Test
    fun `an unread message stays open`() {
        val messages = listOf(read("m1"), unread("m2"), read("m3"), read("m4"), read("m5"))

        assertEquals(listOf("(m1)", "m2", "(m3)", "m4", "m5"), shape(messages))
    }

    @Test
    fun `a message the panel keeps open stays open`() {
        assertEquals(listOf("(m1)", "m2", "(m3)", "m4", "m5"), shape(reads(5), View(keptOpen = setOf("m2"))))
    }

    @Test
    fun `the message being edited stays open`() {
        assertEquals(listOf("(m1)", "m2", "(m3)", "m4", "m5"), shape(reads(5), View(editing = "m2")))
    }

    @Test
    fun `a message the user opened stays open`() {
        assertEquals(listOf("(m1)", "m2", "(m3)", "m4", "m5"), shape(reads(5), View(opened = setOf("m2"))))
    }

    private fun keptOpenAfterTwoArrive(
        keptBefore: Set<String>,
        messages: List<Message> = reads(9),
        level: Level = Level.DEFAULT,
        readingAboveTheEnd: Boolean = false,
    ) = ThreadFolding.keptOpenAfter(setOf("m8", "m9"), keptBefore, messages, level, readingAboveTheEnd)

    @Test
    fun `new messages fold back what the panel kept open before them`() {
        // GIVEN - a read thread open with its last two messages
        val shownBefore = setOf("m6", "m7")

        // WHEN - two new messages arrive
        val keptOpen = keptOpenAfterTwoArrive(shownBefore)

        // THEN - only the last two stay open, everything before them folds again
        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5,m6,m7)", "m8", "m9"), shape(reads(9), View(keptOpen = keptOpen)))
    }

    @Test
    fun `new messages fold nothing while the reader is above the end of the thread`() {
        val keptOpen = keptOpenAfterTwoArrive(setOf("m6", "m7"), readingAboveTheEnd = true)

        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5)", "m6", "m7", "m8", "m9"), shape(reads(9), View(keptOpen = keptOpen)))
    }

    @Test
    fun `new messages fold nothing while the whole thread is expanded`() {
        val keptOpen = keptOpenAfterTwoArrive(setOf("m6", "m7"), level = Level.ALL_EXPANDED)

        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5)", "m6", "m7", "m8", "m9"), shape(reads(9), View(keptOpen = keptOpen)))
    }

    @Test
    fun `new messages keep a message the user opened open`() {
        val keptOpen = keptOpenAfterTwoArrive(setOf("m6", "m7"))

        assertEquals(
            listOf("(m1)", "m2", "stack(m3,m4,m5,m6,m7)", "m8", "m9"),
            shape(reads(9), View(keptOpen = keptOpen, opened = setOf("m2"))),
        )
    }

    @Test
    fun `a message still unread when new ones arrive does not fold once it is read`() {
        // GIVEN - message 4 is unread, so the panel shows it open, when two new messages arrive
        val unreadFour = reads(9).toMutableList().apply { this[3] = unread("m4") }
        val keptOpen = keptOpenAfterTwoArrive(setOf("m4", "m6", "m7"), messages = unreadFour)

        // WHEN - the reader reads it
        val readFour = reads(9)

        // THEN - it stays open under the reader, while the read ones before the last two fold
        assertEquals(listOf("(m1)", "(m2)", "(m3)", "m4", "(m5)", "(m6)", "(m7)", "m8", "m9"), shape(readFour, View(keptOpen = keptOpen)))
    }

    @Test
    fun `a message the user folded folds even when it would stay open`() {
        val messages = listOf(read("m1"), unread("m2"), read("m3"))

        assertEquals(listOf("(m1)", "(m2)", "(m3)"), shape(messages, View(folded = setOf("m2", "m3"))))
    }

    @Test
    fun `an agreement never folds and is not one of the last two`() {
        val messages = reads(3) + agreement("a1")

        assertEquals(listOf("(m1)", "m2", "m3", "Agreed."), shape(messages))
    }

    @Test
    fun `an agreement the user folded still shows`() {
        val messages = reads(3) + agreement("a1")

        assertEquals(listOf("(m1)", "m2", "m3", "Agreed."), shape(messages, View(folded = setOf("a1"))))
    }

    @Test
    fun `three folded messages in a row stay as folded rows`() {
        val messages = listOf(unread("m1")) + reads(6).drop(1)

        assertEquals(listOf("m1", "(m2)", "(m3)", "(m4)", "m5", "m6"), shape(messages))
    }

    @Test
    fun `more than three folded messages in a row stack behind one row`() {
        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5)", "m6", "m7"), shape(reads(7)))
    }

    @Test
    fun `the first message never goes into a stack`() {
        assertEquals(listOf("(m1)", "(m2)", "(m3)", "(m4)", "m5", "m6"), shape(reads(6)))
    }

    @Test
    fun `an open message splits a run into runs that stack on their own`() {
        val messages = reads(11)

        assertEquals(
            listOf("(m1)", "stack(m2,m3,m4,m5)", "m6", "(m7)", "(m8)", "(m9)", "m10", "m11"),
            shape(messages, View(keptOpen = setOf("m6"))),
        )
    }

    @Test
    fun `an agreement breaks a run of folded messages`() {
        val messages = reads(4) + agreement("a1") + reads(9).drop(4)

        assertEquals(
            listOf("(m1)", "(m2)", "(m3)", "(m4)", "Agreed.", "(m5)", "(m6)", "(m7)", "m8", "m9"),
            shape(messages),
        )
    }

    @Test
    fun `a revealed stack shows its messages as folded rows`() {
        assertEquals(
            listOf("(m1)", "(m2)", "(m3)", "(m4)", "(m5)", "m6", "m7"),
            shape(reads(7), View(revealed = setOf("m2", "m3", "m4", "m5"))),
        )
    }

    @Test
    fun `messages the user folded never stack`() {
        val folded = setOf("m1", "m2", "m3", "m4", "m5", "m6")

        assertEquals(listOf("(m1)", "(m2)", "(m3)", "(m4)", "(m5)", "(m6)"), shape(reads(6), View(folded = folded)))
    }

    @Test
    fun `a message the user folded stays in view and splits the run around it`() {
        assertEquals(
            listOf("(m1)", "(m2)", "(m3)", "(m4)", "(m5)", "(m6)", "(m7)", "(m8)", "m9", "m10"),
            shape(reads(10), View(folded = setOf("m5"))),
        )
    }

    @Test
    fun `opening a message out of a stack re-stacks only a remaining run of more than three`() {
        val messages = reads(10)

        assertEquals(
            listOf("(m1)", "(m2)", "m3", "stack(m4,m5,m6,m7,m8)", "m9", "m10"),
            shape(messages, View(opened = setOf("m3"))),
        )
    }

    @Test
    fun `all folded folds every message, unread and kept open included`() {
        val messages = listOf(read("m1"), unread("m2"), read("m3"))

        assertEquals(listOf("(m1)", "(m2)", "(m3)"), shape(messages, View(level = Level.ALL_FOLDED, keptOpen = setOf("m3"))))
    }

    @Test
    fun `all folded keeps the message being edited open`() {
        assertEquals(listOf("(m1)", "m2", "(m3)"), shape(reads(3), View(level = Level.ALL_FOLDED, editing = "m2")))
    }

    @Test
    fun `all folded stacks long runs`() {
        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5,m6)"), shape(reads(6), View(level = Level.ALL_FOLDED)))
    }

    @Test
    fun `all folded still opens a message the user opened`() {
        assertEquals(listOf("(m1)", "m2", "(m3)"), shape(reads(3), View(level = Level.ALL_FOLDED, opened = setOf("m2"))))
    }

    @Test
    fun `all expanded opens every message`() {
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), shape(reads(6), View(level = Level.ALL_EXPANDED)))
    }

    @Test
    fun `all expanded still folds a message the user folded, never stacking`() {
        val folded = setOf("m1", "m2", "m3", "m4", "m5")

        assertEquals(listOf("(m1)", "(m2)", "(m3)", "(m4)", "(m5)", "m6"), shape(reads(6), View(level = Level.ALL_EXPANDED, folded = folded)))
    }

    @Test
    fun `folding steps from all expanded to default to all folded`() {
        assertEquals(Level.DEFAULT, Level.ALL_EXPANDED.folded())
        assertEquals(Level.ALL_FOLDED, Level.DEFAULT.folded())
        assertNull(Level.ALL_FOLDED.folded())
    }

    @Test
    fun `expanding steps from all folded to default to all expanded`() {
        assertEquals(Level.DEFAULT, Level.ALL_FOLDED.expanded())
        assertEquals(Level.ALL_EXPANDED, Level.DEFAULT.expanded())
        assertNull(Level.ALL_EXPANDED.expanded())
    }

    @Test
    fun `by default every read message folds, short or long, the first included`() {
        assertEquals(listOf("(m1)", "(L2)", "(m3)", "(L4)", "m5", "m6"), shape(shortAndLong(), prefs = FoldingPrefs()))
    }

    @Test
    fun `folding long read messages leaves short read messages open`() {
        assertEquals(listOf("m1", "(L2)", "m3", "(L4)", "m5", "m6"), shape(shortAndLong(), prefs = foldLong))
    }

    @Test
    fun `folding long read messages keeps a long unread message open`() {
        val messages = listOf(Message(agent, "L1\n" + "line\n".repeat(3), id = "L1"), read("m2"), read("m3"))

        assertEquals(listOf("L1", "m2", "m3"), shape(messages, prefs = foldLong))
    }

    @Test
    fun `folding long read messages keeps a long last message open`() {
        val messages = listOf(read("m1"), long("L2"), long("L3"))

        assertEquals(listOf("m1", "L2", "L3"), shape(messages, prefs = foldLong))
    }

    @Test
    fun `folding long read messages stacks a run of more than three long ones`() {
        val messages = listOf(long("L1"), long("L2"), long("L3"), long("L4"), long("L5"), read("m6"), read("m7"))

        assertEquals(listOf("(L1)", "stack(L2,L3,L4,L5)", "m6", "m7"), shape(messages, prefs = foldLong))
    }

    @Test
    fun `folding long read messages keeps the one being edited and one the user opened open`() {
        assertEquals(listOf("m1", "L2", "m3", "L4", "m5", "m6"), shape(shortAndLong(), View(editing = "L2", opened = setOf("L4")), foldLong))
    }

    @Test
    fun `keeping the first message open leaves a long first message open while long ones fold`() {
        val messages = listOf(long("L1"), read("m2"), long("L3"), read("m4"), read("m5"))

        assertEquals(listOf("L1", "m2", "(L3)", "m4", "m5"), shape(messages, prefs = foldLong.copy(keepFirstOpen = true)))
    }

    @Test
    fun `keeping the first message open still folds it when the user folds it while nothing folds`() {
        assertEquals(listOf("(m1)", "m2", "m3", "m4"), shape(reads(4), View(folded = setOf("m1")), foldNothing.copy(keepFirstOpen = true)))
    }

    @Test
    fun `folding nothing leaves every read message open`() {
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5", "m6"), shape(reads(6), prefs = foldNothing))
    }

    @Test
    fun `folding nothing still folds a message the user folded`() {
        assertEquals(listOf("m1", "(m2)", "m3", "m4"), shape(reads(4), View(folded = setOf("m2")), foldNothing))
    }

    @Test
    fun `folding nothing still folds everything when all are folded`() {
        assertEquals(listOf("(m1)", "stack(m2,m3,m4,m5,m6)"), shape(reads(6), View(level = Level.ALL_FOLDED), foldNothing))
    }

    @Test
    fun `keeping the first message open leaves it open among folded read messages`() {
        assertEquals(listOf("m1", "stack(m2,m3,m4,m5)", "m6", "m7"), shape(reads(7), prefs = firstOpen))
    }

    @Test
    fun `keeping the first message open still folds it when all are folded`() {
        assertEquals(listOf("(m1)", "(m2)", "(m3)"), shape(reads(3), View(level = Level.ALL_FOLDED), firstOpen))
    }

    @Test
    fun `keeping the first message open still folds it when the user folds it`() {
        assertEquals(listOf("(m1)", "(m2)", "m3", "m4"), shape(reads(4), View(folded = setOf("m1")), firstOpen))
    }

    @Test
    fun `an unknown stored default view falls back to folding every read message`() {
        assertEquals(LongThreadFolding.FOLD_EVERY_READ_MESSAGE, LongThreadFolding.fromStored("sideways"))
        LongThreadFolding.entries.forEach { assertEquals(it, LongThreadFolding.fromStored(it.stored)) }
    }

    @Test
    fun `a body of three lines is not long`() {
        assertFalse(ThreadFolding.isLong("one\ntwo\nthree"))
    }

    @Test
    fun `a body of four lines is long`() {
        assertTrue(ThreadFolding.isLong("one\ntwo\nthree\nfour"))
    }

    @Test
    fun `blank lines do not count`() {
        assertFalse(ThreadFolding.isLong("one\n\n  \ntwo\n\nthree\n"))
    }

    @Test
    fun `a line counts once per started eighty characters`() {
        assertFalse(ThreadFolding.isLong("x".repeat(240)))
        assertTrue(ThreadFolding.isLong("x".repeat(241)))
        assertFalse(ThreadFolding.isLong("x".repeat(80) + "\n" + "y".repeat(80) + "\n" + "z".repeat(80)))
        assertTrue(ThreadFolding.isLong("x".repeat(81) + "\nshort\nshort"))
    }

    @Test
    fun `a paragraph of two hundred characters is not long but one of two hundred and fifty is`() {
        assertFalse(ThreadFolding.isLong("w".repeat(200)))
        assertTrue(ThreadFolding.isLong("w".repeat(250)))
    }
}
