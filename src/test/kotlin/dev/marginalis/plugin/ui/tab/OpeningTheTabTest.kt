package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager

class OpeningTheTabTest : MarginTabTestCase() {

    fun testAProjectsFirstOpenWithoutProjectThreadsLeavesTheTabClosed() {
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        openForTheFirstTime()

        assertFalse(tab.isOpen)
    }

    fun testAProjectsFirstOpenWithAnOpenProjectThreadOpensTheTabUnpinnedBehindTheFileYouAreReading() {
        sample()
        agent.ask("Which release name?")
        tab.close()
        tab.openFileInFront(SAMPLE)

        openForTheFirstTime()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
    }

    fun testARevealWhileTheTabOpensBehindTheFileYouAreReadingLeavesTheTabInFront() {
        sample()
        val thread = user.thread(agent.ask("Which release name?"))
        tab.close()
        tab.openFileInFront(SAMPLE)
        // Opening the tab runs queued EDT events while its editor is built; a reveal can be one of them.
        ApplicationManager.getApplication().invokeLater { ProjectTab.reveal(project, thread) }

        ProjectTab.showBehindSelection(project)
        tab.settle()

        assertTrue(tab.isInFront)
    }

    fun testAReloadedPluginBringsThePinnedTabBackBehindTheFileYouAreReading() {
        sample()
        tab.pin()
        tab.openFileInFront(SAMPLE)

        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertTrue(tab.isPinned)
        assertFalse(tab.isInFront)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
        assertEquals(listOf("Margin", "Sample.kt"), tab.openTabs())
    }

    fun testAReloadedPluginBringsAnUnpinnedTabBackUnpinned() {
        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
        assertTrue(tab.isInFront)
    }

    fun testUpdatingThePluginWhileTheTabIsClosedTakesThreadPanelsAndTheTabOutOfTheIde() {
        sample()
        val thread = agent.addOnLine(5, "body" to "Still open in the editor")
        user.open(thread)
        tab.close()
        val address = tab.file.url
        var tabAddressWhileUnloading: Any? = Unit

        val failure = tab.unloadPluginForUpdate {
            tabAddressWhileUnloading = VirtualFileManager.getInstance().findFileByUrl(address)
        }

        assertNull(failure)
        assertEquals(emptyList<Any>(), user.panels())
        assertNull(tabAddressWhileUnloading)
    }

    fun testWhileThePluginIsUnloadedTheTabCannotBeRestoredByItsAddress() {
        val address = tab.file.url
        var found: Any? = tab.file
        tab.reloadPlugin { found = VirtualFileManager.getInstance().findFileByUrl(address) }

        assertNull(found)
        assertTrue(tab.isOpen)
    }

    fun testAReloadedPluginBringsBackEveryProjectsTab() {
        withTwin { second, there ->
            there.pin()

            tab.reloadPlugin()
            there.openOnStartup()

            assertEquals(listOf("marginalis://margin/${project.locationHash}"), tab.marginAddresses())
            assertEquals(listOf("marginalis://margin/${second.project.locationHash}"), there.marginAddresses())
            assertTrue(there.isPinned)
        }
    }

    fun testAClosedTabStaysClosedWhenTheProjectOpensAgain() {
        tab.close()

        tab.openOnStartup()

        assertFalse(tab.isOpen)
    }

    private fun openForTheFirstTime() {
        PropertiesComponent.getInstance(project).unsetValue("marginalis.projectTab.introduced")
        tab.openOnStartup()
    }
}
