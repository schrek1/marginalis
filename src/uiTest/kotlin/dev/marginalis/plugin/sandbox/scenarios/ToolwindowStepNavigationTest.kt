package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import org.junit.jupiter.api.Test

/**
 * Walking the Guided section with the tool window's arrows on a review-sized walkthrough (the deep walkthrough
 * fixture); a port of `sandbox/scenarios/toolwindow_step_navigation.py`. Records what each press selects, so a jump
 * back to the first node or an order that ignores step numbers shows up.
 */
class ToolwindowStepNavigationTest : SandboxScenario("toolwindow_step_navigation") {

    @Test
    fun arrowsWalkTheSteps() = case("run") {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        Thread.sleep(3_000)
        val shown = ToolWindowUi.treeSteps()
        report("INFO  Guided in tree order: $shown")
        // Step navigation in Guided follows the step numbers, not the folder-and-file order the tree shows.
        val tree = shown.sorted()
        ToolWindowUi.screenshotToolWindow(shots.resolve("tree.png"))

        val down = walk("next", 16, NEXT, 8)
        val expectedDown = tree.drop(tree.indexOf(16)).take(9)
        check("Next keeps a selection after every press", null !in down, down)
        check("Next walks up the step numbers without jumping back", down == expectedDown, "got $down, expected $expectedDown")

        val up = walk("previous", 22, PREVIOUS, 3)
        val at = tree.indexOf(22)
        val expectedUp = tree.subList(at - 3, at + 1).reversed()
        check("Previous walks down the step numbers without jumping back", up == expectedUp, "got $up, expected $expectedUp")

        ToolWindowUi.selectStep(20)
        humanPause()
        ToolWindowUi.pressToolWindowAction("First Step")
        humanPause()
        val first = ToolWindowUi.selectedStep()
        check("First Step lands on the lowest open step", first?.order == tree.first(), first)

        ToolWindowUi.pressToolWindowAction("Last Step")
        humanPause()
        ToolWindowUi.pokeAThread()
        Thread.sleep(500)
        val last = ToolWindowUi.selectedStep()
        check("Last Step lands on the highest open step", last?.order == tree.last(), last)

        val openTree = ToolWindowUi.treeSteps("Open")
        report("INFO  Open in tree order: $openTree")
        val inOpen = walk("next-in-open", openTree.first(), NEXT, 4, section = "Open")
        val sections = setOfNotNull(ToolWindowUi.selectedStep()?.section)
        check("in Open, Next follows the tree, not the numbers", inOpen == openTree.take(5), "got $inOpen, tree says ${openTree.take(5)}")
        check("in Open, Next stays in Open", sections == setOf("Open"), sections)

        ToolWindowUi.selectStep(10)
        humanPause()
        ToolWindowUi.pressToolWindowAction(*NEXT)
        humanPause()
        ToolWindowUi.pressToolWindowAction(*NEXT)
        humanPause()
        val settled = ToolWindowUi.selectedStep()
        check("the selection is still there after the tree refreshes", settled?.order == 12, settled)
        ToolWindowUi.screenshotToolWindow(shots.resolve("after-last.png"))
    }

    private fun walk(label: String, start: Int, buttons: Array<String>, presses: Int, section: String = "Guided"): List<Int?> {
        ToolWindowUi.selectStep(start, section)
        humanPause()
        val seen = mutableListOf(ToolWindowUi.selectedStep())
        repeat(presses) { press ->
            ToolWindowUi.pressToolWindowAction(*buttons)
            humanPause()
            // In a real IDE opening a step marks it read, which rebuilds the tree; the sandbox sits on another desktop
            // and does not always, so the rebuild is triggered here.
            ToolWindowUi.pokeAThread()
            Thread.sleep(500)
            seen += ToolWindowUi.selectedStep()
            if (press == 2) ToolWindowUi.screenshotToolWindow(shots.resolve("$label-after-3.png"))
        }
        val orders = seen.map { it?.order }
        report("INFO  $label from $start: $orders")
        return orders
    }

    private companion object {
        val NEXT = arrayOf("Next Step", "Next Occurrence")
        val PREVIOUS = arrayOf("Previous Step", "Previous Occurrence")

        fun humanPause() = Thread.sleep(1_500)
    }
}
