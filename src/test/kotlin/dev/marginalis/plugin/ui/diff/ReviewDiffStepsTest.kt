package dev.marginalis.plugin.ui.diff

import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.ReviewDiff
import dev.marginalis.plugin.testing.ReviewProcessorTab
import dev.marginalis.plugin.testing.ReviewProcessorTab.ReviewedFile
import dev.marginalis.plugin.testing.SteppingChoice
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.LastStep

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

    fun testPreviousStepInTheSameFileStaysInTheReview() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(20))

        user.press(user.panel(shownDiff().editor), "Previous Step")

        assertStepOpensInTheReview(review, CART, rowOf(5))
    }

    fun testResolvingAStepOpensTheNextOneInTheSameFileInTheReview() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Resolve")

        assertStepOpensInTheReview(review, CART, rowOf(20))
    }

    fun testAStepInAnotherFileOfTheReviewOpensInThatFilesTabEvenOnALineBothFilesShare() {
        walkthrough(CART to 5, ORDER to LINE_EVERY_FILE_SHARES)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = LINE_EVERY_FILE_SHARES)
        assertEquals(CART, review.shownFile())
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
        walkthrough(CART to 5, CART to 20)
        reviewOf(reviewed(CART, removing = 20), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(CART, line = 20)
    }

    fun testTheStepYouLandOnStaysOpenWhenTheReviewRefreshes() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, CART, rowOf(20))

        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(20)))
    }

    fun testTheStepYouLandOnStaysOpenWhenTheReviewRefreshesTwiceInARow() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, CART, rowOf(20))

        review.refresh()
        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(20)))
    }

    fun testStepsYouLeaveInTheSameFileStayOpenWhenChosenAndWhenTheReviewRefreshes() {
        SteppingChoice.keepTheStepYouLeaveOpen(testRootDisposable)
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertPanelRowsBecome(listOf(rowOf(5), rowOf(20)))
        assertEquals(rowOf(20), shownDiff().editor.caretModel.logicalPosition.line + 1)

        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(5), rowOf(20)))
    }

    fun testAStepInAnotherFileOpensInItsTabAndTheStepYouLeaveStaysOpenInTheReviewWhenChosen() {
        SteppingChoice.keepTheStepYouLeaveOpen(testRootDisposable)
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = 20)
        backToTheReview()
        assertEquals(CART, review.shownFile())
        assertPanelRowsBecome(listOf(rowOf(5)))
    }

    fun testAStepYouClosedStaysClosedWhenTheReviewRefreshes() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Close")
        openStepInTheReview(rowOf(20))

        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(20)))
    }

    fun testARefreshLeavesAThreadYouMarkedUnreadUnread() {
        val id = walkthrough(CART to 5).single()
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        val window = user.openToolWindow()
        window.rebuiltBy { window.chooseFromContextMenu("Step 1", "Mark as Unread") }
        assertEquals(1, user.thread(id).unreadByUserCount())

        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(5)))
        assertEquals(1, user.thread(id).unreadByUserCount())
    }

    fun testAReplyThatArrivedWhileTheFileWasAwayIsReadOnceItsRestoredPanelIsOnScreen() {
        val id = walkthrough(CART to 5).single()
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.panels(shownDiff().editor)
        assertEquals(0, user.thread(id).unreadByUserCount())

        shown(review, ORDER)
        agent.reply(id, "Answered while you were away")
        assertEquals(1, user.thread(id).unreadByUserCount())
        shown(review, CART)
        assertPanelRowsBecome(listOf(rowOf(5)))

        user.panels(shownDiff().editor)

        assertEquals(0, user.thread(id).unreadByUserCount())
    }

    fun testNextStepDropsExtraCaretsAndTheSelectionInTheReview() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        val editor = shownDiff().editor
        editor.caretModel.addCaret(editor.logicalToVisualPosition(LogicalPosition(rowOf(12) - 1, 0)))
        editor.selectionModel.setSelection(0, editor.document.getLineEndOffset(1))

        user.press(user.panel(editor), "Next Step")

        assertStepOpensInTheReview(review, CART, rowOf(20))
        assertEquals(1, editor.caretModel.caretCount)
        assertFalse(editor.selectionModel.hasSelection())
    }

    fun testAFileLevelStepInAUnifiedReviewThatOpensWithRemovedLinesPutsTheCaretWhereItsPanelIs() {
        agent.post(
            "comment_add_batch",
            "items" to listOf(
                step(order = 1, "file" to CART, "line" to 5, "anchor_text" to localLines(CART)[4]),
                step(order = 2, "file" to CART),
            ),
        )
        reviewOf(ReviewedFile(CART, text(localLines(CART)), text(localLines(CART).drop(2))), reviewed(ORDER), unified = true)
        val editor = shownDiff().editor
        openStepInTheReview(iconRowsOnceThereAre(2).single { shownDiff().textOfRow(it) == localLines(CART)[4] })

        user.press(user.panel(editor), "Next Step")

        assertPanelRowsBecome(listOf(1))
        assertEquals(1, editor.caretModel.logicalPosition.line + 1)
    }

    fun testNextStepInTheSameFileStaysInAUnifiedReview() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER), unified = true)
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, CART, rowOf(20))
    }

    fun testTheStepYouLandOnStaysOpenWhenAUnifiedReviewRefreshes() {
        walkthrough(CART to 5, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER), unified = true)
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, CART, rowOf(20))

        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(20)))
    }

    fun testAStepOnALineTheReviewRemovedOpensWhereTheReviewShowsItsIcon() {
        walkthrough(CART to 5, CART to LINE_EVERY_FILE_SHARES)
        val review = reviewOf(reviewed(CART, removing = LINE_EVERY_FILE_SHARES), reviewed(ORDER))
        val iconRows = iconRowsOnceThereAre(2)
        val stepTwoIcon = (iconRows - rowOf(5)).single()
        assertEquals("}", shownDiff().textOfRow(stepTwoIcon))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, CART, stepTwoIcon)
    }

    fun testThreadsLeftOpenComeBackWhenTheReviewShowsTheirFileAgainUnlessResolvedOrDeleted() {
        val ids = walkthrough(CART to 5, CART to 12, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        listOf(5, 12, 20).forEach { line -> openStepInTheReview(rowOf(line)) }
        assertEquals(listOf(5, 12, 20).map(::rowOf), panelRows())

        shown(review, ORDER)
        agent.post("comment_resolve", "thread_id" to ids[0])
        MarginalisStore.getInstance(project).threads.delete(ids[1])
        shown(review, CART)

        assertPanelRowsBecome(listOf(rowOf(20)))
    }

    fun testARefreshLeavesTheWalkthroughOnTheStepYouMovedTo() {
        val ids = walkthrough(CART to 5, CART to 20, INVOICE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        listOf(5, 20).forEach { line -> openStepInTheReview(rowOf(line)) }
        user.press(user.panels(shownDiff().editor).last(), "Next Step")
        assertStepOpensInItsFileTab(INVOICE, line = 4)

        backToTheReview()
        refreshed(review)

        assertPanelRowsBecome(listOf(rowOf(5)))
        assertEquals(ids[2], LastStep.getInstance(project).mark?.threadId)
    }

    private fun step(order: Int, vararg fields: Pair<String, Any>): Map<String, Any> =
        mapOf("order" to order, "walkthrough" to "SHOP-42", "body" to "Step $order", *fields)

    private fun walkthrough(vararg steps: Pair<String, Int>): List<String> {
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
        return agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results")
            .map { it.asJsonObject["thread_id"].asString }
    }

    private fun reviewOf(vararg files: ReviewedFile, unified: Boolean = false): ReviewProcessorTab {
        val review = ReviewProcessorTab.open(project, editors, files.toList(), unified)
        waitFor("the review never showed its first file") { shownDiffOrNull()?.hasText() == true }
        return review
    }

    private fun reviewed(path: String, removing: Int? = null): ReviewedFile {
        val local = localLines(path)
        val pullRequest = ADDED_ON_TOP + local.filterIndexed { index, _ -> index + 1 != removing }
        return ReviewedFile(path, text(local), text(pullRequest))
    }

    private fun rowOf(line: Int) = line + ADDED_ON_TOP.size

    private fun refreshed(review: ReviewProcessorTab) {
        val viewerBefore = shownDiff().viewer
        review.refresh()
        waitFor("the review never showed the file again") { shownDiffOrNull()?.takeIf { it.viewer !== viewerBefore }?.hasText() == true }
    }

    private fun shown(review: ReviewProcessorTab, file: String) {
        val viewerBefore = shownDiff().viewer
        review.switchTo(file)
        waitFor("the review never showed $file") {
            review.shownFile() == file && shownDiffOrNull()?.takeIf { it.viewer !== viewerBefore }?.hasText() == true
        }
    }

    private fun backToTheReview() {
        val tab = editors.allEditors.filterIsInstance<DiffEditorViewerFileEditor>().single()
        editors.openFile(tab.file, true)
        waitFor("the review never came back to the front") { shownDiffOrNull()?.hasText() == true }
    }

    private fun iconRowsOnceThereAre(count: Int): List<Int> {
        waitFor("the review never painted $count icons") { shownDiff().iconRows().size == count }
        return shownDiff().iconRows()
    }

    private fun assertPanelRowsBecome(expected: List<Int>) {
        waitFor("the review's panels never settled on $expected: ${panelRows()}") { panelRows() == expected }
        assertEquals(expected, panelRows())
    }

    private fun ReviewDiff.hasText() = editor.document.textLength > 0

    private fun openStepInTheReview(row: Int) {
        shownDiff().clickIcon(row)
    }

    private fun assertStepOpensInTheReview(review: ReviewProcessorTab, file: String, row: Int) {
        waitFor("the step never opened in the review") { panelRows().isNotEmpty() || editors.selectedEditor !is DiffEditorViewerFileEditor }
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertEquals(file, review.shownFile())
        assertEquals("the step's thread opens on its row", listOf(row), panelRows())
        val editor = shownDiff().editor
        assertEquals("the caret moves to the step's row", row, editor.caretModel.logicalPosition.line + 1)
        assertFalse("the step's row stays folded", editor.foldingModel.isOffsetCollapsed(editor.caretModel.offset))
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
        const val LINE_EVERY_FILE_SHARES = 8
        val LINES_EVERY_FILE_SHARES = setOf(LINE_EVERY_FILE_SHARES, 10)
        val ADDED_ON_TOP = listOf("// added 1", "// added 2", "// added 3")

        fun localLines(path: String): List<String> {
            val name = path.substringAfterLast('/').substringBefore('.').lowercase()
            return (1..30).map { if (it in LINES_EVERY_FILE_SHARES) "}" else "val $name$it = $it" }
        }

        fun text(lines: List<String>) = lines.joinToString("\n") + "\n"
    }
}
