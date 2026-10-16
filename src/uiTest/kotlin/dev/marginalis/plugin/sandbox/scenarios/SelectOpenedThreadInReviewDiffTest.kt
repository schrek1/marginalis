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
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Select Opened Thread in the tool window header works with a review diff in front (MARG-60); a port of
 * `sandbox/scenarios/select_opened_thread_in_review_diff.py`.
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
        val shown = waitUntil { ReviewDiffUi.diffShows(TARGET_AFTER) }
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
        val kind = SandboxUi.selectedEditorKind()
        check("the review diff is in front", kind == "DIFF", kind)
        check("Select Opened Thread is enabled with a review diff in front", ToolWindowUi.isToolWindowActionEnabled(BUTTON))
    }

    @Test
    @Order(3)
    fun buttonSelectsTheThreadOnTheCaretLine() = case("button_selects_the_thread_on_the_caret_line") {
        putCaretInReview(TOTAL_IN_REVIEW)
        ToolWindowUi.pressToolWindowAction(BUTTON)
        pause()
        val got = ToolWindowUi.selectedStep()
        ToolWindowUi.screenshotToolWindow(shots.resolve("1-thread.png"))
        check("on the line of a thread in the review's text it selects that thread", got?.thread == total, got)
    }

    @Test
    @Order(4)
    fun buttonSelectsTheFileElsewhere() = case("button_selects_the_file_elsewhere") {
        putCaretInReview(IMPORT_IN_REVIEW)
        ToolWindowUi.pressToolWindowAction(BUTTON)
        pause()
        val name = ToolWindowUi.selectedNodeName()
        check("on a line without a thread it selects the file", name == "review_target.py", name)
    }

    @Test
    @Order(5)
    fun buttonSelectsTheThreadWhoseReplyBoxHasTheFocus() = case("button_selects_the_thread_whose_reply_box_has_the_focus") {
        SandboxUi.openThreadInDiff(total, TARGET_AFTER)
        waitUntil { total in SandboxUi.threadIdsOpenInDiff(TARGET_AFTER) }
        putCaretInReview(IMPORT_IN_REVIEW)
        ToolWindowUi.selectDir("Open", "src")
        val focused = focusReplyBox(total)
        check("setup: the step's reply box holds the focus", focused == "true", focused)
        ToolWindowUi.pressToolWindowAction(BUTTON)
        pause()
        val got = ToolWindowUi.selectedStep()
        ToolWindowUi.screenshotToolWindow(shots.resolve("2-reply-box.png"))
        check("with the caret in a thread's reply box it selects that thread, not the caret line's file", got?.thread == total, got)
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

    /**
     * Opens the thread's reply box and makes it the frame's focus owner. The sandbox IDE runs in the background, so a
     * focus request alone is not kept: the frame's most recent focus owner is recorded, as the window would.
     */
    private fun focusReplyBox(threadId: String): String {
        SandboxUi.js(
            SandboxUi.FIND + """
            var link = find(component, function (c) {
                return c instanceof com.intellij.ui.components.ActionLink && c.getText() == 'Reply…';
            });
            if (link != null) link.doClick();
            String(true)
            """,
            SandboxUi.threadPanel(threadId),
        )
        Thread.sleep(1_000)
        return SandboxUi.js(
            SandboxUi.FIND + """
            var area = find(component, function (c) { return c instanceof com.intellij.ui.EditorTextField; });
            var target = area.getEditor().getContentComponent();
            target.requestFocusInWindow();
            var window = javax.swing.SwingUtilities.getWindowAncestor(target);
            if (window.getMostRecentFocusOwner() !== target) {
                var types = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Class'), 1);
                types[0] = java.lang.Class.forName('java.awt.Component');
                var remember = java.lang.Class.forName('java.awt.KeyboardFocusManager').getDeclaredMethod('setMostRecentFocusOwner', types);
                remember.setAccessible(true);
                var args = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Object'), 1);
                args[0] = target;
                remember.invoke(null, args);
            }
            String(window.getMostRecentFocusOwner() === target)
            """,
            SandboxUi.threadPanel(threadId),
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
