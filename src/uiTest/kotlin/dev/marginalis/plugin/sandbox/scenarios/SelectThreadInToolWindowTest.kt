package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.ToolWindowUi.SelectedStep
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Select a thread in the tool window (fork MARG-11): "Select in Marginalis" in a thread's header, and "Select Opened
 * Thread" in the tool window header, both land on the thread's node, in the section that lists it.
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
    fun toolbarSelectsTheThreadOnTheCaretLine() = case("toolbar_selects_the_thread_on_the_caret_line") {
        SandboxUi.closeAllEditors()
        SandboxUi.openInEditor(FILE)
        pause()
        SandboxUi.moveCaret(FILE, 5)
        ToolWindowUi.pressToolWindowAction("Select Opened Thread")
        val got = selectedUntil { it?.thread == plain }
        check("Select Opened Thread selects the thread on the caret's line", got?.thread == plain, got)

        SandboxUi.moveCaret(FILE, 2)
        ToolWindowUi.pressToolWindowAction("Select Opened Thread")
        val name = readUntil("the file selected", read = { ToolWindowUi.selectedNodeName() }) { it == "review_target.py" }
        check("on a line without a thread it selects the file", name == "review_target.py", name)
    }

    private fun selectedUntil(done: (SelectedStep?) -> Boolean): SelectedStep? =
        readUntil("the thread selected in the tool window", read = { ToolWindowUi.selectedStep() }, done = done)

    private companion object {
        const val FILE = "src/review_target.py"
        const val SELECT_IN_MARGINALIS = "Select in Marginalis"

        fun pause() = Thread.sleep(1_500)
    }
}
