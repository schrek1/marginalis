package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Long threads fold to what matters, and keep folding as the conversation goes on (MARG-170).
 *
 * The agent posts a seven-message thread on a line. Read and reopened, it shows the last two messages open and the
 * rest folded to one line, a long run of them behind one link. A single message folds and opens with its arrow. As
 * new messages arrive the thread keeps only the last two open, unless the whole thread is expanded or the reader is
 * scrolled up in the middle of it. Settings can open long threads with nothing folded.
 *
 * The sandbox IDE is never the active app, so its panels mark nothing read by themselves: the robot reads through
 * the store, as an open panel does once on screen.
 */
class LongThreadsFoldTest : SandboxScenario("long_threads_fold") {

    private lateinit var thread: String

    @Test
    @Order(1)
    fun aReadLongThreadOpensFolded() = case("opens_folded") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        thread = Agent.agentAddsLineThread(FILE, 1, body(1))
        (2..7).forEach { Agent.agentReplies(thread, body(it)) }
        SandboxUi.openThreadInEditor(thread, FILE)
        val unread = openUntil(thread, (1..7).toList())
        check("an unread long thread opens every message", unread == (1..7).toList(), unread)

        SandboxUi.userReads(thread)
        settle()
        check("reading folds nothing under the reader", open(thread) == (1..7).toList(), open(thread))

        reopen(thread)
        val reopened = openUntil(thread, listOf(6, 7))
        shot("1-read-reopened")
        check("a read long thread opens with its last two messages open", reopened == listOf(6, 7), reopened)
        check("the first message folds to its opening line", SandboxUi.foldedLines(thread) == listOf("Point 1."), SandboxUi.foldedLines(thread))
        check("the four between stack behind one link", SandboxUi.stackLinks(thread) == listOf("Show 4 more messages"), SandboxUi.stackLinks(thread))
    }

    @Test
    @Order(2)
    fun aMessageFoldsToOneLineAndBack() = case("fold_one_message") {
        SandboxUi.toggleMessage(thread, "Point 7.")
        val folded = openUntil(thread, listOf(6))
        shot("2-message-folded")
        check("the arrow folds an open message", folded == listOf(6), folded)
        check("the folded message shows its opening line", "Point 7." in SandboxUi.foldedLines(thread), SandboxUi.foldedLines(thread))

        SandboxUi.toggleMessage(thread, "Point 7.")
        val opened = openUntil(thread, listOf(6, 7))
        check("the arrow opens it again", opened == listOf(6, 7), opened)
    }

    @Test
    @Order(3)
    fun newMessagesKeepTheThreadFolded() = case("keeps_folding") {
        reopen(thread)
        openUntil(thread, listOf(6, 7))

        Agent.agentReplies(thread, body(8))
        SandboxUi.userReads(thread)
        val afterAnswer = openUntil(thread, listOf(7, 8))
        check("an agent's answer folds what came before the last two", afterAnswer == listOf(7, 8), afterAnswer)

        SandboxUi.userReplies(thread, body(9))
        val afterReply = openUntil(thread, listOf(8, 9))
        shot("3-keeps-folding")
        check("your own reply folds what came before the message you answer", afterReply == listOf(8, 9), afterReply)
        val links = readUntil("the stack grows", read = { SandboxUi.stackLinks(thread) }) { it == listOf("Show 6 more messages") }
        check("the folded run grows behind its link", links == listOf("Show 6 more messages"), links)
    }

    @Test
    @Order(4)
    fun anExpandedThreadFoldsNothingAsMessagesArrive() = case("expanded_folds_nothing") {
        SandboxUi.pressPanelAction(thread, "Expand All")
        openUntil(thread, (1..9).toList())

        Agent.agentReplies(thread, body(10))
        SandboxUi.userReads(thread)
        val expanded = openUntil(thread, (1..10).toList())
        settle()
        check("with the whole thread expanded a new message folds nothing", open(thread) == (1..10).toList(), expanded)

        SandboxUi.pressPanelAction(thread, "Back to Default View")
        Agent.agentReplies(thread, body(11))
        SandboxUi.userReads(thread)
        val backToDefault = openUntil(thread, listOf(10, 11))
        check("back in the default view the next message folds the thread again", backToDefault == listOf(10, 11), backToDefault)
    }

    @Test
    @Order(5)
    fun readingInTheMiddleFoldsNothing() = case("reading_in_the_middle") {
        val tall = Agent.agentAddsLineThread(FILE, 5, longBody(1))
        (2..7).forEach { Agent.agentReplies(tall, longBody(it)) }
        SandboxUi.closeAllEditors()
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(tall, FILE)
        openUntil(tall, (1..7).toList())
        SandboxUi.userReads(tall)

        SandboxUi.scrollEditorToMessage(FILE, tall, "Point 3.")
        val before = readUntil("message 3 at the top of the view", read = { SandboxUi.threadInView(FILE, tall, "Point 3.") }) { it.messageTop in 0..80 }
        check("the thread runs past the bottom of the view", before.panelBottom > before.viewHeight, before)

        Agent.agentReplies(tall, longBody(8))
        SandboxUi.userReads(tall)
        val arrived = openUntil(tall, (1..8).toList())
        settle()
        val after = SandboxUi.threadInView(FILE, tall, "Point 3.")
        shot("4-reading-in-the-middle")
        check("while you read the middle a new message folds nothing", open(tall) == (1..8).toList(), arrived)
        check("the message you read stays where it was", after.messageTop == before.messageTop, "before $before, after $after")

        SandboxUi.scrollEditorToThreadEnd(FILE, tall)
        settle()
        Agent.agentReplies(tall, longBody(9))
        SandboxUi.userReads(tall)
        val atTheEnd = openUntil(tall, listOf(8, 9))
        shot("5-at-the-end")
        check("back at the end the next message folds the thread", atTheEnd == listOf(8, 9), atTheEnd)
    }

    @Test
    @Order(6)
    fun settingsCanOpenLongThreadsUnfolded() = case("setting_fold_nothing") {
        SandboxUi.chooseLongThreadFolding("FOLD_NOTHING")
        try {
            reopen(thread)
            val all = openUntil(thread, (1..11).toList())
            shot("6-fold-nothing")
            check("with \"fold nothing\" a read long thread opens every message", all == (1..11).toList(), all)
        } finally {
            SandboxUi.chooseLongThreadFolding("FOLD_EVERY_READ_MESSAGE")
        }
    }

    /** The numbers of the thread's open messages: an open message shows its detail line, a folded one only its opening. */
    private fun open(threadId: String): List<Int> =
        SandboxUi.panelView(threadId).texts.mapNotNull { text -> DETAIL.find(text)?.groupValues?.get(1)?.toInt() }

    private fun openUntil(threadId: String, expected: List<Int>): List<Int> =
        readUntil("the thread shows messages $expected open", read = { open(threadId) }) { it == expected }

    private fun reopen(threadId: String) {
        SandboxUi.closeAllEditors()
        settle()
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(threadId, FILE)
    }

    private companion object {
        const val FILE = "src/review_target.py"
        val DETAIL = Regex("""Detail (\d+)\.""")

        fun body(n: Int) = "Point $n.\n\nDetail $n."

        fun longBody(n: Int) = "Point $n.\n\nDetail $n.\n\n" + (1..12).joinToString("\n\n") { "Line $it of message $n." }

        /** Time for a re-render that must not fold anything to happen anyway. */
        fun settle() = Thread.sleep(2_000)
    }
}
