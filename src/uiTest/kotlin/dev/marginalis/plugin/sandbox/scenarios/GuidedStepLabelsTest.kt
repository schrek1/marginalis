package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Test

/**
 * Two walkthroughs open at once: every step shows just `(n/total)`, the section title carries the label.
 */
class GuidedStepLabelsTest : SandboxScenario("guided_step_labels") {

    @Test
    fun twoWalkthroughsLabelTheirSectionsNotTheirSteps() = case("run") {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        listOf(2, 6, 9).forEachIndexed { index, line ->
            Agent.agentAddsStep(ROUND_TWO_FILE, line, "Round 2, step ${index + 1}", index + 1, ROUND_TWO)
        }

        val prefixes = readUntil("both walkthroughs listed with their step numbers", read = { ToolWindowUi.stepPrefixes() }) { shown ->
            val first = firstRoundOf(shown)
            secondRoundOf(shown) == ROUND_TWO_PREFIXES && first.size == 24 && first.all { NUMBER_ONLY.matches(it) }
        }
        report("INFO  sections: ${prefixes.keys}")
        val firstRound = firstRoundOf(prefixes)
        val secondRound = secondRoundOf(prefixes)
        check("both walkthroughs have their own section", firstRound.isNotEmpty() && secondRound.isNotEmpty(), prefixes.keys)
        check("round 2 steps show just their number", secondRound == ROUND_TWO_PREFIXES, secondRound)
        val odd = firstRound.filterNot { NUMBER_ONLY.matches(it) }
        check("round 1 steps show just their number", odd.isEmpty() && firstRound.size == 24, odd.take(3).ifEmpty { firstRound.size })
        ToolWindowUi.screenshotToolWindow(shots.resolve("two-walkthroughs.png"))
    }

    private fun firstRoundOf(prefixes: Map<String, List<String>>) = prefixes["Guided ${DeepWalkthrough.label}"].orEmpty()

    private fun secondRoundOf(prefixes: Map<String, List<String>>) = prefixes["Guided $ROUND_TWO"].orEmpty()

    private companion object {
        const val ROUND_TWO = "SHOP-42 · round 2 – split the importer"
        const val ROUND_TWO_FILE = "src/review_target.py"
        val ROUND_TWO_PREFIXES = listOf("(1/3)", "(2/3)", "(3/3)")
        val NUMBER_ONLY = Regex("""\(\d+/\d+\)""")
    }
}
