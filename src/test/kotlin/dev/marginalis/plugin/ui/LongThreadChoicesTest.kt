package dev.marginalis.plugin.ui

import dev.marginalis.plugin.testing.LongThreadSettings
import dev.marginalis.plugin.transport.HttpContractTestCase

class LongThreadChoicesTest : HttpContractTestCase() {

    private lateinit var thread: String

    override fun setUp() {
        super.setUp()
        sample()
        thread = agent.addOnLine(1, "body" to body(1))
        (2..7).forEach { agent.reply(thread, if (it in LONG) long(it) else body(it)) }
    }

    override fun tearDown() {
        try {
            LongThreadSettings.restoreDefaults()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testFoldingLongReadMessagesLeavesShortOnesOpen() {
        user.changeLongThreadSettings { openWith(FOLD_LONG) }
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf(1, 3, 5, 6, 7), opened(panel))
        assertEquals(listOf(2, 4), folded(panel))
    }

    fun testFoldingLongReadMessagesKeepsALongUnreadOneOpen() {
        user.changeLongThreadSettings { openWith(FOLD_LONG) }
        readElsewhere(except = 4)

        val panel = user.open(thread)

        assertEquals(listOf(2), folded(panel))
    }

    fun testFoldingNothingOpensEveryReadMessageWithoutStacks() {
        user.changeLongThreadSettings { openWith(FOLD_NOTHING) }
        user.read(thread)

        val panel = user.open(thread)

        assertEquals((1..7).toList(), opened(panel))
        assertEquals(emptyList<String>(), stacks(panel))
    }

    fun testFoldingNothingStillFoldsOneMessageOnRequest() {
        user.changeLongThreadSettings { openWith(FOLD_NOTHING) }
        user.read(thread)
        val panel = user.open(thread)

        user.fold(panel, "Point 3.")

        assertEquals(listOf(3), folded(panel))
    }

    fun testFoldingNothingStillFoldsAllOnRequest() {
        user.changeLongThreadSettings { openWith(FOLD_NOTHING) }
        user.read(thread)
        val panel = user.open(thread)

        user.press(panel, "Fold All")

        assertEquals(emptyList<Int>(), opened(panel))
        assertEquals(listOf("Show 6 more messages"), stacks(panel))
    }

    fun testTheFirstMessageCanStayOpen() {
        user.changeLongThreadSettings { alwaysShowTheFirstMessage(true) }
        user.read(thread)

        val panel = user.open(thread)

        assertEquals(listOf(1, 6, 7), opened(panel))
        assertEquals(listOf("Show 4 more messages"), stacks(panel))
    }

    fun testTheFirstMessageKeptOpenStillFoldsWhenAllFold() {
        user.changeLongThreadSettings { alwaysShowTheFirstMessage(true) }
        user.read(thread)
        val panel = user.open(thread)

        user.press(panel, "Fold All")

        assertEquals(listOf(1), folded(panel))
    }

    fun testAnOpenThreadTakesUpANewChoiceOnApply() {
        user.read(thread)
        val panel = user.open(thread)

        user.changeLongThreadSettings { openWith(FOLD_NOTHING) }

        assertEquals((1..7).toList(), opened(panel))
    }

    fun testAnOpenThreadFoldsWhatANewChoiceFolds() {
        user.changeLongThreadSettings { openWith(FOLD_NOTHING) }
        user.read(thread)
        val panel = user.open(thread)

        user.changeLongThreadSettings { openWith(FOLD_EVERY) }

        assertEquals(listOf(6, 7), opened(panel))
    }

    fun testANewChoiceKeepsTheMessagesYouOpened() {
        user.read(thread)
        val panel = user.open(thread)
        user.clickLink(panel, "Show 4 more messages")
        user.expand(panel, "Point 3.")

        user.changeLongThreadSettings { alwaysShowTheFirstMessage(true) }

        assertEquals(listOf(1, 3, 6, 7), opened(panel))
        assertEquals(listOf(2, 4, 5), folded(panel))
    }

    fun testANewChoiceKeepsMessagesReadWhileOpenOpen() {
        val panel = user.open(thread)
        readElsewhere()

        user.changeLongThreadSettings { openWith(FOLD_LONG) }

        assertEquals((1..7).toList(), opened(panel))
    }

    fun testANewChoiceLeavesAThreadShownInFullAsItIsUntilItStepsBackToDefault() {
        user.read(thread)
        val panel = user.open(thread)
        user.press(panel, "Expand All")

        user.changeLongThreadSettings { openWith(FOLD_LONG) }

        assertEquals((1..7).toList(), opened(panel))

        user.press(panel, "Back to Default View")

        assertEquals(listOf(1, 3, 5, 6, 7), opened(panel))
        assertEquals(listOf(2, 4), folded(panel))
    }

    private fun readElsewhere(except: Int? = null) {
        val read = user.thread(thread)
        read.markReadByUser(read.messages.filterIndexed { index, _ -> index + 1 != except })
        store.threads.notifyChanged(read)
    }

    private fun opened(panel: ThreadPanel): List<Int> =
        user.shownText(panel).lines().mapNotNull { DETAIL.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    private fun folded(panel: ThreadPanel): List<Int> =
        user.foldedRows(panel).mapNotNull { POINT.matchEntire(it)?.groupValues?.get(1)?.toInt() }

    private fun stacks(panel: ThreadPanel): List<String> = user.visibleLinks(panel).filter { STACK.matches(it) }

    private companion object {
        val LONG = setOf(2, 4)
        fun body(n: Int) = "Point $n.\n\nDetail $n."
        fun long(n: Int) = "Point $n.\n\nDetail $n.\n\nA third line.\n\nAnd a fourth."
        val DETAIL = Regex("""Detail (\d+)\.""")
        val POINT = Regex("""Point (\d+)\.""")
        val STACK = Regex("""Show \d+ more messages""")
        const val FOLD_EVERY = "Fold every read message"
        const val FOLD_LONG = "Fold long read messages"
        const val FOLD_NOTHING = "Fold nothing"
    }
}
