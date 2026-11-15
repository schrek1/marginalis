package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import dev.marginalis.core.MarginTabShows
import dev.marginalis.plugin.settings.MarginalisSettings

class WhenTheTabShowsTest : MarginTabTestCase() {

    private val ownAddress: String get() = "marginalis://margin/${project.locationHash}"

    private fun showTheTabWithSomethingInIt() {
        MarginalisSettings.getInstance().marginTabShows = MarginTabShows.WITH_SOMETHING_IN_IT
    }

    private fun keepTheTabOpen() {
        MarginalisSettings.getInstance().marginTabShows = MarginTabShows.ALWAYS
    }

    private fun openForTheFirstTime() {
        PropertiesComponent.getInstance(project).unsetValue("marginalis.projectTab.introduced")
        tab.openOnStartup()
    }

    private fun restart(pinned: Boolean = false) {
        tab.close()
        tab.unloadPluginForUpdate()
        tab.restore(ownAddress, pinned)
        tab.openOnStartup()
    }

    fun testTheTabShowsWithSomethingInItByDefault() {
        assertEquals(MarginTabShows.WITH_SOMETHING_IN_IT, MarginalisSettings.State().marginTabShows.let(MarginTabShows::fromStored))
    }

    fun testKeepingTheTabOpenAProjectsFirstOpenOpensItPinnedEvenWhenEmpty() {
        keepTheTabOpen()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        openForTheFirstTime()

        assertTrue(tab.isOpen)
        assertTrue(tab.isPinned)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
    }

    fun testKeepingTheTabOpenARestartKeepsTheRestoredEmptyTab() {
        keepTheTabOpen()
        restart()

        assertTrue(tab.isOpen)
    }

    fun testKeepingTheTabOpenAnAgentsNewProjectThreadLeavesAClosedTabClosed() {
        keepTheTabOpen()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        agent.ask("Which release name?")
        tab.settle()

        assertFalse(tab.isOpen)
    }

    fun testWithSomethingInItAProjectsFirstOpenWithoutProjectThreadsLeavesTheTabClosed() {
        showTheTabWithSomethingInIt()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        openForTheFirstTime()

        assertFalse(tab.isOpen)
    }

    fun testWithSomethingInItAProjectsFirstOpenWithAnOpenProjectThreadOpensTheTabUnpinnedBehindTheFileYouAreReading() {
        showTheTabWithSomethingInIt()
        sample()
        agent.ask("Which release name?")
        tab.close()
        tab.openFileInFront(SAMPLE)

        openForTheFirstTime()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
    }

    fun testWithSomethingInItARestartClosesTheRestoredEmptyTab() {
        showTheTabWithSomethingInIt()

        restart()

        assertFalse(tab.isOpen)
    }

    fun testKeepingTheTabOpenARevealWhileTheTabOpensOnStartupLeavesTheTabInFront() {
        sample()
        val thread = user.thread(agent.ask("Which release name?"))
        tab.close()
        tab.openFileInFront(SAMPLE)
        ApplicationManager.getApplication().invokeLater { ProjectTab.reveal(project, thread) }

        openForTheFirstTime()

        assertTrue(tab.isInFront)
    }

    fun testWithSomethingInItAPinnedEmptyTabSurvivesARestart() {
        showTheTabWithSomethingInIt()

        restart(pinned = true)

        assertTrue(tab.isOpen)
        assertTrue(tab.isPinned)
    }

    fun testWithSomethingInItARestoredTabWithAnOpenProjectThreadStays() {
        showTheTabWithSomethingInIt()
        agent.ask("Which release name?")

        restart()

        assertTrue(tab.isOpen)
    }

    fun testWithSomethingInItAReloadedPluginBringsAnUnpinnedEmptyTabBack() {
        showTheTabWithSomethingInIt()

        tab.reloadPlugin()

        assertTrue(tab.isOpen)
        assertFalse(tab.isPinned)
    }

    fun testWithSomethingInItAnAgentsNewProjectThreadOpensTheTabBehindTheFileYouAreReading() {
        showTheTabWithSomethingInIt()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        agent.ask("Which release name?")
        tab.settle()

        assertTrue(tab.isOpen)
        assertFalse(tab.isInFront)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
    }

    fun testWithSomethingInItAProjectThreadInABatchOpensTheTabBehindTheFileYouAreReading() {
        showTheTabWithSomethingInIt()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        agent.post("comment_add_batch", "items" to listOf(mapOf("body" to "Which release name?")))
        tab.settle()

        assertTrue(tab.isOpen)
        assertEquals("Sample.kt", tab.editors.selectedFiles.single().name)
    }

    fun testWithSomethingInItAFileThreadLeavesAClosedTabClosed() {
        showTheTabWithSomethingInIt()
        sample()
        tab.close()
        tab.openFileInFront(SAMPLE)

        agent.add("file" to SAMPLE, "body" to "About the whole file")
        tab.settle()

        assertFalse(tab.isOpen)
    }

    fun testWithSomethingInItAProjectThreadResolvedBeforeTheTabOpensLeavesTheTabClosed() {
        showTheTabWithSomethingInIt()
        sample()
        val thread = agent.ask("Which release name?")
        tab.settle()
        agent.post("comment_resolve", "thread_id" to thread)
        tab.close()
        tab.openFileInFront(SAMPLE)

        ProjectTab.showNewProjectThreadLater(project)
        tab.settle()

        assertFalse(tab.isOpen)
    }

    fun testWithSomethingInItATabYouRevealedAndClosedBeforeTheAgentsThreadOpensItStaysClosed() {
        showTheTabWithSomethingInIt()
        sample()
        val thread = user.thread(agent.ask("Which release name?"))
        tab.close()
        tab.openFileInFront(SAMPLE)

        ProjectTab.showNewProjectThreadLater(project)
        ProjectTab.reveal(project, thread)
        tab.close()
        tab.settle()

        assertFalse(tab.isOpen)
    }

    fun testWithSomethingInItARevealWhileTheTabOpensBehindTheFileYouAreReadingLeavesTheTabInFront() {
        showTheTabWithSomethingInIt()
        sample()
        val thread = user.thread(agent.ask("Which release name?"))
        tab.close()
        tab.openFileInFront(SAMPLE)
        ApplicationManager.getApplication().invokeLater { ProjectTab.reveal(project, thread) }

        openForTheFirstTime()

        assertTrue(tab.isInFront)
    }
}
