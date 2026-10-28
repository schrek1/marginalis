package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.ToolWindowUi.SelectedStep
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.REVIEW_FILES
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.SHOWS_TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET_AFTER
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Select a thread in the tool window (fork MARG-11, MARG-171): "Select in Marginalis" in a thread's header lands on
 * the thread's node, in the section that lists it, from an editor, a review diff or the Margin tab, wherever the
 * editor's caret is. The tool window header has no "Select Opened Thread": it guessed the thread from the caret.
 */
class SelectThreadInToolWindowTest : SandboxScenario("select_thread_in_tool_window") {

    private lateinit var steps: Map<Int, String>
    private lateinit var plain: String
    private lateinit var resolved: String

    @BeforeAll
    fun postTheThreads() {
        SandboxUi.showToolWindow()
        steps = postWalkthrough(DeepWalkthrough)
        plain = Agent.agentAddsLineThread(FILE, 5, "A thread outside the walkthrough")
        resolved = Agent.agentAddsLineThread(FILE, 9, "A thread already resolved")
        Agent.resolveThread(resolved)
        Thread.sleep(3_000)
    }

    @Test
    @Order(1)
    fun headerButtonSelectsAStepInItsGuidedSection() = case("header_button_selects_a_step_in_its_guided_section") {
        val order = 27
        val file = DeepWalkthrough.steps.single { it.order == order }.file
        SandboxUi.openInEditor(file)
        SandboxUi.openThreadInEditor(steps.getValue(order), file)
        pause()
        SandboxUi.pressPanelAction(steps.getValue(order), SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == steps.getValue(order) && it.section.startsWith("Guided") }
        ToolWindowUi.screenshotToolWindow(shots.resolve("1-step.png"))
        check("Select in Marginalis selects the step", got?.thread == steps.getValue(order), got)
        check("…in its Guided section", got?.section.orEmpty().startsWith("Guided"), got)
    }

    @Test
    @Order(2)
    fun headerButtonSelectsAPlainThreadInOpen() = case("header_button_selects_a_plain_thread_in_open") {
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(plain, FILE)
        pause()
        SandboxUi.pressPanelAction(plain, SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == plain && it.section == "Open" }
        check("a thread outside any walkthrough is selected in Open", got?.thread == plain && got.section == "Open", got)
    }

    @Test
    @Order(3)
    fun headerButtonReachesIntoTheFoldedResolvedSection() = case("header_button_reaches_into_the_folded_resolved_section") {
        // Opening an unread resolved thread marks it read, and that change closes a resolved thread's panel at once.
        SandboxUi.userReads(resolved)
        // The robot only sees panels on screen; the plain thread's open panel would push this one below the fold.
        SandboxUi.closeAllEditors()
        pause()
        SandboxUi.openInEditor(FILE)
        pause()
        SandboxUi.openThreadInEditor(resolved, FILE)
        // A freshly reopened editor lays its inlays out a moment later; the press waits for the panel on screen.
        SandboxUi.pressPanelAction(resolved, SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == resolved && it.section == "Resolved" }
        ToolWindowUi.screenshotToolWindow(shots.resolve("2-resolved.png"))
        check("a resolved thread is selected inside the folded Resolved section", got?.thread == resolved && got.section == "Resolved", got)
    }

    @Test
    @Order(4)
    fun headerHasNoSelectOpenedThread() = case("header_has_no_select_opened_thread") {
        val buttons = ToolWindowUi.headerButtons()
        check("the tool window header has no Select Opened Thread", "Select Opened Thread" !in buttons, buttons)
    }

    @Test
    @Order(5)
    fun headerButtonSelectsTheOpenStepWhereverTheCaretIs() = case("header_button_selects_the_open_step_wherever_the_caret_is") {
        // The case behind MARG-171: a step on line 1, the caret on line 2, the focus in the editor.
        val firstLine = Agent.agentAddsStep(FILE, 1, "A step on the file's first line", 1, FIRST_LINE_WALK)
        SandboxUi.closeAllEditors()
        SandboxUi.openInEditor(FILE)
        pause()
        SandboxUi.openThreadInEditor(firstLine, FILE)
        SandboxUi.moveCaret(FILE, 2)
        SandboxUi.focusEditor(FILE)
        ToolWindowUi.selectDir("Open", "src")
        SandboxUi.pressPanelAction(firstLine, SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == firstLine }
        ToolWindowUi.screenshotToolWindow(shots.resolve("3-caret-elsewhere.png"))
        check("Select in Marginalis selects its step, not the file under the caret", got?.thread == firstLine, got)
        check("…in its Guided section", got?.section == "Guided $FIRST_LINE_WALK", got)
    }

    @Test
    @Order(6)
    fun headerButtonSelectsAStepOpenInAReviewDiff() = case("header_button_selects_a_step_open_in_a_review_diff") {
        val total = Agent.agentAddsStep(TARGET, 6, "Step 1: total sums the values.", 1, "review")
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown = SHOWS_TARGET)
        val shown = waitUntil("the review diff shows review_target.py") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        check("setup: the review diff shows review_target.py", shown)
        SandboxUi.openThreadInDiff(total, TARGET_AFTER)
        awaitUntil("the step opens in the review diff") { total in SandboxUi.threadIdsOpenInDiff(TARGET_AFTER) }
        SandboxUi.showToolWindow()
        ToolWindowUi.selectDir("Open", "src")
        SandboxUi.pressPanelAction(total, SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == total }
        check("Select in Marginalis in a review diff selects its step", got?.thread == total, got)
        check("…in its Guided section", got?.section == "Guided review", got)
    }

    @Test
    @Order(7)
    fun headerButtonSelectsAProjectThreadFromTheMarginTab() = case("header_button_selects_a_project_thread_from_the_margin_tab") {
        val plan = Agent.agentAddsProjectStep("The plan for this review", 1, "plans")
        SandboxUi.openMarginTab(plan)
        ToolWindowUi.selectDir("Open", "src")
        SandboxUi.pressPanelAction(plan, SELECT_IN_MARGINALIS)
        val got = selectedUntil { it?.thread == plan }
        check("Select in Marginalis in the Margin tab selects its project thread", got?.thread == plan, got)
    }

    private fun selectedUntil(done: (SelectedStep?) -> Boolean): SelectedStep? =
        readUntil("the thread selected in the tool window", read = { ToolWindowUi.selectedStep() }, done = done)

    private companion object {
        const val FILE = "src/review_target.py"
        const val SELECT_IN_MARGINALIS = "Select in Marginalis"
        const val FIRST_LINE_WALK = "SHOP-42 findings"

        fun pause() = Thread.sleep(1_500)
    }
}
