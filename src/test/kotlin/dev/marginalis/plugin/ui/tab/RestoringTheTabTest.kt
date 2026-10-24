package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vfs.VirtualFileManager

class RestoringTheTabTest : MarginTabTestCase() {

    private val ownAddress: String get() = "marginalis://margin/${project.locationHash}"

    fun testARestartedProjectFindsItsTabByItsAddress() {
        tab.close()
        tab.unloadPluginForUpdate()

        val restored = tab.restore(ownAddress)

        assertEquals(listOf(ownAddress), tab.marginAddresses())
        assertSame(restored, tab.file)
        assertTrue(tab.isOpen)
    }

    fun testATabRestoredInBothHalvesOfASplitIsListedOnceInRecentFiles() {
        tab.close()
        tab.unloadPluginForUpdate()

        tab.restore(ownAddress)
        tab.restoreInASplit(ownAddress)
        tab.selectTheTabInEachHalf()

        assertEquals(listOf(ownAddress), tab.recentMarginAddresses())
    }

    fun testAPinOnEitherHalfOfARestoredSplitSurvivesAReload() {
        tab.close()
        tab.unloadPluginForUpdate()
        tab.restore(ownAddress)
        tab.restoreInASplit(ownAddress, pinned = true)

        tab.reloadPlugin()

        assertTrue(tab.isPinned)
    }

    fun testANewProjectThreadInARestoredSplitOpensInTheFocusedHalfsOwnTab() {
        tab.close()
        tab.unloadPluginForUpdate()
        tab.restore(ownAddress)
        val right = tab.restoreInASplit(ownAddress)
        tab.focusTheHalfShowing(right)

        ProjectTab.draftNew(project)
        tab.settle()

        assertEquals(listOf(1, 1), tab.marginTabsInEachHalf())
    }

    fun testAnAddressNoProjectOwnsFindsNoTabOfThisProject() {
        assertNull(VirtualFileManager.getInstance().findFileByUrl("marginalis://elsewhere"))

        val unknown = VirtualFileManager.getInstance().findFileByUrl("marginalis://margin/no-such-project")

        assertNotSame(tab.file, unknown)
    }

    fun testAProjectSavedWithTheSharedTabBringsItBackPinnedInPlace() {
        sample()
        agent.ask("Which release name?")
        tab.close()

        tab.restore(SHARED_TAB_ADDRESS, pinned = true)
        tab.openFileInFront(SAMPLE)

        assertEquals(listOf(ownAddress), tab.marginAddresses())
        assertEquals(listOf("Margin", "Sample.kt"), tab.openTabs())
        assertTrue(tab.isPinned)
        assertEquals("✉ 1", tab.title())
        tab.bringToFront()
        assertEquals(listOf("Margin", "Sample.kt"), tab.openTabs())
    }

    fun testTwoProjectsSavedWithTheSharedTabEachGetTheirOwn() {
        tab.close()
        withTwin { second, there ->
            there.close()

            tab.restore(SHARED_TAB_ADDRESS)
            there.restore(SHARED_TAB_ADDRESS)

            assertEquals(listOf(ownAddress), tab.marginAddresses())
            assertEquals(listOf("marginalis://margin/${second.project.locationHash}"), there.marginAddresses())
        }
    }

    fun testAnUpgradedProjectListsItsTabOnceInRecentFiles() {
        tab.close()
        val history = EditorHistoryManager.getInstance(project)
        history.loadState(JDOMUtil.load("""<component name="EditorHistoryManager"><entry file="$SHARED_TAB_ADDRESS"><provider selected="true" editor-type-id="marginalis-project-tab"/></entry></component>"""))

        tab.restore(SHARED_TAB_ADDRESS)

        assertEquals(listOf(ownAddress), history.fileList.map { it.url }.filter { it.startsWith("marginalis:") })
    }

    fun testACopiedProjectCarryingAnotherOpenProjectsAddressGetsItsOwnTab() {
        withTwin { second, there ->
            there.close()

            there.restore(ownAddress)

            assertEquals(listOf("marginalis://margin/${second.project.locationHash}"), there.marginAddresses())
            assertEquals(listOf(ownAddress), tab.marginAddresses())
        }
    }

    private companion object {
        const val SHARED_TAB_ADDRESS = "marginalis://margin"
    }
}
