package dev.marginalis.plugin.ui

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.ui.tab.MarginFile
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import dev.marginalis.plugin.testing.MarginUser
import dev.marginalis.plugin.testing.MarginalisTestCase
import com.intellij.openapi.util.text.StringUtil.unescapeXmlEntities
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.CommentThread

class WalkthroughNavigatorTest : MarginalisTestCase() {

    private val sample = (1..12).joinToString("\n") { "val line$it = $it" }

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("Sample.kt", sample)
    }

    private fun step(line: Int?, file: String = "Sample.kt") =
        CommentThread(file, line, anchorText = line?.let { "val line${it + 1} = ${it + 1}" })

    private val user by lazy { MarginUser(project, testRootDisposable) }

    private val caretLine get() = myFixture.editor.caretModel.logicalPosition.line

    fun testStepInTheSameFileMovesTheCaretToIt() {
        assertTrue(WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(7)))
        assertEquals(7, caretLine)
    }

    fun testStepInAnotherFileIsDeclined() {
        myFixture.addFileToProject("Other.kt", "val other = 1\nval more = 2\n")
        assertFalse(WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(1, file = "Other.kt")))
        assertEquals(0, caretLine)
    }

    fun testReleasedEditorIsDeclined() {
        val editor = EditorFactory.getInstance().createEditor(myFixture.editor.document, project)
        EditorFactory.getInstance().releaseEditor(editor)
        assertFalse(WalkthroughNavigator.navigateWithin(editor, project, step(7)))
    }

    fun testStepInsideFoldedCodeIsUnfolded() {
        val document = myFixture.editor.document
        val folding = myFixture.editor.foldingModel
        folding.runBatchFoldingOperation {
            folding.addFoldRegion(document.getLineStartOffset(3), document.getLineEndOffset(9), "...")!!.isExpanded = false
        }

        WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(6))

        assertFalse(folding.isOffsetCollapsed(myFixture.editor.caretModel.offset))
    }

    fun testBackReturnsToThePreviousStep() {
        WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(2))
        WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(9))

        IdeDocumentHistory.getInstance(project).back()

        assertEquals(2, caretLine)
    }

    fun testFileLevelThreadStaysInTheEditor() {
        assertTrue(WalkthroughNavigator.navigateWithin(myFixture.editor, project, step(null)))
        assertEquals(0, caretLine)
    }

    fun testStepOpensItsThreadInTheSameEditor() {
        val target = step(7)

        WalkthroughNavigator.navigateWithin(myFixture.editor, project, target)

        val panel = UIUtil.findComponentOfType(myFixture.editor.contentComponent, ThreadPanel::class.java)
        assertNotNull(panel)
    }

    fun testAThreadInAnotherFileOpensThatFileAtItsLineWithItsPanel() {
        myFixture.addFileToProject("Other.kt", (1..12).joinToString("\n") { "val other$it = $it" })

        WalkthroughNavigator.navigateTo(project, CommentThread("Other.kt", 4, anchorText = "val other5 = 5"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("Other.kt", FileDocumentManager.getInstance().getFile(editor.document)!!.name)
        assertEquals(4, editor.caretModel.logicalPosition.line)
        assertNotNull(UIUtil.findComponentOfType(editor.contentComponent, ThreadPanel::class.java))
    }

    fun testAThreadWhoseFileIsGoneGoesNowhereAndSaysWhy() {
        val shown = user.notifications()

        WalkthroughNavigator.navigateTo(project, CommentThread("Gone.kt", 4, anchorText = "x"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals("Sample.kt", FileEditorManager.getInstance(project).selectedFiles.single().name)
        assertNull(UIUtil.findComponentOfType(myFixture.editor.contentComponent, ThreadPanel::class.java))
        assertEquals(listOf("Couldn't show the thread: Gone.kt is no longer in the project."), shown.map { unescapeXmlEntities(it.content) })
    }

    fun testAProjectLevelStepFoldsTheStepYouLeave() {
        var leftBehind = true

        WalkthroughNavigator.navigateTo(project, CommentThread(null, null, anchorText = null, order = 2), onShown = { leftBehind = false })

        assertFalse(leftBehind)
    }

    fun testAProjectThreadIsShownInTheMarginTab() {
        WalkthroughNavigator.navigateTo(project, CommentThread(null, null, anchorText = null))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(MarginFile.of(project), FileEditorManager.getInstance(project).selectedEditor?.file)
    }
}
