package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.GREETING
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.REVIEW_FILES
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.SHOWS_TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET_AFTER
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Navigate › Select In › Marginalis (MARG-190): from a file's editor or a review diff, the tool window selects the
 * thread on the caret's line, else the file; a file without threads does not offer Marginalis at all.
 */
class SelectInMarginalisTest : SandboxScenario("select_in_marginalis") {

    private lateinit var step: String

    @Test
    @Order(1)
    fun build() = case("build") {
        SandboxUi.showToolWindow()
        step = Agent.agentAddsStep(TARGET, 6, "Step 1: total sums the values.", 1, "review")
        Agent.agentAddsLineThread(TARGET, 2, "parse splits on commas")
        SandboxUi.closeAllEditors()
        SandboxUi.openInEditor(TARGET)
        pause()
    }

    @Test
    @Order(2)
    fun fromTheEditorItSelectsTheStepOnTheCaretLine() = case("from_the_editor_it_selects_the_step_on_the_caret_line") {
        ToolWindowUi.selectDir("Open", "src")
        SandboxUi.moveCaret(TARGET, 6)
        val selectIn = SandboxUi.selectInMarginalisFromEditor(TARGET)
        check("Select In lists Marginalis for a file with threads", selectIn.offered, selectIn)
        val got = readUntil("the step selected", read = { ToolWindowUi.selectedStep() }) { it?.thread == step }
        ToolWindowUi.screenshotToolWindow(shots.resolve("1-step.png"))
        check("the step on the caret's line is selected", got?.thread == step, got)
        check("…in its Guided section", got?.section.orEmpty().startsWith("Guided"), got)
    }

    @Test
    @Order(3)
    fun onALineWithoutAThreadItSelectsTheFile() = case("on_a_line_without_a_thread_it_selects_the_file") {
        SandboxUi.moveCaret(TARGET, 4)
        SandboxUi.selectInMarginalisFromEditor(TARGET)
        val name = readUntil("the file selected", read = { ToolWindowUi.selectedNodeName() }) { it == "review_target.py" }
        check("on a line without a thread the file is selected", name == "review_target.py", name)
    }

    @Test
    @Order(4)
    fun aFileWithoutThreadsDoesNotOfferMarginalis() = case("a_file_without_threads_does_not_offer_marginalis") {
        SandboxUi.openInEditor(GREETING)
        pause()
        val selectIn = SandboxUi.selectInMarginalisFromEditor(GREETING)
        check("setup: Select In asks about greeting.py", selectIn.file.endsWith(GREETING), selectIn)
        check("Select In does not list Marginalis for a file without threads", !selectIn.offered, selectIn)
    }

    @Test
    @Order(5)
    fun fromAReviewDiffItSelectsTheThreadOnTheCaretRow() = case("from_a_review_diff_it_selects_the_thread_on_the_caret_row") {
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown = SHOWS_TARGET)
        val shown = waitUntil("the review diff shows review_target.py") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        check("setup: the review diff shows review_target.py", shown)
        SandboxUi.showToolWindow()
        ToolWindowUi.selectDir("Open", "src")
        putCaretInReview(TOTAL_IN_REVIEW)
        val selectIn = SandboxUi.selectInMarginalisFromDiff(TARGET_AFTER)
        check("Select In lists Marginalis in a review diff of a file with threads", selectIn.offered, selectIn)
        val got = readUntil("the step selected", read = { ToolWindowUi.selectedStep() }) { it?.thread == step }
        ToolWindowUi.screenshotToolWindow(shots.resolve("2-review-diff.png"))
        check("the thread on the caret's row of the review's text is selected", got?.thread == step, got)
    }

    private fun putCaretInReview(line: Int) {
        SandboxUi.js(
            SandboxUi.EDITORS + """
            var editor = diffEditorShowing(${SandboxUi.lit(TARGET_AFTER)});
            if (editor == null) throw 'no diff editor shows that text';
            asUser(function () { editor.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition($line, 0)); });
            String(true)
            """,
        )
    }

    private companion object {
        // 0-based line in TARGET_AFTER: the review's copy moves every line down by three.
        const val TOTAL_IN_REVIEW = 8

        fun pause() = Thread.sleep(1_500)
    }
}
