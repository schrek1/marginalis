package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.VirtualFileManager
import dev.marginalis.plugin.testing.Agent

class EveryProjectHasItsOwnTabTest : MarginTabTestCase() {

    fun testBothProjectsShowTheirMarginTabWithoutAnError() {
        withTwin { _, there ->
            assertTrue(tab.isOpen)
            assertTrue(there.isOpen)
            assertNotSame(tab.file, there.file)
        }
    }

    fun testEachTabShowsItsOwnProjectsThreadsAndTitle() {
        agent.ask("Which release name?")
        withTwin { second, there ->
            val theirAgent = Agent(second.project, "Claude", "claude-main")
            theirAgent.ask("Ship on Friday?")
            theirAgent.ask("Who reviews the release?")

            assertEquals("✉ 1", tab.title())
            assertEquals("✉ 2", there.title())
            assertTrue(tab.outline().contains("Which release name?"))
            assertFalse(tab.outline().contains("Ship on Friday?"))
            assertTrue(there.outline().contains("Ship on Friday?"))
            assertTrue(there.outline().contains("Who reviews the release?"))
            assertFalse(there.outline().contains("Which release name?"))
        }
    }

    fun testEachProjectsTabHasItsOwnAddress() {
        withTwin { second, there ->
            assertEquals("marginalis://margin/${project.locationHash}", tab.file.url)
            assertEquals("marginalis://margin/${second.project.locationHash}", there.file.url)
        }
    }

    fun testClosingOneProjectLeavesTheOthersTabIntact() {
        agent.ask("Which release name?")
        withTwin { second, _ -> second.closeProject() }

        assertTrue(tab.isOpen)
        assertTrue(tab.editor.isValid)
        assertEquals("✉ 1", tab.title())
        assertTrue(tab.outline().contains("Which release name?"))
    }

    fun testAClosedProjectsTabIsForgotten() {
        withTwin { second, there ->
            val theirs = there.file
            second.closeProject()

            assertFalse(theirs.isValid)
            assertNotSame(theirs, VirtualFileManager.getInstance().findFileByUrl(theirs.url))
        }
    }
}
