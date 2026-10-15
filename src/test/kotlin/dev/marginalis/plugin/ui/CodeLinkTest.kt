package dev.marginalis.plugin.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.marginalis.core.Reference
import dev.marginalis.plugin.transport.HttpContractTestCase

class CodeLinkTest : HttpContractTestCase() {

    private val other = "src/Other.kt"

    override fun setUp() {
        super.setUp()
        sample()
        sample(other, lines = 10)
    }

    private fun panelSaying(body: String): ThreadPanel {
        val id = agent.addOnLine(5, "body" to body)
        return user.open(id)
    }

    private fun openFileName(): String? =
        FileDocumentManager.getInstance().getFile(user.editor().document)?.name

    private val caretLine: Int get() = user.editor().caretModel.logicalPosition.line + 1

    fun testALineLinkOpensTheFileWithTheCaretOnThatLine() {
        val panel = panelSaying("See [the helper]($other#L3).")

        val warnings = user.warningsWhile { user.followLink(panel, "$other#L3") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals("Other.kt", openFileName())
        assertEquals(3, caretLine)
        assertFalse(user.editor().selectionModel.hasSelection())
    }

    fun testARangeLinkSelectsEveryLineOfTheRange() {
        val panel = panelSaying("See [these]($other#L3-L5).")

        user.followLink(panel, "$other#L3-L5")

        val editor = user.editor()
        assertEquals("Other.kt", openFileName())
        assertEquals(3, caretLine)
        assertEquals("val line3 = 3\nval line4 = 4\nval line5 = 5", editor.selectionModel.selectedText)
    }

    fun testALinkWithoutALineOpensTheFile() {
        val panel = panelSaying("See [the file]($other).")

        user.followLink(panel, other)

        assertEquals("Other.kt", openFileName())
    }

    fun testALinkToTheLastLineOfAFileEndingInANewlineOpens() {
        val panel = panelSaying("See [the end]($other#L10).")

        val warnings = user.warningsWhile { user.followLink(panel, "$other#L10") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals(10, caretLine)
    }

    fun testALinkPastTheEndOfTheFileWarnsAndStaysPut() {
        val panel = panelSaying("See [nowhere]($other#L11).")

        val warnings = user.warningsWhile { user.followLink(panel, "$other#L11") }

        assertEquals(listOf("$other#L11 reaches past the end of $other, which has 10 lines."), warnings)
        assertEquals("Sample.kt", openFileName())
    }

    fun testALineLinkIntoABinaryFileWarnsThatItHasNoLines() {
        myFixture.addFileToProject("img/logo.png", "not really an image")
        val panel = panelSaying("See [logo](img/logo.png#L1).")

        val warnings = user.warningsWhile { user.followLink(panel, "img/logo.png#L1") }

        assertEquals(listOf("img/logo.png has no text the editor can open at a line."), warnings)
    }

    fun testALinkToAFolderWarnsThatALinkOpensAFile() {
        val panel = panelSaying("See [the folder](src).")

        val warnings = user.warningsWhile { user.followLink(panel, "src") }

        assertEquals(listOf("src names a directory — a code link opens a file."), warnings)
    }

    fun testALinkThatClimbsOutOfTheProjectWarnsWithTheRule() {
        val panel = panelSaying("See [up](../secret.kt).")

        val warnings = user.warningsWhile { user.followLink(panel, "../secret.kt") }

        assertEquals(1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("climbs out of the project with &#39;..&#39;"))
        assertEquals("Sample.kt", openFileName())
    }

    fun testALinkWithAMalformedLineAnchorWarns() {
        val panel = panelSaying("See [there]($other#line3).")

        val warnings = user.warningsWhile { user.followLink(panel, "$other#line3") }

        assertTrue(warnings.single(), warnings.single().contains("is not a line anchor"))
    }

    fun testALinkOtherThanHttpWarnsThatOnlyWebLinksOpen() {
        val panel = panelSaying("Mail [me](ftp://example.com/x).")

        val warnings = user.warningsWhile { user.followLink(panel, "ftp://example.com/x") }

        assertEquals(listOf("Only http and https links open from the margin."), warnings)
    }

    fun testAnInPageAnchorDoesNothing() {
        val panel = panelSaying("See [above](#top).")

        val warnings = user.warningsWhile { user.followLink(panel, "#top") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals("Sample.kt", openFileName())
    }

    fun testAReferenceInABodyOpensTheThreadItNames() {
        val target = agent.addOnLine(4, "body" to "The original point.", file = other)
        val reference = Reference.of(target).toString()
        val panel = panelSaying("As in $reference, again.")

        user.followLink(panel, reference)

        assertEquals("Other.kt", openFileName())
        assertEquals(4, caretLine)
        assertTrue(user.panels().any { user.shownText(it).contains("The original point.") })
    }

    fun testAReferenceToAMessageOpensItsThread() {
        agent.addOnLine(4, "body" to "The original point.", file = other)
        val messageId = agent.threads("file" to other).single().messages().single().string("message_id")!!
        val reference = Reference.of(messageId).toString()
        val panel = panelSaying("As $reference said.")

        user.followLink(panel, reference)

        assertEquals("Other.kt", openFileName())
        assertTrue(user.panels().any { user.shownText(it).contains("The original point.") })
    }

    fun testAReferenceToNothingWarnsAndStaysPut() {
        val panel = panelSaying("As in mg:0123abcd, again.")

        val warnings = user.warningsWhile { user.followLink(panel, "mg:0123abcd") }

        assertEquals(listOf("mg:0123abcd names no thread or message in this project"), warnings)
        assertEquals("Sample.kt", openFileName())
    }

    fun testAMalformedReferenceLinkWarnsWithTheSyntax() {
        val panel = panelSaying("See [it](mg:xyz).")

        val warnings = user.warningsWhile { user.followLink(panel, "mg:xyz") }

        assertTrue(warnings.single(), warnings.single().contains("is not a reference"))
    }

    fun testAReferenceInsideCodeIsNotALink() {
        val target = agent.addOnLine(4, file = other)
        val reference = Reference.of(target).toString()
        val panel = panelSaying("Run `$reference` literally.")

        assertTrue(user.shownText(panel).contains(reference))
        assertFalse(user.hasLink(panel, reference))
    }
}
