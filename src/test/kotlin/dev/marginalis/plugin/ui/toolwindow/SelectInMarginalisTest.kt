package dev.marginalis.plugin.ui.toolwindow

import com.intellij.ide.FileSelectInContext
import com.intellij.ide.SelectInContext
import com.intellij.ide.SelectInTarget
import com.intellij.ide.StandardTargetWeights
import com.intellij.ide.actions.SelectInContextImpl
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.TestActionEvent
import dev.marginalis.plugin.testing.FocusOwner
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.ReviewDiffs
import dev.marginalis.plugin.ui.tab.MarginFile

class SelectInMarginalisTest : ToolWindowTestCase() {

    private val target: SelectInTarget get() = SelectInTarget.EP_NAME.getExtensions(project).single { it.toString() == "Marginalis" }

    private fun inEditor(path: String, line: Int): SelectInContext {
        user.openFile(path, line)
        return FileSelectInContext(project, project.guessProjectDir()!!.findFileByRelativePath(path)!!)
    }

    fun testItWeighsMoreThanEveryPlatformTargetSoSelectInListsItBelowThem() {
        assertEquals("Marginalis", target.toolWindowId)
        assertTrue(target.weight > StandardTargetWeights.PROJECT_SETTINGS_WEIGHT)
    }

    fun testItIsOfferedOnlyForAFileWithThreads() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(5, "body" to "first")

        assertTrue(target.canSelect(inEditor(SAMPLE, 9)))
        assertFalse(target.canSelect(inEditor("src/Other.kt", 5)))
    }

    fun testItSelectsTheThreadOnTheCaretsLineAndFocusesTheTree() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        window.outline()

        target.selectIn(inEditor(SAMPLE, 9), true)
        window.settle()

        assertEquals("✉ L9  second", window.selected())
        assertEquals(1, window.window.activations)
        assertSame(window.tree, window.window.contentManager.contents.single().preferredFocusableComponent)
    }

    fun testWithTheToolWindowClosedItOpensIt() {
        sample()
        agent.addOnLine(5, "body" to "first")
        window.window.visible = false

        target.selectIn(inEditor(SAMPLE, 5), false)
        window.settle()

        assertTrue(window.window.visible)
        assertEquals(0, window.window.activations)
        assertEquals("✉ L5  first", window.selected())
    }

    fun testFromAReplyBoxItSelectsThatThread() {
        sample()
        val first = agent.addOnLine(5, "body" to "first")
        agent.addOnLine(9, "body" to "second")
        window.outline()
        val panel = user.open(first)
        user.editor().caretModel.moveToLogicalPosition(LogicalPosition(8, 0))

        val context = user.replyBoxContext(panel).getData(SelectInContext.DATA_KEY)!!

        assertTrue(target.canSelect(context))
        target.selectIn(context, true)
        window.settle()
        assertEquals("✉ L5  first", window.selected())
    }

    fun testFromADraftsReplyBoxItIsOfferedEvenBeforeTheFileHasThreads() {
        sample()
        val draft = user.panels(user.comment(SAMPLE, 9)).last()

        val context = user.replyBoxContext(draft).getData(SelectInContext.DATA_KEY)!!

        assertTrue(target.canSelect(context))
        assertEquals(SAMPLE.substringAfterLast('/'), context.virtualFile.name)
    }

    private val editors by lazy { MarginTab(project, testRootDisposable).editors }

    private fun selectInContextOfTheTabInFront(): SelectInContext {
        val focus = FileEditorManager.getInstance(project).selectedEditor!!.preferredFocusedComponent!!
        val selectIn = ActionManager.getInstance().getAction("SelectIn")
        return SelectInContextImpl.createContext(TestActionEvent.createTestEvent(selectIn, user.dataContextOf(focus)))!!
    }

    private fun lines(count: Int, edit: (Int) -> List<String> = { listOf(lineText(it)) }) =
        (1..count).flatMap(edit).joinToString("\n") + "\n"

    fun testFromAReviewDiffTabItSelectsTheThreadOnTheCaretsRow() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        agent.addOnLine(22, "body" to "Not this")
        window.outline()
        val pullRequest = lines(60) { if (it == 10) listOf("// added 1", "// added 2", lineText(it)) else listOf(lineText(it)) }
        val diff = ReviewDiffs(project, testRootDisposable).inATab(editors, SAMPLE, lines(60), pullRequest, false, user::dataContextOf)
        diff.putCaretOnRow(22)
        val context = selectInContextOfTheTabInFront()

        assertTrue(target.canSelect(context))
        target.selectIn(context, true)
        window.settle()

        assertEquals("✉ L20  Check this", window.selected())
    }

    fun testItIsNotOfferedInAReviewDiffOfAFileWithoutThreads() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(20, "body" to "Elsewhere", file = "src/Other.kt")
        ReviewDiffs(project, testRootDisposable).inATab(editors, SAMPLE, lines(60), lines(61), false, user::dataContextOf)

        assertFalse(target.canSelect(selectInContextOfTheTabInFront()))
    }

    fun testItIsNotOfferedInAClipboardComparison() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        ReviewDiffs(project, testRootDisposable).clipboardInATab(editors, "one\n", "two\n", user::dataContextOf)

        assertFalse(target.canSelect(selectInContextOfTheTabInFront()))
    }

    fun testFromTheMarginTabItIsOfferedOnlyOnceThereAreProjectThreads() {
        sample()
        agent.addOnLine(5, "body" to "in a file")
        assertFalse(target.canSelect(FileSelectInContext(project, MarginFile.of(project))))

        agent.add("body" to "about the project")
        assertTrue(target.canSelect(FileSelectInContext(project, MarginFile.of(project))))
    }

    fun testFromADiffInItsOwnWindowItSelectsTheThreadOnThatDiffsCaretRow() {
        sample()
        agent.addOnLine(5, "body" to "in the main window")
        agent.addOnLine(30, "body" to "in the diff")
        window.outline()
        user.openFile(SAMPLE, 5)
        val diff = ReviewDiffs(project, testRootDisposable).sideBySide(SAMPLE, lines(60), lines(60))
        diff.putCaretOnRow(30)
        FocusOwner.on(diff.editor.contentComponent, testRootDisposable)
        val context = FileSelectInContext(project, FileDocumentManager.getInstance().getFile(diff.editor.document)!!)

        assertTrue(target.canSelect(context))
        target.selectIn(context, false)
        window.settle()

        assertEquals("✉ L30  in the diff", window.selected())
    }
}
