package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.VirtualFileManager

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

    fun testAReloadedPluginBringsAnUnpinnedTabBackUnpinned() {
        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
        assertTrue(tab.isInFront)
    }

    // FixMe: Known issue - #58 updating the plugin while the Margin tab is closed fails in the unload: the open thread panels and the Margin tab outlive the plugin
    fun testUpdatingThePluginWhileTheTabIsClosedReportsAnErrorAndLeavesThreadPanelsOpen() {
        sample()
        val thread = agent.addOnLine(5, "body" to "Still open in the editor")
        user.open(thread)
        tab.close()

        val failure = tab.unloadPluginForUpdate()

        assertInstanceOf(failure, IllegalArgumentException::class.java)
        assertTrue(failure!!.message, failure.message!!.contains("file is not open"))
        assertEquals(1, user.panels().size)
        assertSame(MarginFile, VirtualFileManager.getInstance().findFileByUrl("marginalis://margin"))
    }

    fun testWhileThePluginIsUnloadedTheTabCannotBeRestoredByItsAddress() {
        var found: Any? = MarginFile
        tab.reloadPlugin { found = VirtualFileManager.getInstance().findFileByUrl("marginalis://margin") }

        assertNull(found)
        assertTrue(tab.isOpen)
    }

    fun testAClosedTabStaysClosedWhenTheProjectOpensAgain() {
        tab.close()

        tab.openOnStartup()

        assertFalse(tab.isOpen)
    }
}
