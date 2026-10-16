package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.VirtualFileManager

class OpeningTheTabTest : MarginTabTestCase() {

    fun testAReloadedPluginBringsThePinnedTabWithAnOpenProjectThreadBackBehindTheFileYouAreReading() {
        sample()
        agent.ask("Which release name?")
        tab.pin()
        tab.openFileInFront(SAMPLE)

        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertTrue(tab.isPinned)
        assertFalse(tab.isInFront)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
        assertEquals(listOf("Margin", "Sample.kt"), tab.openTabs())
    }

    fun testAReloadedPluginBringsAnUnpinnedTabWithAnOpenProjectThreadBackUnpinned() {
        agent.ask("Which release name?")

        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
        assertTrue(tab.isInFront)
    }

    fun testAReloadedPluginLeavesATabWithNothingInItClosed() {
        sample()
        val resolved = agent.ask("Settled already")
        agent.post("comment_resolve", "thread_id" to resolved)
        tab.pin()

        tab.reloadPlugin()

        assertFalse(tab.isOpen)
    }

    // FixMe: Known issue - #58 updating the plugin while the Margin tab is closed fails in the unload: the open thread panels and the Margin tab outlive the plugin
    fun testUpdatingThePluginWhileTheTabIsClosedReportsAnErrorAndLeavesThreadPanelsOpen() {
        sample()
        val thread = agent.addOnLine(5, "body" to "Still open in the editor")
        user.open(thread)
        tab.close()
        var tabValidWhileUnloaded = false

        val failure = tab.unloadPluginForUpdate { tabValidWhileUnloaded = MarginFile.isValid }

        assertInstanceOf(failure, IllegalArgumentException::class.java)
        assertTrue(failure!!.message, failure.message!!.contains("file is not open"))
        assertEquals(1, user.panels().size)
        assertTrue(tabValidWhileUnloaded)
    }

    fun testWhileThePluginIsUnloadedTheTabCannotBeRestoredByItsAddressYetComesBackWithItsOpenThread() {
        agent.ask("Which release name?")
        var found: Any? = MarginFile
        tab.reloadPlugin { found = VirtualFileManager.getInstance().findFileByUrl(MarginFile.url) }

        assertNull(found)
        assertTrue(tab.isOpen)
    }

    fun testAClosedTabStaysClosedWhenTheProjectOpensAgainEvenWithAnOpenProjectThread() {
        agent.ask("Which release name?")
        tab.close()

        tab.openOnStartup()

        assertFalse(tab.isOpen)
    }
}
