package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.VirtualFileManager
import dev.marginalis.plugin.testing.HeldBackEditorChecks

class OpeningTheTabTest : MarginTabTestCase() {

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

    fun testAtStartupTheTabOpensWithoutWaitingForTheEditorChecksTheIdeHoldsBack() {
        // GIVEN - the pinned tab sits behind Sample.kt, and the IDE holds back its editor checks, as a write pending at startup does
        sample()
        tab.pin()
        tab.openFileInFront(SAMPLE)
        ProjectTab.closeEverywhere()
        MarginFile.reinstate()
        val heldBack = HeldBackEditorChecks.install(testRootDisposable)

        // WHEN - the project opens the tab at startup
        try {
            ProjectTab.openOnStartup(project)
            val returnedWhileHeldBack = heldBack.isHolding

            // THEN - opening returned at once, and the tab is back once the checks finish
            assertTrue("opening the tab waited on the EDT until the held-back editor check gave up", returnedWhileHeldBack)
        } finally {
            heldBack.release()
        }
        tab.settle()
        assertTrue(tab.isPinned)
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
}
