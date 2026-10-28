package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.testFramework.LightVirtualFile
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.DisplayOnly
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.NeedsADisplay
import dev.marginalis.plugin.testing.ReviewDiff
import dev.marginalis.plugin.testing.ReviewDiffs
import dev.marginalis.plugin.ui.MarginalisDataKeys
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
class SelectOpenedThreadTest : ToolWindowTestCase() {

    private fun moveCaretTo(line: Int) {
        user.editor().caretModel.moveToLogicalPosition(LogicalPosition(line - 1, 0))
    }

    fun testTheTitleBarLeavesItOutWhileFindActionAndTheKeymapStillReachIt() {
        window.toolbar() shouldNotContain "Select Opened Thread"
        ActionManager.getInstance().getAction(SelectOpenedThreadAction.ID).shouldNotBeNull()
    }

    fun testItSelectsTheThreadOnTheCaretsLine() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        user.openFile(SAMPLE, 9)

        window.selectOpenedThread()

        assertEquals("✉ L9  second", window.selected())
        assertEquals(emptyList<String>(), window.window.balloons)
    }

    fun testALineWithoutAThreadSelectsTheFile() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        user.openFile(SAMPLE, 7)

        window.selectOpenedThread()

        assertEquals("Sample.kt ✉2", window.selected())
    }

    fun testOfTwoThreadsOnOneLineTheOlderIsSelected() {
        sample()
        agent.addOnLine(5, "body" to "older")
        agent.addOnLine(5, "body" to "newer")
        user.openFile(SAMPLE, 5)

        window.selectOpenedThread()

        assertEquals("✉ L5  older", window.selected())
    }

    fun testAResolvedThreadOnTheCaretsLineIsPassedOver() {
        sample()
        val done = agent.addOnLine(5, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)
        user.openFile(SAMPLE, 5)

        window.selectOpenedThread()

        assertEquals(listOf("Resolved  1", "src  1", "Sample.kt"), window.selectedPath())
    }

    fun testAStepIsSelectedInItsGuidedSection() {
        sample()
        agent.addOnLine(5, "order" to 1, "body" to "a step")
        user.openFile(SAMPLE, 5)

        window.selectOpenedThread()

        assertEquals("Guided  1", window.selectedPath().first())
        assertEquals("✉ (1/1)  Sample.kt L5  a step", window.selected())
    }

    fun testATitleBarClickLeavesFocusWhereItWas() {
        sample()
        agent.addOnLine(5, "body" to "first")
        user.openFile(SAMPLE, 5)

        window.selectOpenedThread()

        assertEquals(0, window.window.activations)
    }

    fun testAFilterThatHidesTheThreadResetsToAll() {
        sample()
        agent.addOnLine(3, "severity" to "blocker", "body" to "blocking")
        agent.addOnLine(9, "body" to "ordinary")
        window.filterBy("Blockers Only")
        user.openFile(SAMPLE, 9)

        window.selectOpenedThread()

        assertEquals("✉ L9  ordinary", window.selected())
        assertEquals(mapOf("All" to true), window.filters().filterValues { it })
        assertEquals(listOf("Showing all threads: your filter was hiding this one."), window.window.balloons)
    }

    fun testAFilterThatShowsTheThreadStays() {
        sample()
        agent.addOnLine(3, "severity" to "blocker", "body" to "blocking")
        agent.addOnLine(9, "body" to "ordinary")
        window.filterBy("Blockers Only")
        user.openFile(SAMPLE, 3)

        window.selectOpenedThread()

        assertEquals("✉ L3  blocking", window.selected())
        assertEquals(mapOf("Blockers Only" to true), window.filters().filterValues { it })
        assertEquals(emptyList<String>(), window.window.balloons)
    }

    fun testAFilterStaysWhenWhatIsInFrontIsNotListedAnyway() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(3, "severity" to "blocker", "body" to "blocking")
        window.filterBy("Blockers Only")
        user.openFile("src/Other.kt", 3)

        window.selectOpenedThread()

        assertEquals(mapOf("Blockers Only" to true), window.filters().filterValues { it })
        assertEquals(listOf("No margin threads in Other.kt."), window.window.balloons)
    }

    @DisplayOnly
    fun testTheSelectionStaysInViewWhenTheTreeRebuildsRightAfter() {
        sample()
        (40..58 step 2).forEach { agent.addOnLine(it, "body" to "late $it") }
        window.showOnScreen(400, 160)
        user.openFile(SAMPLE, 58)
        window.selectOpenedThread()
        assertTrue(window.isSelectionInView())

        window.rebuiltBy { (1..12).forEach { agent.addOnLine(it, "body" to "early $it") } }

        assertEquals("✉ L58  late 58", window.selected())
        assertTrue(window.isSelectionInView())
    }

    fun testAThreadTheTreeHasNotCaughtUpWithIsStillSelected() {
        sample()
        agent.addOnLine(5, "body" to "first")
        window.outline()
        user.openFile(SAMPLE, 8)
        val fresh = CommentThread(SAMPLE, 7, lineText(8)).apply { addMessage(Message(Authors.user, "fresh")) }

        MarginalisStore.getInstance(project).threads.add(fresh)
        window.selectOpenedThread()

        assertTrue(window.selected().toString(), window.selected()!!.endsWith("L8  fresh"))
    }

    fun testATitleBarClickAfterTypingInAReplyBoxSelectsTheThreadOnTheCaretsLine() {
        sample()
        val first = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        val panel = user.open(first)
        moveCaretTo(9)

        user.fireComposerFocusGained(panel)
        window.selectOpenedThread()

        assertEquals("✉ L9  second", window.selected())
    }

    fun testTheReplyBoxResolvesThroughItsDataContextBeforeAnyEditorThere() {
        sample()
        val first = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        val panel = user.open(first)
        moveCaretTo(9)

        val context = user.replyBoxContext(panel)

        assertSame(user.thread(first), context.getData(MarginalisDataKeys.THREAD))
        assertNotNull(context.getData(CommonDataKeys.EDITOR))
        window.selectOpenedThread(context)
        assertEquals("✉ L5  first", window.selected())
    }

    fun testAResolvedThreadFromItsReplyBoxUnfoldsResolved() {
        sample()
        val done = agent.addOnLine(5, "body" to "settled")
        agent.post("comment_resolve", "thread_id" to done)
        val panel = user.open(done)

        window.selectOpenedThread(user.replyBoxContext(panel))

        assertEquals("Resolved  1", window.selectedPath().first())
        assertTrue(window.selected()!!.endsWith("L5  settled"))
        assertTrue(window.isExpanded("Resolved"))
    }

    fun testAnOrphanFromItsReplyBoxLandsUnderOrphaned() {
        sample()
        val lost = agent.addOnLine(20, "body" to "lost")
        eraseLine(20)
        agent.threads()
        val panel = user.open(lost)

        window.selectOpenedThread(user.replyBoxContext(panel))

        assertEquals("Orphaned  1", window.selectedPath().first())
    }

    fun testADraftSelectsItsFileAndSaysWhyItIsNotListed() {
        sample()
        agent.addOnLine(5, "body" to "first")
        val draft = user.panels(user.comment(SAMPLE, 9)).last()

        window.selectOpenedThread(user.replyBoxContext(draft))

        assertEquals("Sample.kt ✉1", window.selected())
        assertEquals(listOf("Submit the draft to list it."), window.window.balloons)
    }

    fun testADraftInAFileWithoutThreadsSaysBoth() {
        sample()
        val draft = user.panels(user.comment(SAMPLE, 9)).last()

        window.selectOpenedThread(user.replyBoxContext(draft))

        assertNull(window.selected())
        assertEquals(listOf("No margin threads in Sample.kt. Submit the draft to list it."), window.window.balloons)
    }

    fun testAFileOutsideTheProjectSaysSo() {
        sample()
        agent.addOnLine(5, "body" to "first")
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, LightVirtualFile("Loose.kt", "val x = 1\n")), true)

        window.selectOpenedThread()

        assertNull(window.selected())
        assertEquals(listOf("Loose.kt is outside the project."), window.window.balloons)
    }

    fun testWithNothingInFrontTheActionIsDisabled() {
        sample()
        agent.addOnLine(5, "body" to "first")
        FileEditorManager.getInstance(project).let { editors -> editors.openFiles.forEach(editors::closeFile) }

        assertFalse(window.isSelectOpenedThreadEnabled())
    }

    fun testWithTheMarginTabInFrontItSelectsTheProjectNodeEvenAfterTypingInAReplyBox() {
        sample()
        agent.add("body" to "first plan")
        agent.add("body" to "second plan")
        val tab = user.openMarginTab()

        window.selectOpenedThread()
        assertEquals("Project  2", window.selected())

        user.fireComposerFocusGained(tab.panel("second plan"))
        window.selectOpenedThread()
        assertEquals("Project  2", window.selected())
    }

    private val base get() = text((1..60).map(::lineText))

    private val pullRequest get() = text(
        (1..60).flatMap { line ->
            when (line) {
                5 -> emptyList()
                10 -> listOf("// added 1", "// added 2", "// added 3", lineText(line))
                else -> listOf(lineText(line))
            }
        },
    )

    private fun text(lines: List<String>) = lines.joinToString("\n") + "\n"

    private fun reviewTab(unified: Boolean): ReviewDiff {
        val editors = MarginTab(project, testRootDisposable).editors
        return ReviewDiffs(project, testRootDisposable).inATab(editors, SAMPLE, base, pullRequest, unified, user::dataContextOf)
    }

    private fun reviewedThreads() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        agent.addOnLine(22, "body" to "Not this")
    }

    fun testADiffTabsFocusedComponentNamesTheEditorInFront() {
        reviewedThreads()
        val diff = reviewTab(unified = false)
        val tab = FileEditorManager.getInstance(project).selectedEditor!!

        assertNull(FileEditorManager.getInstance(project).selectedTextEditor)
        assertSame(diff.editor, user.dataContextOf(tab.preferredFocusedComponent!!).getData(CommonDataKeys.EDITOR))
        diff.lookAtTheBase()
        assertSame(diff.baseEditor, user.dataContextOf(tab.preferredFocusedComponent!!).getData(CommonDataKeys.EDITOR))
    }

    fun testASideBySideReviewSelectsTheThreadOnTheCaretsRowThere() {
        reviewedThreads()
        val diff = reviewTab(unified = false)
        assertEquals(lineText(20), diff.textOfRow(22))

        diff.putCaretOnRow(22)
        window.selectOpenedThread()

        assertEquals("✉ L20  Check this", window.selected())
    }

    fun testAUnifiedReviewSelectsTheThreadOnTheCaretsRowThere() {
        reviewedThreads()
        val diff = reviewTab(unified = true)
        val row = diff.iconRows().single { diff.textOfRow(it) == lineText(20) }
        assertTrue(row != 20)

        diff.putCaretOnRow(row)
        window.selectOpenedThread()

        assertEquals("✉ L20  Check this", window.selected())
    }

    fun testTheBaseSideOfAReviewSelectsTheFile() {
        reviewedThreads()
        val diff = reviewTab(unified = false)
        diff.lookAtTheBase()

        diff.putCaretOnRow(20, on = diff.baseEditor)
        window.selectOpenedThread()

        assertEquals("Sample.kt ✉2", window.selected())
    }

    fun testATitleBarClickAfterTypingInAReviewsReplyBoxSelectsTheThreadOnTheCaretsRow() {
        reviewedThreads()
        val diff = reviewTab(unified = false)
        diff.clickIcon(22)
        val panel = diff.panels().single()
        diff.putCaretOnRow(24)

        user.fireComposerFocusGained(panel)
        window.selectOpenedThread()

        assertEquals("✉ L22  Not this", window.selected())
    }

    fun testComparingTwoProjectFilesLeavesTheLeftOneAnOrdinaryProjectFile() {
        sample("src/A.kt")
        sample("src/B.kt")
        agent.addOnLine(5, "body" to "in A", file = "src/A.kt")
        agent.addOnLine(5, "body" to "in B", file = "src/B.kt")
        val diff = ReviewDiffs(project, testRootDisposable).compareFiles("src/A.kt", "src/B.kt")

        diff.clickBaseIcon(5)
        assertEquals(1, diff.basePanels().size)

        diff.putCaretOnRow(9, on = diff.baseEditor)
        user.invoke("marginalis.addComment", diff.baseEditor)
        assertEquals(2, diff.basePanels().size)

        window.selectOpenedThread(diff.editorContext(diff.baseEditor))
        assertEquals("A.kt ✉1", window.selected())
    }

    private fun <T> withEditorOn(document: Document, use: (Editor) -> T): T {
        val editor = EditorFactory.getInstance().createEditor(document, project)
        return try {
            use(editor)
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testFocusInAnEditorWithNoFileAndNoTabOpenSaysToOpenAFile() {
        sample()
        agent.addOnLine(5, "body" to "first")
        FileEditorManager.getInstance(project).let { editors -> editors.openFiles.forEach(editors::closeFile) }

        withEditorOn(EditorFactory.getInstance().createDocument("console output")) { console ->
            window.selectOpenedThread(user.editorContext(console))
        }

        assertNull(window.selected())
        assertEquals(listOf("Open a file to find its thread."), window.window.balloons)
    }

    fun testFocusInAnEditorOfNoProjectFileHandsOverToTheFileInFront() {
        sample()
        agent.addOnLine(5, "body" to "first")
        user.openFile(SAMPLE, 5)
        val commitMessage = FileDocumentManager.getInstance().getDocument(LightVirtualFile("Dummy.txt", "Fix the thing"))!!

        withEditorOn(commitMessage) { window.selectOpenedThread(user.editorContext(it)) }

        assertEquals("✉ L5  first", window.selected())
        assertEquals(emptyList<String>(), window.window.balloons)
    }

    fun testADraftInAFileTheFilterHidesSaysOnlyWhyTheDraftIsNotListed() {
        sample()
        agent.addOnLine(5, "body" to "first")
        window.filterBy("Blockers Only")
        val draft = user.panels(user.comment(SAMPLE, 9)).last()

        window.selectOpenedThread(user.replyBoxContext(draft))

        assertEquals("Sample.kt ✉1", window.selected())
        assertEquals(listOf("Submit the draft to list it."), window.window.balloons)
    }

    fun testTheHeaderButtonSelectsAStepInItsGuidedSection() {
        sample()
        val step = agent.addOnLine(5, "order" to 1, "body" to "a step")
        window.outline()

        user.press(user.open(step), "Select in Marginalis")

        assertEquals("Guided  1", window.selectedPath().first())
        assertEquals("✉ (1/1)  Sample.kt L5  a step", window.selected())
    }

    fun testTheHeaderButtonSelectsAPlainThreadInOpenWhateverTheCaretsLine() {
        sample()
        val first = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        window.outline()
        val panel = user.open(first)
        moveCaretTo(9)

        user.press(panel, "Select in Marginalis")

        assertEquals(listOf("Open  2", "src  2", "Sample.kt ✉2", "✉ L5  first"), window.selectedPath())
    }

    fun testTheHeaderButtonOnAResolvedThreadUnfoldsResolved() {
        sample()
        val done = agent.addOnLine(5, "body" to "settled")
        agent.post("comment_resolve", "thread_id" to done)
        window.outline()

        user.press(user.open(done), "Select in Marginalis")

        assertEquals("Resolved  1", window.selectedPath().first())
        assertTrue(window.isExpanded("Resolved"))
    }

    fun testTheHeaderButtonIsHiddenOnADraft() {
        sample()
        val draft = user.panels(user.comment(SAMPLE, 9)).last()

        assertFalse(user.toolbar(draft).getValue("Select in Marginalis").isVisible)
    }

    fun testTheHeaderButtonOpensAClosedToolWindowAndFocusesTheTree() {
        sample()
        val first = agent.addOnLine(5, "body" to "first")
        window.window.visible = false

        user.press(user.open(first), "Select in Marginalis")

        assertTrue(window.window.visible)
        assertEquals(1, window.window.activations)
        assertSame(window.tree, window.window.contentManager.contents.single().preferredFocusableComponent)
        assertEquals("✉ L5  first", window.selected())
    }

    fun testAUnifiedDiffOfLocalChangesSelectsTheThreadOnTheCaretsRow() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        agent.addOnLine(22, "body" to "Not this")
        window.outline()
        val diff = ReviewDiffs(project, testRootDisposable).localChanges(SAMPLE, pullRequest, unified = true)
        val row = (1..diff.editor.document.lineCount).first { diff.textOfRow(it) == lineText(20) }
        assertTrue(row != 20)

        diff.putCaretOnRow(row)
        window.selectOpenedThread(diff.editorContext())

        assertEquals("✉ L20  Check this", window.selected())
        assertEquals(emptyList<String>(), window.window.balloons)
    }

    fun testADiffOfAFileThePullRequestDeletesSelectsTheFile() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        window.outline()
        val diff = ReviewDiffs(project, testRootDisposable).deleted(SAMPLE, base)

        diff.putCaretOnRow(20)
        window.selectOpenedThread(diff.editorContext())

        assertEquals("Sample.kt ✉1", window.selected())
        assertEquals(emptyList<String>(), window.window.balloons)
    }

    fun testAThreadAboutTheWholeFileDoesNotWinOverTheFirstLinesThread() {
        sample()
        agent.add("file" to SAMPLE, "body" to "about the file")
        agent.addOnLine(1, "body" to "on the first line")
        user.openFile(SAMPLE, 1)

        window.selectOpenedThread()

        assertEquals("✉ L1  on the first line", window.selected())
    }

    fun testADeletedThreadsStillOpenReplyBoxSaysItIsGoneNotThatItIsADraft() {
        sample()
        val gone = agent.addOnLine(5, "body" to "soon gone")
        val context = user.replyBoxContext(user.open(gone))

        MarginalisStore.getInstance(project).threads.delete(gone)
        window.selectOpenedThread(context)

        assertEquals(listOf("That thread is no longer in the margin."), window.window.balloons)
    }

    fun testADeletedThreadsStillOpenPanelKeepsItsHeaderButtonWhichSaysTheThreadIsGone() {
        sample()
        val gone = agent.addOnLine(5, "body" to "soon gone")
        window.outline()
        val panel = user.open(gone)

        MarginalisStore.getInstance(project).threads.delete(gone)

        assertTrue(user.toolbar(panel).getValue("Select in Marginalis").isVisible)
        user.press(panel, "Select in Marginalis")
        assertEquals(listOf("That thread is no longer in the margin."), window.window.balloons)
    }
}
