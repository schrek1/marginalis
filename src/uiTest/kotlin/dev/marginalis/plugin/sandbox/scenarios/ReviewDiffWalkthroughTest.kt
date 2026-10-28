package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.ReviewDiffUi.ReviewFile
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.safe
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

/**
 * A guided walkthrough followed inside a pull/merge request review diff stays in that diff (MARG-17).
 *
 * The review diff is the platform's `MutableDiffRequestProcessor` over two project files, both shown as revision
 * copies (the review branch is not checked out), built by [ReviewDiffUi]. Each case opens a fresh diff, opens its
 * starting step in the diff, presses a step button of that thread, and reads where the next step ended up.
 */
class ReviewDiffWalkthroughTest : SandboxScenario("review_diff_walkthrough") {

    private lateinit var steps: Map<String, String>
    private var reviewDiffReady = false

    @BeforeAll
    fun agentPostsTheWalkthroughs() {
        SandboxUi.showToolWindow()
        // Line numbers are 1-based in the local files; the review's copies shift them (see the *_AFTER texts).
        steps = mapOf(
            "parse" to Agent.agentAddsStep(TARGET, 2, "Step 1: parse splits on commas.", 1, "review"),
            "total" to Agent.agentAddsStep(TARGET, 6, "Step 2: total sums the values.", 2, "review"),
            "farewell" to Agent.agentAddsStep(GREETING, 6, "Step 3: farewell keeps its wording.", 3, "review"),
            "legacy" to Agent.agentAddsStep(TARGET, 9, "Step 4: legacy_helper, which the review removes.", 4, "review"),
            "entry" to Agent.agentAddsStep(GREETING, 9, "Step 1: the entry point.", 1, "outside"),
            "outside" to Agent.agentAddsStep(OUTSIDE, 10, "Step 2: a file the review does not touch.", 2, "outside"),
        )
        Thread.sleep(1_000)
        reviewDiffReady = try {
            harnessBuildsAReviewDiff()
        } catch (error: Exception) {
            check("harness: the review diff opens", false, "${error::class.simpleName}: ${error.message}")
            false
        }
    }

    @Test
    @Order(1)
    fun nextStepInTheSameFile() = walkthroughCase("next_step_in_the_same_file") {
        // GIVEN step 1 open in the review diff of review_target.py (a revision copy)
        userReadsInTheReviewDiff("parse", SHOWS_TARGET)
        // WHEN the user presses Next Step
        SandboxUi.pressPanelAction(steps.getValue("parse"), "Next Step")
        // THEN step 2 opens in the same diff, at its line in the review's text
        expectStepInDiff("same file, revision copy", "total", TARGET_AFTER, line = 8)
    }

    @Test
    @Order(2)
    fun nextStepInAnotherFileOfTheReview() = walkthroughCase("next_step_in_another_file_of_the_review") {
        userReadsInTheReviewDiff("total", SHOWS_TARGET)
        SandboxUi.pressPanelAction(steps.getValue("total"), "Next Step")
        expectStepInDiff("next, another file of the review", "farewell", GREETING_AFTER, line = 6)
    }

    @Test
    @Order(3)
    fun previousStepBackToTheFirstFile() = walkthroughCase("previous_step_back_to_the_first_file") {
        userReadsInTheReviewDiff("farewell", SHOWS_GREETING)
        SandboxUi.pressPanelAction(steps.getValue("farewell"), "Previous Step")
        expectStepInDiff("previous, back to the first file", "total", TARGET_AFTER, line = 8)
    }

    @Test
    @Order(4)
    fun stepWhoseAnchorTheReviewRemoved() = walkthroughCase("step_whose_anchor_the_review_removed") {
        // The review shows review_target.py, but without legacy_helper: the step has nothing to anchor on there.
        userReadsInTheReviewDiff("farewell", SHOWS_GREETING)
        SandboxUi.pressPanelAction(steps.getValue("farewell"), "Next Step")
        expectStepInFileTab("anchor not in the review's text", "legacy", TARGET)
    }

    @Test
    @Order(5)
    fun stepInAFileOutsideTheReview() = walkthroughCase("step_in_a_file_outside_the_review") {
        userReadsInTheReviewDiff("entry", SHOWS_GREETING)
        SandboxUi.pressPanelAction(steps.getValue("entry"), "Next Step")
        expectStepInFileTab("file outside the review", "outside", OUTSIDE)
    }

    @Test
    @Order(6)
    fun resolveMovesToAnotherFileOfTheReview() = walkthroughCase("resolve_moves_to_another_file_of_the_review") {
        // Last: it resolves step 2, which the other cases walk through.
        userReadsInTheReviewDiff("total", SHOWS_TARGET)
        SandboxUi.pressPanelAction(steps.getValue("total"), "Resolve")
        expectStepInDiff("resolve, another file of the review", "farewell", GREETING_AFTER, line = 6)
    }

    private fun walkthroughCase(name: String, body: () -> Unit) = case(name) {
        if (!reviewDiffReady) error("the sandbox could not build a review diff; no walkthrough case ran")
        body()
    }

    private fun harnessBuildsAReviewDiff(): Boolean {
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown = SHOWS_TARGET)
        val shown = waitUntil("the review diff shows review_target.py") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        val processor = ReviewDiffUi.selectedDiffProcessor()
        check("harness: the review diff runs on MutableDiffRequestProcessor", processor == ReviewDiffUi.PROCESSOR, processor)
        check("harness: the review diff shows its first file", shown)
        return processor == ReviewDiffUi.PROCESSOR && shown
    }

    private fun userReadsInTheReviewDiff(step: String, shown: Int) {
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown)
        val after = REVIEW_FILES[shown].after
        awaitUntil("the review diff shows ${REVIEW_FILES[shown].path}") { ReviewDiffUi.diffShows(after) }
        SandboxUi.openThreadInDiff(steps.getValue(step), after)
        awaitUntil("step $step opens in the review diff") { steps.getValue(step) in SandboxUi.threadIdsOpenInDiff(after) }
    }

    private fun expectStepInDiff(case: String, step: String, after: String, line: Int) {
        val opened = waitUntil("step $step opens in the review diff") { steps.getValue(step) in SandboxUi.threadIdsOpenInDiff(after) }
        shot(case)
        val kind = SandboxUi.selectedEditorKind()
        check("$case: the review diff stays in front", kind == "DIFF", kind)
        check("$case: the diff shows the step's file", ReviewDiffUi.diffShows(after))
        check("$case: the step's thread opens in the diff", opened, safe { SandboxUi.threadIdsOpenInDiff(after) })
        val caret = safe { readUntil("$case: the caret on the step's line", read = { ReviewDiffUi.diffCaretLine(after) }) { it == line } }
        check("$case: the diff scrolls to the step's line in the review's text", caret == line, caret)
    }

    private fun expectStepInFileTab(case: String, step: String, file: String) {
        val opened = waitUntil("step $step opens in the tab of $file") { steps.getValue(step) in ReviewDiffUi.threadIdsOpenInFileTab(file) }
        val fileTab = "TEXT:${file.substringAfterLast('/')}"
        val kind = readUntil("$case: the file's own tab in front", read = { SandboxUi.selectedEditorKind() }) { it == fileTab }
        shot(case)
        check("$case: the step opens in the file's own tab", kind == fileTab, kind)
        check("$case: the step's thread opens there", opened, safe { ReviewDiffUi.threadIdsOpenInFileTab(file) })
    }

    /** The review: also the setup of [SelectThreadInToolWindowTest] and [SelectInMarginalisTest]. */
    internal companion object {
        const val TARGET = "src/review_target.py"
        const val GREETING = "src/greeting.py"
        const val OUTSIDE = "src/long_module.py"
        const val SHOWS_TARGET = 0
        const val SHOWS_GREETING = 1

        // The review's version: three lines on top (every surviving line moves down by three), legacy_helper removed.
        val TARGET_AFTER = lines(
            "import csv",
            "",
            "",
            "def parse(text: str) -> list[str]:",
            "    return text.split(\",\")",
            "",
            "",
            "def total(values: list[int]) -> int:",
            "    return sum(values)",
        )

        // The review's version: a new greeting, and a docstring that moves farewell's body down by one.
        val GREETING_AFTER = lines(
            "def greet(name: str) -> str:",
            "    return f\"Hi, {name}!\"",
            "",
            "",
            "def farewell(name: str) -> str:",
            "    \"\"\"Says goodbye.\"\"\"",
            "    return f\"Goodbye, {name}.\"",
            "",
            "",
            "if __name__ == \"__main__\":",
            "    print(greet(\"sandbox\"))",
            "    print(farewell(\"sandbox\"))",
        )

        val REVIEW_FILES = listOf(
            ReviewFile(TARGET, templateText(TARGET), TARGET_AFTER),
            ReviewFile(GREETING, templateText(GREETING), GREETING_AFTER),
        )

        fun lines(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

        // A Windows checkout carries CRLF; the diff's documents hold LF.
        fun templateText(file: String) = Sandbox.harness.resolve("project-template").resolve(file).readText().replace("\r\n", "\n")
    }
}
