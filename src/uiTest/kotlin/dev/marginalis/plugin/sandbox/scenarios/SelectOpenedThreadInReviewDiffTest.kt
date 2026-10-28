package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.REVIEW_FILES
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.SHOWS_TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET_AFTER
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Select Opened Thread in the tool window header works with a review diff in front (MARG-60).
 *
 * The review diff is the one [ReviewDiffWalkthroughTest] builds: two project files shown as revision copies. The caret
 * is put on a line of the review's text, and the button is read as the user would meet it: enabled or greyed out,
 * then pressed.
 */
class SelectOpenedThreadInReviewDiffTest : SandboxScenario("select_opened_thread_in_review_diff") {

    private lateinit var total: String

    @Test
    @Order(1)
    fun build() = case("build") {
        SandboxUi.showToolWindow()
        total = Agent.agentAddsStep(TARGET, 6, "Step 1: total sums the values.", 1, "review")
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown = SHOWS_TARGET)
        val shown = waitUntil("the review diff shows review_target.py") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        check("setup: the review diff shows review_target.py", shown)
        // Opening the diff can hide the tool window.
        SandboxUi.showToolWindow()
        pause()
        // Start from somewhere else, so a selection that does not move is caught.
        ToolWindowUi.selectDir("Open", "src")
        pause()
    }

    @Test
    @Order(2)
    fun buttonIsEnabledInAReviewDiff() = case("button_is_enabled_in_a_review_diff") {
        val kind = readUntil("the review diff in front", read = { SandboxUi.selectedEditorKind() }) { it == "DIFF" }
        check("the review diff is in front", kind == "DIFF", kind)
        val enabled = readUntil("$BUTTON enabled", read = { ToolWindowUi.isToolWindowActionEnabled(BUTTON) }) { it }
        check("Select Opened Thread is enabled with a review diff in front", enabled)
    }

    @Test
    @Order(3)
    fun buttonSelectsTheThreadOnTheCaretLine() = case("button_selects_the_thread_on_the_caret_line") {
        putCaretInReview(TOTAL_IN_REVIEW)
        ToolWindowUi.pressToolWindowAction(BUTTON)
        val got = totalSelectedUntil()
        ToolWindowUi.screenshotToolWindow(shots.resolve("1-thread.png"))
        check("on the line of a thread in the review's text it selects that thread", got?.thread == total, got)
    }

    @Test
    @Order(4)
    fun buttonSelectsTheFileElsewhere() = case("button_selects_the_file_elsewhere") {
        putCaretInReview(IMPORT_IN_REVIEW)
        ToolWindowUi.pressToolWindowAction(BUTTON)
        val name = readUntil("the file selected", read = { ToolWindowUi.selectedNodeName() }) { it == "review_target.py" }
        check("on a line without a thread it selects the file", name == "review_target.py", name)
    }

    private fun totalSelectedUntil(): ToolWindowUi.SelectedStep? =
        readUntil("the step selected in the tool window", read = { ToolWindowUi.selectedStep() }) { it?.thread == total }

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
        const val BUTTON = "Select Opened Thread"

        // 0-based lines in TARGET_AFTER: the review's copy moves every line down by three, line 0 is its new import.
        const val TOTAL_IN_REVIEW = 8
        const val IMPORT_IN_REVIEW = 0

        fun pause() = Thread.sleep(1_500)
    }
}
