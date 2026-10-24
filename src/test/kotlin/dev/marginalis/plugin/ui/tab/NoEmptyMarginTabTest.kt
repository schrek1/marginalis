package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.transport.HttpContractTestCase

class NoEmptyMarginTabTest : HttpContractTestCase() {

    private lateinit var tab: MarginTab

    override fun setUp() {
        super.setUp()
        sample()
        tab = MarginTab(project, testRootDisposable)
    }

    fun testAnAgentsFileThreadLeavesTheTabClosed() {
        agent.addOnLine(5, "body" to "About one line")
        tab.settle()

        assertFalse(tab.isOpen)
    }
}
