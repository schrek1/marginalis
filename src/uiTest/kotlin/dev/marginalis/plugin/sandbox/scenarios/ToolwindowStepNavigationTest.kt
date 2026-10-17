package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Test

/**
 * Walking the Guided section with the tool window's arrows on a review-sized walkthrough (the deep walkthrough
 * fixture). Records what each press selects, so a jump back to the first node or an order that ignores step numbers
 * shows up.
 */
class ToolwindowStepNavigationTest : SandboxScenario("toolwindow_step_navigation") {

    @Test
    fun arrowsWalkTheSteps() = case("run") {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        val shown = readUntil("every open step in the Guided section", read = { ToolWindowUi.treeSteps() }) { it.sorted() == OPEN_STEPS }
        report("INFO  Guided in tree order: $shown")
        // Step navigation in Guided follows the step numbers, not the folder-and-file order the tree shows.
        val tree = shown.sorted()
        ToolWindowUi.screenshotToolWindow(shots.resolve("tree.png"))

        val expectedDown = tree.drop(tree.indexOf(16)).take(9)
        val down = walk("next", NEXT, expectedDown)
        check("Next keeps a selection after every press", null !in down, down)
        check("Next walks up the step numbers without jumping back", down == expectedDown, "got $down, expected $expectedDown")

        val at = tree.indexOf(22)
        val expectedUp = tree.subList(at - 3, at + 1).reversed()
        val up = walk("previous", PREVIOUS, expectedUp)
        check("Previous walks down the step numbers without jumping back", up == expectedUp, "got $up, expected $expectedUp")

        ToolWindowUi.selectStep(20)
        humanPause()
        ToolWindowUi.pressToolWindowAction("First Step")
        val first = stepSelectedUntil(tree.first())
        check("First Step lands on the lowest open step", first?.order == tree.first(), first)

        ToolWindowUi.pressToolWindowAction("Last Step")
        humanPause()
        ToolWindowUi.pokeAThread()
        // Time for the rebuild to drop the selection, which it must not.
        Thread.sleep(500)
        val last = stepSelectedUntil(tree.last())
        check("Last Step lands on the highest open step", last?.order == tree.last(), last)

        val openTree = ToolWindowUi.treeSteps("Open")
        report("INFO  Open in tree order: $openTree")
        val inOpen = walk("next-in-open", NEXT, openTree.take(5), section = "Open")
        val sections = setOfNotNull(ToolWindowUi.selectedStep()?.section)
        check("in Open, Next follows the tree, not the numbers", inOpen == openTree.take(5), "got $inOpen, tree says ${openTree.take(5)}")
        check("in Open, Next stays in Open", sections == setOf("Open"), sections)

        ToolWindowUi.selectStep(10)
        humanPause()
        ToolWindowUi.pressToolWindowAction(*NEXT)
        humanPause()
        ToolWindowUi.pressToolWindowAction(*NEXT)
        // Time for the refresh that opening a step brings to drop the selection, which it must not.
        humanPause()
        val settled = stepSelectedUntil(12)
        check("the selection is still there after the tree refreshes", settled?.order == 12, settled)
        ToolWindowUi.screenshotToolWindow(shots.resolve("after-last.png"))
    }

    /** Selects `expected`'s first step and presses `buttons` once per further step; returns the step each press selected. */
    private fun walk(label: String, buttons: Array<String>, expected: List<Int>, section: String = "Guided"): List<Int?> {
        val start = expected.first()
        ToolWindowUi.selectStep(start, section)
        val seen = mutableListOf(stepSelectedUntil(start))
        for ((press, next) in expected.drop(1).withIndex()) {
            ToolWindowUi.pressToolWindowAction(*buttons)
            humanPause()
            // In a real IDE opening a step marks it read, which rebuilds the tree; the sandbox sits on another desktop
            // and does not always, so the rebuild is triggered here.
            ToolWindowUi.pokeAThread()
            // Time for the rebuild to drop the selection, which it must not.
            Thread.sleep(500)
            seen += stepSelectedUntil(next)
            if (press == 2) ToolWindowUi.screenshotToolWindow(shots.resolve("$label-after-3.png"))
        }
        val orders = seen.map { it?.order }
        report("INFO  $label from $start: $orders")
        return orders
    }

    private fun stepSelectedUntil(order: Int): ToolWindowUi.SelectedStep? =
        readUntil("step $order selected", read = { ToolWindowUi.selectedStep() }) { it?.order == order }

    private companion object {
        val OPEN_STEPS = DeepWalkthrough.steps.map { it.order }.filter { it !in DeepWalkthrough.resolved }.sorted()
        val NEXT = arrayOf("Next Step", "Next Occurrence")
        val PREVIOUS = arrayOf("Previous Step", "Previous Occurrence")

        fun humanPause() = Thread.sleep(1_500)
    }
}
