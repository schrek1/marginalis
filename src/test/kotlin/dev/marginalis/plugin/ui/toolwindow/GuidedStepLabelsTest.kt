package dev.marginalis.plugin.ui.toolwindow

class GuidedStepLabelsTest : ToolWindowTestCase() {

    override fun setUp() {
        super.setUp()
        deep()
        sample()
        for ((order, line) in listOf(1 to 2, 2 to 6, 3 to 9)) {
            agent.addOnLine(line, "order" to order, "walkthrough" to ROUND_TWO, "body" to "Round 2, step $order")
        }
    }

    fun testEachWalkthroughHasItsOwnSectionTitledByItsLabel() {
        assertEquals(listOf("Guided SHOP-42  24", "Guided $ROUND_TWO  3", "Open  27", "Resolved  9"), sections())
    }

    fun testSecondRoundStepsShowJustTheirNumberOutOfThatRound() {
        assertEquals(listOf("(1/3)", "(2/3)", "(3/3)"), prefixes("Guided $ROUND_TWO"))
    }

    fun testFirstRoundStepsShowJustTheirNumberOutOfTheWholeWalk() {
        val prefixes = prefixes("Guided SHOP-42  ")

        assertEquals(24, prefixes.size)
        assertEquals(emptyList<String>(), prefixes.filterNot { Regex("""\(\d+/33\)""").matches(it) })
    }

    // Everything between the turn signal and the location: a label glued to the number shows up here.
    private fun prefixes(title: String): List<String> = section(title).lines()
        .map { it.trim() }
        .filter { it.startsWith("✉ (") }
        .map { it.removePrefix("✉ ").substringBefore("  ") }

    private companion object {
        const val ROUND_TWO = "SHOP-42 · round 2 – split the importer"
    }
}
