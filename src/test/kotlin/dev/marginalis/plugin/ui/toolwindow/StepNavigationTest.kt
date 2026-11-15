package dev.marginalis.plugin.ui.toolwindow

import dev.marginalis.plugin.testing.WalkthroughFixture
import javax.swing.tree.TreePath

class StepNavigationTest : ToolWindowTestCase() {

    private lateinit var steps: Map<Int, String>

    override fun setUp() {
        super.setUp()
        steps = deep()
    }

    fun testNextKeepsTheSelectionThroughTheRefreshAfterEveryPress() {
        select("(16/33)")

        val selectedAfterEachRefresh = (1..8).map {
            window.press("Next Step")
            refreshTheTree()
            window.selected()
        }

        assertEquals(emptyList<Int>(), selectedAfterEachRefresh.indices.filter { selectedAfterEachRefresh[it] == null })
    }

    fun testNextWalksUpTheStepNumbersWithoutJumpingBack() {
        select("(16/33)")

        val walked = listOf(selectedStep()) + (1..8).map {
            window.press("Next Step")
            selectedStep()
        }

        val from = OPEN_STEPS.indexOf(16)
        assertEquals(OPEN_STEPS.subList(from, from + 9), walked)
    }

    fun testPreviousWalksDownTheStepNumbersWithoutJumpingBack() {
        select("(22/33)")

        val walked = listOf(selectedStep()) + (1..3).map {
            window.press("Previous Step")
            selectedStep()
        }

        val at = OPEN_STEPS.indexOf(22)
        assertEquals(OPEN_STEPS.subList(at - 3, at + 1).reversed(), walked)
    }

    fun testFromTheMiddleOfTheWalkFirstAndLastLandOnTheLowestAndHighestOpenStep() {
        select("(20/33)")

        window.press("First Step")
        val first = selectedStep()
        window.press("Last Step")
        val last = selectedStep()

        assertEquals(OPEN_STEPS.first() to OPEN_STEPS.last(), first to last)
    }

    private fun refreshTheTree() = window.rebuiltBy {
        val anyStep = store.threads.byId(steps.getValue(OPEN_STEPS.last()))!!
        store.threads.notifyChanged(anyStep)
    }

    private fun select(text: String, under: String? = null) {
        val row = window.rowOf(text, under)
        window.tree.selectionPath = TreePath(window.tree.getPathForRow(row).path)
        window.settle()
    }

    private fun selectedStep(): Int? = window.selected()?.let { STEP_PREFIX.find(it)?.groupValues?.get(1)?.toInt() }

    private companion object {
        val STEP_PREFIX = Regex("""\((\d+)/33\)""")
        val OPEN_STEPS: List<Int> = WalkthroughFixture.deep().let { walk ->
            walk.steps.map { it.order }.filterNot { it in walk.resolved }.sorted()
        }
    }
}
