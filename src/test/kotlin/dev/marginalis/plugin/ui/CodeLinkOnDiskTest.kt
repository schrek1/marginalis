package dev.marginalis.plugin.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.marginalis.plugin.testing.SavedProjectTestCase
import java.nio.file.Files

class CodeLinkOnDiskTest : SavedProjectTestCase() {

    override fun setUp() {
        super.setUp()
        openShop()
    }

    private fun openFileName(): String? = FileDocumentManager.getInstance().getFile(user.editor().document)?.name

    fun testALinkToAFileThatIsNotThereWarnsThatPathsAreProjectRelative() {
        val panel = user.open(agent.addOnLine(5, "body" to "See [gone](src/Gone.kt#L2)."))

        val warnings = user.warningsWhile { user.followLink(panel, "src/Gone.kt#L2") }

        assertEquals(listOf("src/Gone.kt#L2 names no file in this project — code link paths are relative to the project root."), warnings)
        assertEquals("Sample.kt", openFileName())
    }

    fun testALinkToAFileWrittenOutsideTheIdeFindsItOnDisk() {
        val panel = user.open(agent.addOnLine(5, "body" to "See [new](src/Fresh.kt#L2)."))
        Files.writeString(shop.root.resolve("src/Fresh.kt"), "val a = 1\nval b = 2\nval c = 3\n")

        val warnings = user.warningsWhile { user.followLink(panel, "src/Fresh.kt#L2") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals("Fresh.kt", openFileName())
        assertEquals(1, user.editor().caretModel.logicalPosition.line)
    }

    fun testAnEncodedPathWithSpacesOpensTheFile() {
        Files.createDirectories(shop.root.resolve("src/My Docs"))
        Files.writeString(shop.root.resolve("src/My Docs/Notes.kt"), "val a = 1\nval b = 2\n")
        val panel = user.open(agent.addOnLine(5, "body" to "See [notes](src/My%20Docs/Notes.kt#L2)."))

        val warnings = user.warningsWhile { user.followLink(panel, "src/My%20Docs/Notes.kt#L2") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals("Notes.kt", openFileName())
    }
}
