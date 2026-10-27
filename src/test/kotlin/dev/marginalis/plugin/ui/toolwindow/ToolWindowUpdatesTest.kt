package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.StripeBadge
import dev.marginalis.plugin.testing.DisplayOnly
import dev.marginalis.plugin.testing.NeedsADisplay
import dev.marginalis.plugin.ui.MarginalisIcons
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
class ToolWindowUpdatesTest : ToolWindowTestCase() {

    fun testTheTreeFollowsAnAgentsThreadThroughItsLife() {
        sample()
        window.outline()

        val id = window.rebuiltBy { agent.addOnLine(5, "body" to "Extract this loop.") }
        assertEquals(
            """
            Open  1
              src  1
                Sample.kt ✉1
                  ✉ L5  Extract this loop.
            """.trimIndent(),
            window.outline(),
        )

        window.rebuiltBy { user.reply(id, "Done, have a look.") }
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✈1", "✈ L5  Extract this loop."), window.rows())

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to id) }
        assertEquals(listOf("Resolved  1"), window.rows())

        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to id) }
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✈1", "✈ L5  Extract this loop."), window.rows())

        window.rebuiltBy { agent.post("comment_clear_all") }
        assertEquals(emptyList<String>(), window.rows())
        assertEquals("No margin threads yet", window.emptyText())
    }

    fun testAnAgreedThreadShowsAThumbsUpThenWhatTheAgentDidWithIt() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop?")
        window.outline()

        window.rebuiltBy { user.agree(id) }
        assertEquals(listOf("Open  1", "src  1", "Sample.kt", "👍 L5  Extract this loop?"), window.rows())
        assertTrue(window.spoken("Extract this loop?").startsWith("L5  Extract this loop?, agreed, with Claude"))

        window.rebuiltBy { agent.post("comment_agent_state", "thread_id" to id, "state" to "working") }
        assertEquals("⏳ L5  Extract this loop?", window.rows().last())

        window.rebuiltBy { agent.post("comment_agent_state", "thread_id" to id, "state" to "acknowledged") }
        assertEquals("L5  Extract this loop?", window.rows().last())
    }

    fun testAnAgreedThreadIsNotListedAsAwaitingTheAgent() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop?")
        user.agree(id)

        window.filterBy("Awaiting Agent")

        assertEquals("Nothing awaiting the agent", window.emptyText())
    }

    fun testWhatTheUserDoesInTheEditorShowsInTheTree() {
        sample()
        window.outline()

        window.rebuiltBy { user.comment(SAMPLE, 7, "Why is this here?") }
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✈1", "✈ L7  Why is this here?"), window.rows())

        val id = store.threads.all().single().id
        window.rebuiltBy { user.resolve(id) }
        assertEquals(listOf("Resolved  1"), window.rows())
        assertEquals("resolved", status(id))

        val other = window.rebuiltBy { agent.addOnLine(9, "body" to "Another one") }
        window.rebuiltBy { user.delete(other) }
        assertEquals(listOf("Resolved  1"), window.rows())
    }

    // FixMe: Known issue - #55 an orphaned thread stays listed under Open (with its turn signal gone) until some other thread changes
    fun testAThreadWhoseLineIsErasedReachesTheOrphanedSectionOnlyWhenAnotherThreadChanges() {
        sample()
        agent.addOnLine(20, "body" to "About this line")
        window.outline()

        eraseLine(20)
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✉1", "✉ L20  About this line"), window.rows())

        assertEquals("orphaned", agent.threads().single().string("status"))
        assertEquals(listOf("Open  1", "src  1", "Sample.kt", "L20  About this line"), window.rows())

        window.rebuiltBy { agent.addOnLine(3, "body" to "Something else") }
        assertEquals(
            listOf("Open  1", "src  1", "Sample.kt ✉1", "✉ L3  Something else", "Orphaned  1", "src  1", "Sample.kt", "L20  About this line"),
            window.rows(),
        )
    }

    fun testAReplyAfterARewriteRemovedAThreadsLineMovesItToTheOrphanedSection() {
        sample()
        val id = agent.addOnLine(20, "body" to "About this line")
        window.outline()
        rewrite()

        window.rebuiltBy { agent.reply(id, "Still relevant after the rewrite?") }

        assertEquals(listOf("Orphaned  1", "src  1", "Sample.kt", "L20  About this line"), window.rows())
    }

    // FixMe: Known issue - #55 a thread row keeps its old line number after the user edits above it, until a thread changes or an agent reads the margin
    fun testLineNumbersInTheTreeCatchUpWithEditsOnlyWhenAThreadChanges() {
        sample()
        user.openFile(SAMPLE)
        val id = agent.addOnLine(5, "body" to "here")
        window.outline()

        insertLinesAbove(4, 3)
        assertEquals("✉ L5  here", window.rows().last())

        window.rebuiltBy { agent.reply(id, "still here") }
        assertEquals("✉ L8  here", window.rows().last())
    }

    // FixMe: Known issue - #53 pressing Enter at the very start of a commented line leaves the thread on the new blank line instead of following its code
    fun testEnterAtTheStartOfACommentedLineLeavesTheThreadOnTheBlankLine() {
        sample()
        val id = agent.addOnLine(5, "body" to "here")
        window.outline()
        val vFile = myFixture.findFileInTempDir(SAMPLE)
        myFixture.openFileInEditor(vFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(4))

        myFixture.type("\n")
        window.rebuiltBy { agent.reply(id, "still here") }

        val thread = agent.threads().single()
        assertEquals(5, thread["line"].asInt)
        assertEquals("", thread.string("anchor_text"))
        assertEquals(lineText(5), myFixture.editor.document.text.lines()[5])
        assertEquals("✉ L5  here", window.rows().last())
    }

    @DisplayOnly
    fun testTheSelectionSurvivesAnAgentsReply() {
        sample()
        val id = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        window.click("second")
        assertEquals("✉ L9  second", window.selected())

        window.rebuiltBy { agent.reply(id, "more") }

        assertEquals("✉ L9  second", window.selected())
    }

    fun testTheSelectedThreadStaysSelectedWhenANewFileRegroupsItsFolder() {
        sample("a/b/Deep.kt")
        sample("a/Shallow.kt")
        agent.addOnLine(5, "body" to "deep", file = "a/b/Deep.kt")
        window.press("Next Step")
        assertEquals(listOf("Open  1", "a/b  1", "Deep.kt ✉1", "✉ L5  deep"), window.selectedPath())

        window.rebuiltBy { agent.addOnLine(3, "body" to "shallow", file = "a/Shallow.kt") }

        assertEquals(listOf("Open  2", "a  2", "b  1", "Deep.kt ✉1", "✉ L5  deep"), window.selectedPath())
    }

    @DisplayOnly
    fun testARefreshKeepsTheResolvedSectionAsTheUserUnfoldedIt() {
        val steps = interleaved()
        agent.post("comment_resolve", "thread_id" to steps.getValue(8))
        agent.post("comment_resolve", "thread_id" to steps.getValue(18))
        window.click("Resolved")
        window.press("Next Step")
        window.toggleFolding("shop-orders/src/main/java/com/acme/orders/api", under = "Resolved")
        assertTrue(window.isExpanded("shop-orders/src/main/java/com/acme/orders/api", under = "Resolved"))

        window.rebuiltBy { agent.reply(steps.getValue(9), "more") }

        assertEquals("L10  Step 8", window.selected())
        assertEquals(
            """
            Resolved  3
              acme/backend  3
                shop-common/src/main/java/com/acme/backend/common/order  1
                  Order.java
                    L10  Step 8
                shop-orders/src/main/java/com/acme/orders/api  2
                  OrderController.java
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testFoldsTheUserMadeSurviveAThreadChange() {
        sample()
        sample("lib/Other.kt")
        val id = agent.addOnLine(5, "body" to "first")
        val done = agent.addOnLine(9, "body" to "done", file = "lib/Other.kt")
        agent.post("comment_resolve", "thread_id" to done)
        window.toggleFolding("src")
        window.toggleFolding("Resolved")
        assertFalse(window.isExpanded("src"))
        assertTrue(window.isExpanded("Resolved"))

        window.rebuiltBy { agent.reply(id, "more") }

        assertFalse(window.isExpanded("src"))
        assertTrue(window.isExpanded("Resolved"))
    }

    private fun assertBadge(badge: StripeBadge?) {
        val expected = MarginalisIcons.toolWindow(badge, projectLive = false)
        PlatformTestUtil.waitWithEventsDispatching("the stripe never showed $badge", { window.window.shownIcon === expected }, 5)
        assertSame(expected, window.window.shownIcon)
    }

    fun testTheStripeIconWearsTheMostUrgentBadge() {
        sample()
        assertBadge(null)

        val question = agent.addOnLine(5, "body" to "Which one?")
        assertBadge(StripeBadge.AWAITING_YOU)

        val blocker = agent.addOnLine(9, "severity" to "blocker", "body" to "Must fix")
        assertBadge(StripeBadge.BLOCKER)

        agent.post("comment_resolve", "thread_id" to blocker)
        assertBadge(StripeBadge.AWAITING_YOU)

        user.reply(question, "This one.")
        assertBadge(null)
    }

    fun testTheStripeIconIsBadgedFromTheStartWhenThreadsAlreadyAwaitTheUser() {
        sample()
        agent.addOnLine(5, "body" to "Which one?")

        assertSame(MarginalisIcons.toolWindow(StripeBadge.AWAITING_YOU, projectLive = false), window.window.shownIcon)
    }

    fun testTheToolWindowShowsNoAwaitingCount() {
        sample()
        window.rebuiltBy {
            agent.addOnLine(5, "body" to "one")
            agent.addOnLine(9, "body" to "two")
        }

        assertEquals(emptyList<String>(), window.window.titles)
        assertEquals("", window.displayName)
    }

    fun testTheTitleBarFollowsAnAgentStartingAndStoppingToListen() {
        sample()
        assertEquals(emptyList<String>(), window.presence())
        assertFalse(window.isEnabled("Submit round"))
        assertFalse(window.isEnabled("Stop agents"))

        val waiting = agent.startWait()
        assertEquals(1, window.presence().size)
        assertTrue(window.isEnabled("Submit round"))
        assertTrue(window.isEnabled("Stop agents"))

        window.press("Submit round")
        agent.await(waiting)
        assertEquals(emptyList<String>(), window.presence())
        assertFalse(window.isEnabled("Submit round"))
        assertFalse(window.isEnabled("Stop agents"))
    }

    // FixMe: Known issue - #60 an agent's reply, resolve or reopen changes the thread on the HTTP server thread while the tool window reads it on the EDT
    fun testAnAgentsResolveChangesTheThreadOffTheEventThread() {
        sample()
        val id = agent.addOnLine(5, "body" to "here")
        window.outline()
        val notifiedOnEventThread = mutableListOf<Boolean>()
        store.threads.addListener { notifiedOnEventThread += ApplicationManager.getApplication().isDispatchThread }

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to id) }
        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to id) }
        window.rebuiltBy { agent.reply(id, "more") }

        assertEquals(listOf(false, false, false), notifiedOnEventThread)
        assertEquals("✉ L5  here", window.rows().last())
    }
}
