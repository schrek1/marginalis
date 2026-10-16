package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.transport.HttpContractTestCase

class NoEmptyMarginTabTest : HttpContractTestCase() {

    private lateinit var tab: MarginTab

    override fun setUp() {
        super.setUp()
        sample()
        tab = MarginTab(project, testRootDisposable)
    }

    fun testTheAgentsFirstProjectThreadOpensTheTabUnpinnedBehindTheFileYouAreReading() {
        tab.openFileInFront(SAMPLE)
        assertFalse(tab.isOpen)

        agent.add("body" to "A plan for the whole project")
        tab.settle()

        assertEquals(listOf(true, false, false), listOf(tab.isOpen, tab.isPinned, tab.isInFront))
    }

    fun testAnAgentsFileThreadLeavesTheTabClosed() {
        agent.addOnLine(5, "body" to "About one line")
        tab.settle()

        assertFalse(tab.isOpen)
    }

    fun testAProjectsFirstOpenWithAnOpenProjectThreadOpensTheTabUnpinned() {
        agent.add("body" to "Waiting for you when the project opens")
        tab.close()
        PropertiesComponent.getInstance(project).unsetValue(INTRODUCED)

        tab.openOnStartup()

        assertEquals(listOf(true, false), listOf(tab.isOpen, tab.isPinned))
    }

    private companion object {
        // What ProjectTab keeps to tell a project's first open from a later one.
        const val INTRODUCED = "marginalis.projectTab.introduced"
    }
}
