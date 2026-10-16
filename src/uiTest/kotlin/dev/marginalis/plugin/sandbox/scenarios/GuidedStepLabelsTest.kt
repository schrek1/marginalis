package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import org.junit.jupiter.api.Test

/**
 * Two walkthroughs open at once: every step shows just `(n/total)`, the section title carries the label; a port of
 * `sandbox/scenarios/guided_step_labels.py`.
 */
class GuidedStepLabelsTest : SandboxScenario("guided_step_labels") {

    @Test
    fun twoWalkthroughsLabelTheirSectionsNotTheirSteps() = case("run") {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        listOf(2, 6, 9).forEachIndexed { index, line ->
            Agent.agentAddsStep(ROUND_TWO_FILE, line, "Round 2, step ${index + 1}", index + 1, ROUND_TWO)
        }
        Thread.sleep(4_000)

        val prefixes = ToolWindowUi.stepPrefixes()
        report("INFO  sections: ${prefixes.keys}")
        val firstRound = prefixes["Guided ${DeepWalkthrough.label}"].orEmpty()
        val secondRound = prefixes["Guided $ROUND_TWO"].orEmpty()
        check("both walkthroughs have their own section", firstRound.isNotEmpty() && secondRound.isNotEmpty(), prefixes.keys)
        check("round 2 steps show just their number", secondRound == listOf("(1/3)", "(2/3)", "(3/3)"), secondRound)
        val odd = firstRound.filterNot { NUMBER_ONLY.matches(it) }
        check("round 1 steps show just their number", odd.isEmpty() && firstRound.size == 24, odd.take(3).ifEmpty { firstRound.size })
        ToolWindowUi.screenshotToolWindow(shots.resolve("two-walkthroughs.png"))
    }

    private companion object {
        const val ROUND_TWO = "SHOP-42 · round 2 – split the importer"
        const val ROUND_TWO_FILE = "src/review_target.py"
        val NUMBER_ONLY = Regex("""\(\d+/\d+\)""")
    }
}
