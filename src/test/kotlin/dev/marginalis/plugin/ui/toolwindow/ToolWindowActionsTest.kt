package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.testing.MarginToolWindow.Companion.NO
import dev.marginalis.plugin.testing.MarginToolWindow.Companion.YES
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.tab.MarginFile
import dev.marginalis.plugin.ui.tab.ProjectTabState

class ToolWindowActionsTest : ToolWindowTestCase() {

    fun testTheTitleBarOffersTheMarginsActionsInOrder() {
        assertEquals(
            listOf(
                "Comment on Project", "First Step", "Previous Occurrence", "Next Occurrence", "Last Step",
                "Expand", "Collapse", "Filter", "Select Opened Thread", "Guided Layout",
                "Submit round", "Live by default", "Stop agents", "Resolve All", "Delete All",
            ),
            window.toolbar(),
        )
    }

    fun testTheFilterNarrowsEverySectionAndSaysWhenNothingMatches() {
        sample()
        agent.addOnLine(3, "severity" to "blocker", "body" to "blocking")
        val answered = agent.addOnLine(6, "intent" to "question", "body" to "a question")
        agent.addOnLine(9, "intent" to "finding", "body" to "a finding")
        agent.addOnLine(12, "order" to 1, "intent" to "guidance", "body" to "a guided step")
        user.reply(answered, "My answer.")

        assertEquals(
            mapOf(
                "All" to true, "Blockers Only" to false, "Awaiting You" to false, "Awaiting Agent" to false,
                "Findings" to false, "Guidance" to false, "Questions" to false, "FYI" to false,
            ),
            window.filters(),
        )

        window.filterBy("Blockers Only")
        assertEquals(listOf("Open  1  ·  1 blocker", "src  1", "Sample.kt ✉1", "✉ L3  blocking"), window.rows())
        assertEquals(mapOf("Blockers Only" to true), window.filters().filterValues { it })

        window.filterBy("Awaiting Agent")
        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✈1", "✈ L6  a question"), window.rows())

        window.filterBy("Awaiting You")
        assertEquals(listOf("Guided  1", "✉ (1/1)  Sample.kt L12  a guided step", "Open  3  ·  1 blocker", "src  3", "Sample.kt ✉3"), window.rows().take(5))

        window.filterBy("Guidance")
        assertEquals(listOf("Guided  1", "✉ (1/1)  Sample.kt L12  a guided step", "Open  1", "src  1", "Sample.kt ✉1", "✉ L12  a guided step"), window.rows())

        window.filterBy("Findings")
        assertEquals("✉ L9  a finding", window.rows().last())

        window.filterBy("Questions")
        assertEquals("✈ L6  a question", window.rows().last())

        window.filterBy("FYI")
        assertEquals(emptyList<String>(), window.rows())
        assertEquals("No FYIs", window.emptyText())
        window.rebuiltBy { agent.addOnLine(15, "intent" to "fyi", "body" to "for your information") }
        assertEquals("✉ L15  for your information", window.rows().last())
        assertEquals(4, window.rows().size)

        window.filterBy("All")
        assertEquals("Open  5  ·  1 blocker", window.rows()[2])
    }

    fun testEachFilterHasItsOwnEmptyMessage() {
        val empty = mapOf(
            "All" to "No margin threads yet", "Blockers Only" to "No blockers", "Awaiting You" to "Nothing awaiting you",
            "Awaiting Agent" to "Nothing awaiting the agent", "Findings" to "No findings", "Guidance" to "No guidance",
            "Questions" to "No questions", "FYI" to "No FYIs",
        )

        empty.forEach { (filter, message) ->
            window.filterBy(filter)
            assertEquals(message, window.emptyText())
        }
    }

    fun testUnselectingTheActiveFilterKeepsIt() {
        window.filterBy("Blockers Only")

        window.unselectFilter("Blockers Only")

        assertEquals(mapOf("Blockers Only" to true), window.filters().filterValues { it })
    }

    fun testAFilterStaysOnWhileThreadsArrive() {
        sample()
        window.filterBy("Blockers Only")

        window.rebuiltBy { agent.addOnLine(3, "body" to "ordinary") }
        window.rebuiltBy { agent.addOnLine(6, "severity" to "blocker", "body" to "blocking") }

        assertEquals(listOf("Open  1  ·  1 blocker", "src  1", "Sample.kt ✉1", "✉ L6  blocking"), window.rows())
    }

    fun testResolveAllAsksFirstWarnsAboutBlockersAndResolvesForTheAgentToSee() {
        sample()
        val ordinary = agent.addOnLine(3, "body" to "ordinary")
        val blocker = agent.addOnLine(6, "severity" to "blocker", "body" to "blocking")
        val done = agent.addOnLine(9, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)

        val declined = window.press("Resolve All", answer = NO)
        assertEquals(
            "Resolve all 2 open thread(s)? 1 of them are blockers — resolve only if their outcomes genuinely landed. " +
                "Their gutter markers will be removed; the threads remain in the Resolved log.",
            declined,
        )
        assertEquals("open", status(ordinary))

        window.rebuiltBy { window.press("Resolve All", answer = YES) }

        assertEquals("resolved", status(ordinary))
        assertEquals("resolved", status(blocker))
        assertEquals(listOf("Resolved  3"), window.rows())
        assertFalse(window.isEnabled("Resolve All"))
    }

    fun testResolveAllWithoutBlockersAsksPlainly() {
        sample()
        agent.addOnLine(3, "body" to "ordinary")
        agent.add("body" to "project wide")

        val asked = window.press("Resolve All", answer = YES)

        assertEquals("Resolve all 2 open thread(s)? Their gutter markers will be removed; the threads remain in the Resolved log.", asked)
        assertEquals(listOf("resolved", "resolved"), agent.threads().map { it.string("status") })
    }

    fun testResolveAllCountsOrphansAsOpen() {
        sample()
        agent.addOnLine(20, "body" to "lost")
        eraseLine(20)
        agent.threads()

        assertTrue(window.isEnabled("Resolve All"))
        window.press("Resolve All", answer = YES)

        assertEquals("resolved", agent.threads().single().string("status"))
    }

    fun testDeleteAllAsksFirstAndEmptiesTheMarginForTheAgentToo() {
        sample()
        assertFalse(window.isEnabled("Delete All"))
        val kept = agent.addOnLine(3, "severity" to "blocker", "body" to "blocking")
        val done = agent.addOnLine(6, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)
        assertTrue(window.isEnabled("Delete All"))

        val declined = window.press("Delete All", answer = NO)
        assertEquals(
            "Delete all 2 margin thread(s), including the resolved log? 1 open blocker(s) are among them. This cannot be undone.",
            declined,
        )
        assertEquals("open", status(kept))

        window.rebuiltBy { window.press("Delete All", answer = YES) }

        assertEquals(emptyList<String>(), agent.ids())
        assertEquals(emptyList<String>(), window.rows())
        assertFalse(window.isEnabled("Delete All"))
    }

    fun testDeleteAllWarnsThatDeletedRelayedThreadsCanComeBack() {
        sample()
        val relayed = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))
        agent.addOnLine(8, "body" to "ours")
        window.rebuiltBy { window.chooseFromContextMenu("From Mona.", "Delete…", answer = YES) }
        assertNull(status(relayed))

        val asked = window.rebuiltBy { window.press("Delete All", answer = YES) }

        assertEquals(
            "Delete all 1 margin thread(s), including the resolved log? This cannot be undone. " +
                "Relayed threads you deleted from the margin can then come back the next time an agent relays their PR.",
            asked,
        )
        val again = window.rebuiltBy {
            agent.post("comment_add", "file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))
        }
        assertEquals(again.body, 200, again.status)
        assertEquals(listOf("✉ L5  From Mona."), window.rows().filter { it.contains("Mona") })
    }

    fun testCommentOnProjectOpensADraftInTheMarginTab() {
        sample()
        user.openFile(SAMPLE)

        window.press("Comment on Project")

        assertEquals(MarginFile, FileEditorManager.getInstance(project).selectedEditor?.file)
        assertNotNull(ProjectTabState.getInstance(project).draft)
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testAThreadsContextMenuNavigatesResolvesAndReopens() {
        sample()
        val id = agent.addOnLine(14, "body" to "Look here")

        assertEquals(listOf("Navigate", "Resolve", "Delete…"), window.contextMenu("Look here"))
        window.chooseFromContextMenu("Look here", "Navigate")
        assertEquals("Sample.kt", openFile())
        assertEquals(14, caretLine())

        window.rebuiltBy { window.chooseFromContextMenu("Look here", "Resolve") }
        assertEquals("resolved", status(id))
        window.toggleFolding("Resolved")
        window.toggleFolding("src", under = "Resolved")
        window.toggleFolding("Sample.kt", under = "Resolved")

        assertEquals(listOf("Navigate", "Reopen", "Delete…"), window.contextMenu("Look here"))
        window.rebuiltBy { window.chooseFromContextMenu("Look here", "Reopen") }
        assertEquals("open", status(id))
        assertEquals("Open  1", window.rows().first())
    }

    fun testDeletingFromAThreadsContextMenuAsksFirst() {
        sample()
        val id = agent.addOnLine(14, "severity" to "blocker", "body" to "Look here")

        val declined = window.chooseFromContextMenu("Look here", "Delete…", answer = NO)
        assertEquals(
            "Delete 1 thread(s)? 1 open blocker(s) are among them. Unlike resolving, deletion keeps no record. This cannot be undone.",
            declined,
        )
        assertEquals("open", status(id))

        window.rebuiltBy { window.chooseFromContextMenu("Look here", "Delete…", answer = YES) }
        assertNull(status(id))
        assertEquals(emptyList<String>(), window.rows())
    }

    fun testDeletingARelayedThreadFromTheTreeWarnsItWillNotComeBack() {
        sample()
        agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))

        val asked = window.chooseFromContextMenu("From Mona.", "Delete…", answer = YES)

        assertTrue(asked, asked.endsWith("This cannot be undone. This removes it from the margin only (GitHub is untouched), and relaying the PR again won't bring it back."))
        val again = agent.post("comment_add", "file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))
        assertEquals("deleted_relay", again["reason"]!!.asString)
    }

    fun testAFilesContextMenuCommentsOnTheFileAndActsOnAllItsThreads() {
        sample()
        val first = agent.addOnLine(3, "severity" to "blocker", "body" to "first")
        val second = agent.addOnLine(6, "body" to "second")
        val done = agent.addOnLine(9, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)

        assertEquals(listOf("Comment on File", "Resolve 2 Open Thread(s)…", "Delete 2 Thread(s)…"), window.contextMenu("Sample.kt"))

        window.chooseFromContextMenu("Sample.kt", "Comment on File")
        assertEquals("Sample.kt", openFile())
        val draft = UIUtil.findComponentsOfType(user.editor().contentComponent, ThreadPanel::class.java)
        assertEquals(1, draft.size)

        val declined = window.chooseFromContextMenu("Sample.kt", "Resolve 2 Open Thread(s)…", answer = NO)
        assertEquals("Resolve 2 open thread(s)? 1 of them are blockers — resolve only if their outcomes genuinely landed.", declined)
        assertEquals("open", status(first))

        window.rebuiltBy { window.chooseFromContextMenu("Sample.kt", "Resolve 2 Open Thread(s)…", answer = YES) }
        assertEquals("resolved", status(first))
        assertEquals("resolved", status(second))
        assertEquals(listOf("Resolved  3"), window.rows())
    }

    fun testResolvingAFilesThreadsWithoutBlockersAsksPlainly() {
        sample()
        agent.addOnLine(3, "body" to "first")
        agent.add("file" to SAMPLE, "body" to "whole file")

        val asked = window.chooseFromContextMenu("Sample.kt", "Resolve 2 Open Thread(s)…", answer = YES)

        assertEquals("Resolve 2 open thread(s)?", asked)
        assertEquals(listOf("resolved", "resolved"), agent.threads().map { it.string("status") })
    }

    fun testAFoldersContextMenuActsOnEveryThreadBeneathItButCannotComment() {
        sample()
        sample("src/deeper/Other.kt")
        agent.addOnLine(3, "body" to "first")
        agent.addOnLine(6, "body" to "second", file = "src/deeper/Other.kt")

        assertEquals(listOf("Resolve 2 Open Thread(s)…", "Delete 2 Thread(s)…"), window.contextMenu("src  2"))
        assertEquals(listOf("Resolve 1 Open Thread(s)…", "Delete 1 Thread(s)…"), window.contextMenu("deeper"))

        val asked = window.chooseFromContextMenu("src  2", "Delete 2 Thread(s)…", answer = YES)

        assertEquals("Delete 2 thread(s)? Unlike resolving, deletion keeps no record. This cannot be undone.", asked)
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testAResolvedFilesContextMenuOffersOnlyCommentingAndDeleting() {
        sample()
        val done = agent.addOnLine(9, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)
        window.toggleFolding("Resolved")
        window.toggleFolding("src", under = "Resolved")

        assertEquals(listOf("Comment on File", "Delete 1 Thread(s)…"), window.contextMenu("Sample.kt"))
    }

    fun testSectionAndProjectRowsHaveNoContextMenu() {
        sample()
        agent.add("body" to "project wide")

        assertEquals(emptyList<String>(), window.contextMenu("Open"))
        assertEquals(emptyList<String>(), window.contextMenu("Project  1"))
        assertEquals(listOf("Navigate", "Resolve", "Delete…"), window.contextMenu("project wide"))
    }

    fun testTheTitleBarShowsWhoListensAndSubmitsTheRound() {
        sample()
        val id = agent.addOnLine(5, "body" to "Which one?")
        assertEquals(emptyList<String>(), window.presence())
        assertFalse(window.isEnabled("Submit round"))
        assertFalse(window.isEnabled("Stop agents"))

        val waiting = agent.startWait()
        assertEquals(1, window.presence().size)
        assertTrue(window.presence().single(), window.presence().single().contains("Claude"))
        assertTrue(window.isEnabled("Submit round"))
        assertTrue(window.isEnabled("Stop agents"))
        assertEquals(listOf("Stop Claude", null, "Stop all agents"), window.group("Stop agents").map { it.templatePresentation.text })

        user.reply(id, "This one.")
        window.press("Submit round")
        val wake = agent.await(waiting)

        assertEquals("hand_back", wake["reason"]!!.asString)
        assertEquals(listOf(id), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
        assertFalse(window.isEnabled("Submit round"))
    }
}
