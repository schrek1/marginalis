package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.IconUtil
import dev.marginalis.plugin.ui.MarginalisIcons

class TheTabItselfTest : MarginTabTestCase() {

    fun testAnEmptyMarginIsTitledMarginAndExplainsWhatProjectThreadsAreFor() {
        sample()
        agent.addOnLine(5, "body" to "About a line, not the project")

        assertEquals("Margin", tab.title())
        assertEquals("0 open · 0 your move · 0 agent's move", tab.counts())
        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD), shown())
    }

    fun testTheTitleShowsWhatWaitsOnYouAndWhatWaitsOnTheAgent() {
        val first = agent.ask("Which release name?")
        agent.ask("Ship on Friday?")
        assertEquals("✉ 2", tab.title())

        tab.panel("Which release name?").let {
            user.type(it, "Oak.")
            user.submit(it)
        }

        assertEquals("✉ 1 · ✈ 1", tab.title())
        assertEquals("2 open · 1 your move · 1 agent's move · 2 new", tab.counts())
        agent.post("comment_resolve", "thread_id" to first)
        assertEquals("✉ 1", tab.title())
    }

    fun testTheOpenTabsLabelFollowsTheSignalsAsThreadsChange() {
        assertEquals("Margin", tab.shownTitle())
        val first = agent.ask("Which release name?")
        tab.waitForShownTitle("✉ 1")

        agent.post("comment_resolve", "thread_id" to first)

        tab.waitForShownTitle("Margin")
    }

    fun testOnlyOpenProjectThreadsCountTowardsTheTitle() {
        sample()
        agent.addOnLine(5, "body" to "A line question")
        agent.add("file" to SAMPLE, "body" to "A file question")
        val resolved = agent.ask("Settled already")
        agent.post("comment_resolve", "thread_id" to resolved)

        assertEquals("Margin", tab.title())
        assertEquals("0 open · 0 your move · 0 agent's move", tab.counts())
    }

    fun testTheTabCarriesTheMarginalisIcon() {
        assertSame(MarginalisIcons.Brand, tab.tabIcon())
    }

    fun testOpeningTheMarginShowsOnlyTheMarginEditor() {
        tab.close()

        val opened = tab.editors.openFile(MarginFile, true, true)

        assertEquals(listOf("Margin"), opened.map { it.name })
        assertEquals(listOf(MarginEditorProvider::class.java), FileEditorProviderManager.getInstance().getProviderList(project, MarginFile).map { it.javaClass })
        assertFalse(opened.single().isModified)
        assertTrue(opened.single().isValid)
        assertSame(MarginFile, opened.single().file)
    }

    fun testTheTabIsFoundAgainByItsAddressAfterARestart() {
        assertSame(MarginFile, VirtualFileManager.getInstance().findFileByUrl("marginalis://margin"))
        assertNull(VirtualFileManager.getInstance().findFileByUrl("marginalis://elsewhere"))
        assertSame(MarginFile, VirtualFileManager.getInstance().getFileSystem("marginalis").refreshAndFindFileByPath("margin"))
    }

    fun testHalfWrittenRepliesAreStillThereWhenTheTabOpensAgain() {
        agent.ask("Which release name?")
        user.type(tab.panel("Which release name?"), "Maybe Oak")
        tab.newProjectThread()
        user.type(tab.draft()!!, "Plan the 0.3 release")

        tab.close()
        tab.open()

        assertEquals("Maybe Oak", user.composer(tab.panel("Which release name?")).text)
        assertEquals("Plan the 0.3 release", user.composer(tab.draft()!!).text)
    }

    fun testThreadsThatArriveWhileTheTabIsClosedAreThereWhenItOpens() {
        tab.close()

        agent.ask("Which release name?")
        tab.open()

        assertEquals(listOf("✉ Which release name? ● [expanded]"), shown())
        assertEquals("✉ 1", tab.title())
    }

    fun testOtherFilesKeepTheirOwnTitleAndIcon() {
        val file = tab.openFileInFront(sample())

        assertEquals("Sample.kt", EditorTabPresentationUtil.getEditorTabTitle(project, file))
        assertNotSame(MarginalisIcons.Brand, IconUtil.getIcon(file, 0, project))
    }
}
