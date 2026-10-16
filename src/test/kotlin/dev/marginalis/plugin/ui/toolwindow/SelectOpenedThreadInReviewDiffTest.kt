package dev.marginalis.plugin.ui.toolwindow

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.ui.diff.ReviewDiffMarkers

class SelectOpenedThreadInReviewDiffTest : ToolWindowTestCase() {

    fun testOnTheLineOfAThreadInTheReviewsTextItSelectsThatThread() {
        myFixture.addFileToProject(TARGET, BEFORE.joinToString("\n", postfix = "\n"))
        agent.add("file" to TARGET, "line" to 6, "anchor_text" to BEFORE[5], "body" to "total sums the values")
        agent.add("file" to TARGET, "line" to 2, "anchor_text" to BEFORE[1], "body" to "parse splits on commas")
        val review = reviewDiffInFront()

        review.caretModel.moveToLogicalPosition(LogicalPosition(LINE_OF_TOTAL_IN_REVIEW, 0))
        window.press("Select Opened Thread")

        assertTrue(window.selected(), window.selected()!!.endsWith("total sums the values"))
    }

    // The review's side of a diff in an editor tab, holding its own copy of the file's text.
    private fun reviewDiffInFront(): Editor {
        val editors = MarginTab(project, testRootDisposable).editors
        val local = project.guessProjectDir()!!.findFileByRelativePath(TARGET)!!
        val contents = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Review",
            contents.create(project, BEFORE.joinToString("\n", postfix = "\n")),
            contents.create(project, AFTER.joinToString("\n", postfix = "\n"), local),
            "base",
            "review",
        )
        editors.openFile(ChainDiffVirtualFile(SimpleDiffRequestChain(request), "Review"), true)
        var review: Editor? = null
        PlatformTestUtil.waitWithEventsDispatching("the review diff never showed its text", {
            review = EditorFactory.getInstance().allEditors.firstOrNull { it.getUserData(ReviewDiffMarkers.PROJECT_PATH) == TARGET }
            review != null
        }, 10)
        window.settle()
        return review!!
    }

    private companion object {
        const val TARGET = "src/review_target.py"
        val BEFORE = listOf(
            "def parse(text: str) -> list[str]:",
            "    return text.split(\",\")",
            "",
            "",
            "def total(values: list[int]) -> int:",
            "    return sum(values)",
            "",
            "",
            "def legacy_helper() -> None:",
            "    pass",
        )

        // Three lines on top move every kept line down by three; legacy_helper is gone.
        val AFTER = listOf("import csv", "", "") + BEFORE.take(6)
        const val LINE_OF_TOTAL_IN_REVIEW = 8
    }
}
