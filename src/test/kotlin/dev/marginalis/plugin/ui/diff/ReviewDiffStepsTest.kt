package dev.marginalis.plugin.ui.diff

import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.LeavingAStep
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.ReviewDiff
import dev.marginalis.plugin.testing.ReviewProcessorTab
import dev.marginalis.plugin.testing.ReviewProcessorTab.ReviewedFile
import dev.marginalis.plugin.transport.HttpContractTestCase

class ReviewDiffStepsTest : HttpContractTestCase() {

    private lateinit var editors: FileEditorManagerImpl

    override fun setUp() {
        super.setUp()
        editors = MarginTab(project, testRootDisposable).editors
        listOf(CART, ORDER, INVOICE).forEach { path -> myFixture.addFileToProject(path, text(localLines(path))) }
    }

    fun testNextStepInTheSameFileStaysInTheReview() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, CART, rowOf(20))
    }

    fun testNextStepInAnotherFileSwitchesTheReviewToThatFile() {
        walkthrough(CART to 5, ORDER to 8)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, ORDER, rowOf(8))
    }

    fun testPreviousStepSwitchesTheReviewBackToTheFirstFile() {
        walkthrough(CART to 5, ORDER to 8)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        waitFor("the review never switched to $ORDER") { review.shownFile() == ORDER && panelRows() == listOf(rowOf(8)) }

        user.press(user.panel(shownDiff().editor), "Previous Step")

        assertStepOpensInTheReview(review, CART, rowOf(5))
    }

    fun testResolvingAStepMovesTheReviewToTheNextStepsFile() {
        walkthrough(CART to 5, ORDER to 8)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Resolve")

        assertStepOpensInTheReview(review, ORDER, rowOf(8))
    }

    fun testTheStepYouLandOnStaysOpenWhenTheReviewRefreshes() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, CART, rowOf(20))

        refreshed(review)

        assertEquals("the step's thread opens on its row", listOf(rowOf(20)), panelRows())
    }

    fun testTheStepYouLandOnInAnotherFileStaysOpenWhenTheReviewRefreshes() {
        walkthrough(CART to 5, ORDER to 8)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, ORDER, rowOf(8))

        refreshed(review)

        assertEquals("the step's thread opens on its row", listOf(rowOf(8)), panelRows())
    }

    fun testAStepYouClosedStaysClosedWhenTheReviewRefreshes() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Close")

        refreshed(review)

        assertEquals(emptyList<Int>(), panelRows())
    }

    fun testNextStepFoldsTheStepYouLeaveInTheReviewByDefault() {
        walkthrough(CART to 5, ORDER to 8, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")
        waitFor("the review never switched to $ORDER") { review.shownFile() == ORDER && panelRows() == listOf(rowOf(8)) }
        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, CART, rowOf(20))
    }

    fun testStepsYouLeaveStayOpenInTheReviewWhenChosen() = leavingAStep(LeavingAStep.KEEP_OPEN) {
        walkthrough(CART to 5, ORDER to 8, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")
        waitFor("the review never switched to $ORDER") { review.shownFile() == ORDER && panelRows() == listOf(rowOf(8)) }
        user.press(user.panel(shownDiff().editor), "Next Step")

        waitFor("the review never came back to $CART with both steps open") {
            review.shownFile() == CART && panelRows() == listOf(rowOf(5), rowOf(20))
        }
        assertEquals("the caret moves to the step's row", rowOf(20), shownDiff().editor.caretModel.logicalPosition.line + 1)
    }

    fun testAStepInAFileOutsideTheReviewOpensInThatFilesTab() {
        walkthrough(CART to 5, INVOICE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(INVOICE, line = 4)
        assertEquals(CART, review.shownFile())
    }

    fun testAStepWhoseLineTheReviewRemovedOpensInThatFilesTab() {
        walkthrough(CART to 5, ORDER to 8)
        reviewOf(reviewed(CART), reviewed(ORDER, removing = 8))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = 8)
    }

    private fun leavingAStep(choice: LeavingAStep, test: () -> Unit) {
        val settings = MarginalisSettings.getInstance()
        val before = settings.leavingAStep
        settings.leavingAStep = choice
        try {
            test()
        } finally {
            settings.leavingAStep = before
        }
    }

    private fun walkthrough(vararg steps: Pair<String, Int>) {
        val items = steps.mapIndexed { index, (file, line) ->
            mapOf(
                "file" to file,
                "line" to line,
                "anchor_text" to localLines(file)[line - 1],
                "order" to index + 1,
                "walkthrough" to "SHOP-42",
                "body" to "Step ${index + 1}",
            )
        }
        agent.post("comment_add_batch", "items" to items)
    }

    private fun reviewOf(vararg files: ReviewedFile): ReviewProcessorTab {
        val review = ReviewProcessorTab.open(project, editors, files.toList())
        waitFor("the review never showed its first file") { shownDiffOrNull()?.editor?.document?.textLength?.let { it > 0 } == true }
        return review
    }

    // The review adds three lines on top, so a step's line sits three rows lower in the review than in the file.
    private fun reviewed(path: String, removing: Int? = null): ReviewedFile {
        val local = localLines(path)
        val pullRequest = ADDED_ON_TOP + local.filterIndexed { index, _ -> index + 1 != removing }
        return ReviewedFile(path, text(local), text(pullRequest))
    }

    private fun rowOf(line: Int) = line + ADDED_ON_TOP.size

    private fun refreshed(review: ReviewProcessorTab) {
        val viewerBefore = shownDiff().viewer
        review.refresh()
        waitFor("the review never showed the file again") {
            shownDiffOrNull()?.takeIf { it.viewer !== viewerBefore }?.editor?.document?.textLength?.let { it > 0 } == true
        }
    }

    private fun openStepInTheReview(row: Int) {
        shownDiff().clickIcon(row)
    }

    private fun assertStepOpensInTheReview(review: ReviewProcessorTab, file: String, row: Int) {
        waitFor("the step never opened in the review") { panelRows().isNotEmpty() || editors.selectedEditor !is DiffEditorViewerFileEditor }
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertEquals(file, review.shownFile())
        assertEquals("the step's thread opens on its row", listOf(row), panelRows())
        assertEquals("the caret moves to the step's row", row, shownDiff().editor.caretModel.logicalPosition.line + 1)
    }

    private fun assertStepOpensInItsFileTab(file: String, line: Int) {
        waitFor("the step never opened in $file's tab") { editors.selectedTextEditor?.let(::fileOf) == file }
        val editor = editors.selectedTextEditor!!
        assertEquals(1, user.panels(editor).size)
        assertEquals(line, editor.caretModel.logicalPosition.line + 1)
    }

    private fun fileOf(editor: Editor): String? =
        FileDocumentManager.getInstance().getFile(editor.document)?.let { ProjectPaths.of(project, it) }

    private fun panelRows(): List<Int> = shownDiffOrNull()?.panelRows().orEmpty()

    private fun shownDiff(): ReviewDiff = checkNotNull(shownDiffOrNull()) { "no diff in front: ${editors.selectedEditor}" }

    private fun shownDiffOrNull(): ReviewDiff? {
        val tab = editors.selectedEditor as? DiffEditorViewerFileEditor ?: return null
        val focus = tab.preferredFocusedComponent ?: return null
        val viewer = user.dataContextOf(focus).getData(DiffDataKeys.DIFF_VIEWER) as? DiffViewerBase ?: return null
        return ReviewDiff(project, viewer)
    }

    private fun waitFor(failure: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(failure, condition, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private companion object {
        const val CART = "src/Cart.kt"
        const val ORDER = "src/Order.kt"
        const val INVOICE = "src/Invoice.kt"
        val ADDED_ON_TOP = listOf("// added 1", "// added 2", "// added 3")

        fun localLines(path: String): List<String> {
            val name = path.substringAfterLast('/').substringBefore('.').lowercase()
            return (1..30).map { "val $name$it = $it" }
        }

        fun text(lines: List<String>) = lines.joinToString("\n") + "\n"
    }
}
