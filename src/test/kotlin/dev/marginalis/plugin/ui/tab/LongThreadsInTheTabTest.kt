package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.ui.ThreadPanel

class LongThreadsInTheTabTest : TabInFrontTestCase() {

    private lateinit var thread: String

    override fun setUp() {
        super.setUp()
        // The thread arrives while you are away, so its card shows it unread rather than reading it message by message.
        tab.windowActive = false
        thread = agent.ask(body(1))
        (2..7).forEach { agent.reply(thread, body(it)) }
        tab.settle()
        tab.windowActive = true
    }

    fun testNothingFoldsUnderTheReaderInTheTab() {
        readElsewhere()

        assertEquals((1..7).toList(), opened(tab.panel("Point 1.")))
    }

    fun testAReadLongThreadOpensFoldedInTheTab() {
        readElsewhere()

        tab.reopen("Point 1.")

        assertEquals(listOf(6, 7), opened(tab.panel("Point 1.")))
        assertEquals(listOf("Point 1."), user.foldedRows(tab.panel("Point 1.")))
        assertEquals(listOf("Show 4 more messages"), stacks(tab.panel("Point 1.")))
    }

    fun testRepliesFoldWhatCameBeforeTheLastTwoInTheTab() {
        readElsewhere()
        tab.reopen("Point 1.")

        agent.reply(thread, body(8))
        tab.settle()
        agent.reply(thread, body(9))
        readElsewhere()

        assertEquals(listOf(8, 9), opened(tab.panel("Point 1.")))
        assertEquals(listOf("Show 6 more messages"), stacks(tab.panel("Point 1.")))
    }

    fun testReopeningACardReturnsToTheDefaultView() {
        readElsewhere()
        user.press(tab.panel("Point 1."), "Expand All")

        tab.reopen("Point 1.")

        assertEquals(listOf(6, 7), opened(tab.panel("Point 1.")))
    }

    fun testAnOpenCardTakesUpANewChoiceOnApply() {
        readElsewhere()
        tab.reopen("Point 1.")

        user.changeLongThreadSettings { openWith("Fold nothing") }
        tab.settle()

        assertEquals((1..7).toList(), opened(tab.panel("Point 1.")))
        assertEquals(emptyList<String>(), stacks(tab.panel("Point 1.")))
    }

    fun testAReferenceIntoTheStackOpensOnlyThatMessage() {
        readElsewhere()
        tab.reopen("Point 1.")
        val target = user.thread(thread).messages[3].id
        sample()
        val citing = user.open(agent.addOnLine(5, "body" to "See mg:${target.take(8)}"))

        user.followLink(citing, "mg:${target.take(8)}")

        assertEquals(listOf(4, 6, 7), opened(tab.panel("Point 1.")))
        assertEquals(emptyList<String>(), stacks(tab.panel("Point 1.")))
    }

    private fun readElsewhere() {
        val read = user.thread(thread)
        read.markReadByUser(read.messages)
        store.threads.notifyChanged(read)
        tab.settle()
    }

    private fun opened(panel: ThreadPanel): List<Int> =
        user.shownText(panel).lines().mapNotNull { DETAIL.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    private fun stacks(panel: ThreadPanel): List<String> = user.visibleLinks(panel).filter { STACK.matches(it) }

    private companion object {
        fun body(n: Int) = "Point $n.\n\nDetail $n."
        val DETAIL = Regex("""Detail (\d+)\.""")
        val STACK = Regex("""Show \d+ more messages""")
    }
}
