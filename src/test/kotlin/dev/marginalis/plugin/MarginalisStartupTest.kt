package dev.marginalis.plugin

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.Turn
import dev.marginalis.plugin.testing.Gutter
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.FileTurn
import dev.marginalis.plugin.ui.tab.MarginFile

class MarginalisStartupTest : HttpContractTestCase() {

    fun testAFreshProjectWithoutProjectThreadsOpensWithoutTheMarginTab() {
        assertFalse(FileEditorManager.getInstance(project).isFileOpen(MarginFile))
    }

    fun testAnAgentsThreadGetsAGutterIconOnItsLine() {
        sample()

        agent.addOnLine(5, "body" to "mark me")

        assertEquals(listOf(5), gutterLinesShowing("mark me"))
    }

    fun testResolvingRemovesTheGutterIconAndReopeningRestoresIt() {
        sample()
        val id = agent.addOnLine(5, "body" to "mark me")

        agent.post("comment_resolve", "thread_id" to id)
        settle()
        assertEquals(emptyList<Int>(), gutterLinesShowing("mark me"))

        agent.post("comment_reopen", "thread_id" to id)
        settle()
        assertEquals(listOf(5), gutterLinesShowing("mark me"))
    }

    // FixMe: Known issue - #53 reopening a resolved thread after lines moved pins it to its old line number instead of its text
    fun testAReopenedThreadIsPinnedToItsOldLineNumberAfterLinesMoved() {
        sample()
        val id = agent.addOnLine(5, "body" to "mark me")
        agent.post("comment_resolve", "thread_id" to id)
        settle()

        insertLinesAbove(1, 3)
        agent.post("comment_reopen", "thread_id" to id)
        settle()

        assertEquals(listOf(5), gutterLinesShowing("mark me"))
        val reopened = agent.threads().single()
        assertEquals(5, reopened["line"].asInt)
        assertEquals(lineText(2), reopened.string("anchor_text"))
    }

    fun testDeletingAThreadRemovesItsGutterIconAndForgetsItsDraft() {
        sample()
        val id = agent.addOnLine(5, "body" to "mark me")
        typeDraft(id, "half a thought")
        assertEquals("half a thought", composerTextOf(id))

        user.delete(id)

        assertEquals(emptyList<Int>(), gutterLinesShowing("mark me"))
        assertNull(store.drafts[id])
    }

    fun testResolvingKeepsTheDraftOfTheThread() {
        sample()
        val id = agent.addOnLine(5, "body" to "mark me")
        typeDraft(id, "half a thought")

        agent.post("comment_resolve", "thread_id" to id)
        settle()

        assertEquals("half a thought", composerTextOf(id))
    }

    fun testThreadsOnOneLineShareOneGutterIconUntilOneIsResolved() {
        sample()
        agent.addOnLine(5, "body" to "first thought")
        val second = agent.addOnLine(5, "body" to "second thought")
        assertEquals(listOf(5), gutterLinesShowing("2 Marginalis threads"))

        agent.post("comment_resolve", "thread_id" to second)
        settle()

        assertEquals(emptyList<Int>(), gutterLinesShowing("2 Marginalis threads"))
        assertEquals(listOf(5), gutterLinesShowing("first thought"))
        assertEquals(emptyList<Int>(), gutterLinesShowing("second thought"))
    }

    fun testResolvingAThreadWhoseLineWasDeletedLeavesNoGutterIcon() {
        sample()
        val id = agent.addOnLine(5, "body" to "mark me")
        eraseLine(5)

        agent.post("comment_resolve", "thread_id" to id)

        assertEquals(emptyList<Int>(), gutterLinesShowing("mark me"))
        assertEquals("resolved", agent.threads().single().string("status"))
    }

    fun testReopeningAThreadWhoseFileWasDeletedLeavesNoGutterIcon() {
        sample()
        sample("src/Gone.kt")
        val id = agent.addOnLine(5, file = "src/Gone.kt")
        agent.post("comment_resolve", "thread_id" to id)
        settle()
        WriteAction.run<Exception> { project.guessProjectDir()!!.findFileByRelativePath("src/Gone.kt")!!.delete(this) }

        agent.post("comment_reopen", "thread_id" to id)
        settle()

        assertNull(store.markerOf(user.thread(id)))
        assertEquals("open", agent.threads().single().string("status"))
    }

    fun testAFileLevelThreadShowsOnTheFirstLineUntilResolved() {
        sample()
        val id = agent.add("file" to SAMPLE, "body" to "split this file")
        settle()
        assertEquals(listOf(1), gutterLinesShowing("split this file"))

        agent.post("comment_resolve", "thread_id" to id)
        settle()

        assertEquals(emptyList<Int>(), gutterLinesShowing("split this file"))
    }

    fun testAProjectLevelThreadLeavesTheGutterAlone() {
        sample()
        agent.add("body" to "release plan")
        settle()

        assertEquals(emptyList<Int>(), gutterLinesShowing("release plan"))
    }

    fun testTheFileShowsWhoseTurnItIs() {
        sample()
        val file = project.guessProjectDir()!!.findFileByRelativePath(SAMPLE)!!
        assertNull(FileTurn.of(project, file))

        val id = agent.addOnLine(5)
        settle()
        assertEquals(Turn.USER_OWES, FileTurn.of(project, file))

        user.reply(id, "done")
        settle()
        assertEquals(Turn.AGENT_OWES, FileTurn.of(project, file))

        agent.post("comment_resolve", "thread_id" to id)
        settle()
        assertNull(FileTurn.of(project, file))
    }

    private fun typeDraft(threadId: String, text: String) {
        val panel = user.open(threadId)
        panel.focusReply()
        UIUtil.findComponentsOfType(panel, EditorTextField::class.java).last().text = text
    }

    private fun composerTextOf(threadId: String): String {
        val panel = user.open(threadId)
        panel.focusReply()
        return UIUtil.findComponentsOfType(panel, EditorTextField::class.java).last().text
    }

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    private fun gutterLinesShowing(text: String): List<Int> {
        settle()
        return Gutter.linesShowing(project, document(), text)
    }
}
