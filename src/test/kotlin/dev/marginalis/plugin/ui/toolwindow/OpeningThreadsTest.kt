package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.fileEditor.FileEditorManager
import dev.marginalis.plugin.testing.DisplayOnly
import dev.marginalis.plugin.testing.NeedsADisplay
import dev.marginalis.plugin.ui.FileLevelThreads
import dev.marginalis.plugin.ui.tab.MarginFile
import java.awt.event.KeyEvent
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
class OpeningThreadsTest : ToolWindowTestCase() {

    private fun selectedEditorFile() = FileEditorManager.getInstance(project).selectedEditor?.file

    private fun shownInPanel(): String = user.shownText(user.panel())

    @DisplayOnly
    fun testDoubleClickingALineThreadOpensItsFileAtTheLineWithTheThreadOpen() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(5, "body" to "In the sample")
        agent.addOnLine(17, "body" to "In the other file", file = "src/Other.kt")
        user.openFile(SAMPLE)

        window.doubleClick("In the other file")

        assertEquals("Other.kt", openFile())
        assertEquals(17, caretLine())
        assertTrue(shownInPanel(), shownInPanel().contains("In the other file"))
    }

    @DisplayOnly
    fun testASingleClickOnlySelects() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(17, "body" to "In the other file", file = "src/Other.kt")
        user.openFile(SAMPLE)

        window.click("In the other file")

        assertEquals("✉ L17  In the other file", window.selected())
        assertEquals("Sample.kt", openFile())
    }

    @DisplayOnly
    fun testDoubleClickingAFileLevelThreadOpensTheFileWithTheThreadOpen() {
        sample()
        agent.add("file" to SAMPLE, "body" to "About the whole file")

        window.doubleClick("About the whole file")

        assertEquals("Sample.kt", openFile())
        assertTrue(shownInPanel(), shownInPanel().contains("About the whole file"))
    }

    @DisplayOnly
    fun testDoubleClickingAProjectThreadBringsTheMarginTabToTheFront() {
        sample()
        agent.add("body" to "About the whole project")
        user.openFile(SAMPLE)

        window.doubleClick("About the whole project")

        assertEquals(MarginFile.of(project), selectedEditorFile())
    }

    @DisplayOnly
    fun testDoubleClickingAFileRowFoldsItAndOpensNothing() {
        sample()
        agent.addOnLine(5, "body" to "here")

        window.doubleClick("Sample.kt")

        assertNull(selectedEditorFile())
        assertFalse(window.isExpanded("Sample.kt"))
    }

    @DisplayOnly
    fun testJumpToSourceOpensTheSelectedThread() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(23, "body" to "second")
        window.click("second")

        window.jumpToSource()

        assertEquals("Sample.kt", openFile())
        assertEquals(23, caretLine())
        assertTrue(shownInPanel(), shownInPanel().contains("second"))
    }

    @DisplayOnly
    fun testJumpToSourceIsUnavailableOnAFolderOrFileRow() {
        sample()
        agent.addOnLine(5, "body" to "first")

        window.click("Sample.kt")
        assertFalse(window.canJumpToSource())
        window.click("src")
        assertFalse(window.canJumpToSource())
    }

    // FixMe: Known issue - #63 Enter on a selected thread in the tool window does nothing; only double-click, F4 or the context menu open it
    @DisplayOnly
    fun testEnterOnASelectedThreadOpensNothing() {
        sample()
        agent.addOnLine(5, "body" to "first")
        agent.addOnLine(23, "body" to "second")
        window.click("first")
        window.pressKey(KeyEvent.VK_DOWN)
        assertEquals("✉ L23  second", window.selected())

        window.pressKey(KeyEvent.VK_ENTER)

        assertNull(selectedEditorFile())
        assertEquals("✉ L23  second", window.selected())
    }

    fun testShowingAFileInTheToolWindowSelectsItsFirstRow() {
        val steps = interleaved()
        sample()
        agent.addOnLine(5, "body" to "loose")
        window.outline()

        FileLevelThreads.showInToolWindow(project, "acme/backend/shop-orders/src/main/java/com/acme/orders/OrderService.java")
        assertEquals("OrderService.java  steps 2–3 ✉2", window.selected())
        assertEquals("Guided SHOP-77  17", window.selectedPath().first())

        FileLevelThreads.showInToolWindow(project, SAMPLE)
        assertEquals(listOf("Open  18", "src  1", "Sample.kt ✉1"), window.selectedPath())

        FileLevelThreads.showInToolWindow(project, "src/NoThreads.kt")
        assertEquals("Sample.kt ✉1", window.selected())
        assertEquals(18, steps.size)
    }
}
