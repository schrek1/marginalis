package dev.marginalis.plugin.actions

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.FileLevelThreads
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.tab.MarginFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.LightVirtualFile

class CommentingInTheEditorTest : HttpContractTestCase() {

    private val addComment = "marginalis.addComment"
    private val commentOnFile = "marginalis.commentOnFile"

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    private fun shown(panel: ThreadPanel = user.panel()): String = user.shownText(panel)

    fun testCommentingOnALineStartsAnUnsentDraftBelowIt() {
        sample()

        val editor = user.comment(SAMPLE, 7)

        val panel = user.panel(editor)
        assertTrue(shown(panel).lines().contains("new comment — unsent"))
        assertEquals("Submit", user.submitLabel(panel))
        assertEquals(listOf("Comment on file instead", "Comment on project instead"), user.sendOptions(panel))
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(Inlay.Placement.BELOW_LINE, inlay.placement)
        assertEquals(6, editor.document.getLineNumber(inlay.offset))
        assertEquals(emptyList<String>(), agent.ids())
        assertNull(user.gutterIcon(SAMPLE, 7))
    }

    fun testSubmittingTheDraftHandsTheAgentALineThreadAndMarksTheGutter() {
        sample()

        user.comment(SAMPLE, 7)
        val panel = user.panel()
        user.type(panel, "Why a val here?")
        user.submit(panel)

        val thread = agent.threads().single()
        assertEquals(SAMPLE, thread.string("file"))
        assertEquals(7, thread["line"].asInt)
        assertEquals(lineText(7), thread.string("anchor_text"))
        assertEquals("Why a val here?", thread.messages().single().string("body"))
        assertEquals("user", thread.messages().single().getAsJsonObject("author").string("kind"))
        assertEquals(listOf(thread.id), agent.ids("awaiting" to "agent"))
        assertTrue(user.gutterIcon(SAMPLE, 7)!!.tooltipText.contains("Why a val here?"))
        assertTrue(shown(panel).lines().contains("open"))
        assertEquals("Reply", user.submitLabel(panel))
    }

    fun testClosingADraftWithoutSubmittingLeavesNoThread() {
        sample()
        user.comment(SAMPLE, 7)

        user.press(user.panel(), "Close")

        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals(emptyList<String>(), agent.ids())
        assertNull(user.gutterIcon(SAMPLE, 7))
    }

    // FixMe: Known issue - #57 closing an unsent new comment loses its text: commenting on the line again starts an empty draft
    fun testCommentingAgainOnTheSameLineStartsAnEmptyDraftAndStrandsTheTypedText() {
        sample()
        user.comment(SAMPLE, 7)
        user.type(user.panel(), "half")
        user.press(user.panel(), "Close")

        user.comment(SAMPLE, 7)

        assertEquals(1, user.panels().size)
        assertTrue(shown().lines().contains("new comment — unsent"))
        assertEquals("", user.composer(user.panel()).text)
        assertEquals(listOf("half"), store.drafts.values.map { it.text })
    }

    fun testCommentingOnALineThatHasAThreadOpensThatThread() {
        sample()
        agent.addOnLine(7, "body" to "Already discussed.")

        user.comment(SAMPLE, 7)

        assertTrue(shown().contains("Already discussed."))
        assertEquals(1, agent.ids().size)
    }

    fun testCommentingOnALineWithSeveralThreadsOffersAChoice() {
        sample()
        agent.addOnLine(7, "body" to "first thought")
        reviewer.addOnLine(7, "body" to "second thought")
        val offered = mutableListOf<String>()
        user.choose(offered, "first thought")

        user.comment(SAMPLE, 7)

        assertEquals(2, offered.size)
        assertTrue(shown().contains("first thought"))
    }

    fun testCommentingOnALineWhoseThreadIsResolvedStartsAFreshDraft() {
        sample()
        val id = agent.addOnLine(7, "body" to "Done with.")
        agent.post("comment_resolve", "thread_id" to id)
        settle()

        user.comment(SAMPLE, 7, "New point.")
        settle()

        assertEquals(2, agent.ids().size)
        assertEquals("New point.", agent.threads("status" to "open").single().messages().single().string("body"))
    }

    fun testASelectionStartsASpanDraftEvenBesideAnExistingThread() {
        sample()
        agent.addOnLine(12, "body" to "whole line")

        user.comment(SAMPLE, 12, "rename?", selecting = "line12")
        settle()

        val span = agent.threads().single { it.has("segment") }
        assertEquals("line12", span.getAsJsonObject("segment").string("exact"))
        assertEquals("rename?", span.messages().single().string("body"))
        assertTrue(user.gutterIcon(SAMPLE, 12)!!.tooltipText.contains("2 Marginalis threads"))
    }

    fun testASelectionAcrossLinesIsClampedToItsFirstLine() {
        sample()
        val editor = user.openFile(SAMPLE, 12)
        val start = editor.document.getLineStartOffset(11) + "val ".length
        editor.selectionModel.setSelection(start, editor.document.getLineEndOffset(13))

        user.invoke(addComment, editor)
        user.type(user.panel(), "These three.")
        user.submit(user.panel())

        val thread = agent.threads().single()
        assertEquals(12, thread["line"].asInt)
        val segment = thread.getAsJsonObject("segment")
        assertEquals("line12 = 12", segment.string("exact"))
        assertEquals("val ", segment.string("prefix"))
        assertFalse(segment.has("suffix"))
    }

    fun testABlankSelectionIsAWholeLineComment() {
        myFixture.addFileToProject(SAMPLE, "val a = 1\n    \nval b = 2\n")
        val editor = user.openFile(SAMPLE, 2)
        editor.selectionModel.setSelection(editor.document.getLineStartOffset(1), editor.document.getLineEndOffset(1))

        user.invoke(addComment, editor)
        user.type(user.panel(), "Blank line.")
        user.submit(user.panel())

        val thread = agent.threads().single()
        assertEquals(2, thread["line"].asInt)
        assertFalse(thread.has("segment"))
    }

    fun testALongSelectionKeepsOnlyNearbyContextAroundIt() {
        myFixture.addFileToProject(SAMPLE, "val prefix = " + "a".repeat(60) + " + target + " + "b".repeat(60) + "\n")

        user.comment(SAMPLE, 1, "Why?", selecting = "target")

        val segment = agent.threads().single().getAsJsonObject("segment")
        assertEquals("target", segment.string("exact"))
        assertTrue(segment.string("prefix")!!.length < 60)
        assertTrue(segment.string("prefix")!!.endsWith("a + "))
        assertTrue(segment.string("suffix")!!.startsWith(" + b"))
    }

    fun testCommentingIsOfferedOnlyInAFileEditor() {
        sample()
        val editor = user.openFile(SAMPLE)
        val scratch = EditorFactory.getInstance().createEditor(EditorFactory.getInstance().createDocument("loose text"), project)
        try {
            assertTrue(user.isOffered(addComment, editor))
            assertTrue(user.isOffered(commentOnFile, editor))
            assertFalse(user.isOffered(addComment, null))
            assertFalse(user.isOffered(commentOnFile, null))
            assertFalse(user.isOffered(addComment, scratch))
            assertFalse(user.isOffered(commentOnFile, scratch))
        } finally {
            EditorFactory.getInstance().releaseEditor(scratch)
        }
    }

    fun testCommentingOnAFileOutsideTheProjectDoesNothing() {
        sample()
        val outside = LightVirtualFile("Loose.kt", "val x = 1\n")
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, outside), true)!!
        assertTrue(user.isOffered(addComment, editor))

        user.invoke(addComment, editor)
        user.invoke(commentOnFile, editor)

        assertEquals(emptyList<ThreadPanel>(), user.panels(editor))
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testCommentingOnTheFileStartsAFileLevelDraftAboveTheFirstLine() {
        sample()

        val editor = user.comment(SAMPLE, 30, action = commentOnFile)

        val panel = user.panel(editor)
        assertTrue(shown(panel).lines().contains("new comment — unsent"))
        assertEquals(listOf("Comment on project instead"), user.sendOptions(panel))
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(Inlay.Placement.ABOVE_LINE, inlay.placement)
        assertEquals(0, editor.document.getLineNumber(inlay.offset))
    }

    fun testSubmittingAFileLevelDraftHandsTheAgentAThreadAboutTheFile() {
        sample()

        user.comment(SAMPLE, 30, "Split this file.", action = commentOnFile)
        settle()

        val thread = agent.threads().single()
        assertEquals(SAMPLE, thread.string("file"))
        assertFalse(thread.has("line"))
        assertEquals("Split this file.", thread.messages().single().string("body"))
        assertTrue(user.gutterIcon(SAMPLE, 1)!!.tooltipText.contains("Split this file."))
    }

    fun testCommentingOnTheFileWithASelectionKeepsTheSelectionAsItsSubject() {
        sample()

        user.comment(SAMPLE, 30, "Used everywhere?", action = commentOnFile, selecting = "line30")

        val thread = agent.threads().single()
        assertFalse(thread.has("line"))
        assertEquals("line30", thread.getAsJsonObject("segment").string("exact"))
    }

    fun testALineDraftCanBeSubmittedAsAFileCommentInstead() {
        sample()
        user.comment(SAMPLE, 30, selecting = "line30")
        user.type(user.panel(), "Really about the whole file.")

        user.sendInstead(user.panel(), "Comment on file instead")

        val thread = agent.threads().single()
        assertEquals(SAMPLE, thread.string("file"))
        assertFalse(thread.has("line"))
        assertEquals("line30", thread.getAsJsonObject("segment").string("exact"))
        assertEquals("Really about the whole file.", thread.messages().single().string("body"))
        assertTrue(shown().contains("Really about the whole file."))
        assertNull(store.drafts.values.firstOrNull())
    }

    fun testALineDraftCanBeSubmittedAsAProjectCommentInstead() {
        sample()
        user.comment(SAMPLE, 30)
        user.type(user.panel(), "Applies everywhere.")

        user.sendInstead(user.panel(), "Comment on project instead")

        val thread = agent.threads().single()
        assertFalse(thread.has("file"))
        assertEquals("Applies everywhere.", thread.messages().single().string("body"))
        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals(MarginFile, FileEditorManager.getInstance(project).selectedEditor?.file)
    }

    fun testABlankDraftIsNotMovedUp() {
        sample()
        user.comment(SAMPLE, 30)

        user.sendInstead(user.panel(), "Comment on project instead")

        assertEquals(emptyList<String>(), agent.ids())
        assertEquals(1, user.panels().size)
    }

    fun testStartingAFileCommentFromElsewhereOpensTheFileWithADraft() {
        sample()
        sample("src/Other.kt")
        user.openFile(SAMPLE, 20)

        FileLevelThreads.startDraft(project, "src/Other.kt")
        settle()

        assertEquals("Other.kt", FileDocumentManager.getInstance().getFile(user.editor().document)!!.name)
        assertTrue(shown().lines().contains("new comment — unsent"))
        user.type(user.panel(), "About Other.")
        user.submit(user.panel())
        assertEquals("src/Other.kt", agent.threads().single().string("file"))
        assertEquals(Authors.user.displayName, agent.threads().single().messages().single().getAsJsonObject("author").string("name"))
    }

    fun testStartingAFileCommentOnAMissingFileDoesNothing() {
        sample()
        user.openFile(SAMPLE)

        FileLevelThreads.startDraft(project, "src/Gone.kt")
        settle()

        assertEquals("Sample.kt", FileDocumentManager.getInstance().getFile(user.editor().document)!!.name)
        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testEachThreadGetsOnePanelPerEditor() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        user.clickGutter(SAMPLE, 5)
        user.clickGutter(SAMPLE, 9)

        assertEquals(2, user.panels().size)
        user.comment(SAMPLE, 5)
        assertEquals(2, user.panels().size)
    }

    fun testASelectionDraftMovedUpToTheProjectKeepsItsSelectionAsProvenance() {
        sample()
        user.comment(SAMPLE, 30, selecting = "line30")
        user.type(user.panel(), "Everywhere.")

        user.sendInstead(user.panel(), "Comment on project instead")

        val thread = agent.threads().single()
        assertFalse(thread.has("file"))
        assertEquals("line30", thread.getAsJsonObject("segment").string("exact"))
    }

    fun testQuoteCodeOnAFileDraftWithoutASelectionQuotesNothing() {
        sample()
        user.comment(SAMPLE, 30, action = commentOnFile)
        val panel = user.panel()

        user.clickLinkWithTooltip(panel, "Quote code")

        assertEquals("", user.composer(panel).text)
    }
}
