package dev.marginalis.plugin.ui

import com.intellij.openapi.fileTypes.PlainTextFileType
import dev.marginalis.plugin.testing.Browser
import dev.marginalis.plugin.testing.RenderedBody
import dev.marginalis.plugin.transport.HttpContractTestCase
import javax.swing.JEditorPane
import javax.swing.ScrollPaneConstants
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTML

class MessageBodiesTest : HttpContractTestCase() {

    override fun setUp() {
        super.setUp()
        sample()
    }

    private fun rendered(body: String): RenderedBody = RenderedBody.of(panelSaying(body))

    private fun panelSaying(body: String): ThreadPanel = user.open(agent.addOnLine(5, "body" to body))

    fun testEmphasisStrongAndInlineCodeAreMarkedUpWithoutTheirMarkers() {
        val body = rendered("Keep *this* and **that**, but drop `legacyFlag` now.")

        assertEquals(listOf("Keep this and that, but drop legacyFlag now."), body.lines())
        assertEquals(setOf("em"), body.markedAs("this"))
        assertEquals(setOf("strong"), body.markedAs("that"))
        assertEquals(setOf("code"), body.markedAs("legacyFlag"))
        assertEquals(emptySet<String>(), body.markedAs("Keep"))
    }

    fun testHeadingsAreRescaledToMarginProportions() {
        val body = rendered("# Big\n\n## Middle\n\n### Small\n\nPlain words.")

        val plain = body.fontOf("Plain").size
        val big = body.fontOf("Big").size
        val middle = body.fontOf("Middle").size
        val small = body.fontOf("Small").size
        assertTrue("h1 $big vs body $plain", big > middle && middle >= small)
        assertTrue("h1 $big towers over body $plain", big <= plain * 1.5)
        assertEquals(listOf("Big", "Middle", "Small", "Plain words."), body.lines())
    }

    fun testBulletAndNumberedListsKeepTheirItemsAndStartingNumber() {
        val body = rendered("Steps:\n\n- one\n- two\n\n3. three\n4. four")

        assertEquals(listOf("Steps:", "one", "two", "three", "four"), body.lines())
        assertTrue(body.enclosingBlocks("one").containsAll(listOf("li", "ul")))
        assertTrue(body.enclosingBlocks("three").containsAll(listOf("li", "ol")))
        assertEquals("3", body.attributeOfBlock("three", "ol", HTML.Attribute.START))
    }

    fun testRenderedProseKeepsItsCaretAtTheStartSoARebuildCannotScrollTheCode() {
        val body = rendered("Rendered once, never typed into.")

        assertEquals(listOf(0), body.prose.map { it.caretPosition })
        assertEquals(listOf(DefaultCaret.NEVER_UPDATE), body.prose.map { (it.caret as DefaultCaret).updatePolicy })
    }

    fun testAQuoteIsSetApartAsAQuote() {
        val body = rendered("> As the user said\n\nAgreed.")

        assertTrue(body.enclosingBlocks("As the user said").contains("blockquote"))
        assertFalse(body.enclosingBlocks("Agreed.").contains("blockquote"))
    }

    fun testAWebLinkOpensInTheBrowser() {
        val browser = Browser(testRootDisposable)
        val panel = panelSaying("Read [the docs](https://example.com/docs) first.")
        assertEquals("https://example.com/docs", RenderedBody.of(panel).linkOn("the docs"))

        val warnings = user.warningsWhile { user.followLink(panel, "https://example.com/docs") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals(listOf("https://example.com/docs"), browser.opened)
    }

    fun testPointingAtALinkOpensNothing() {
        val browser = Browser(testRootDisposable)
        val panel = panelSaying("Read [the docs](https://example.com/docs) first.")

        val warnings = user.warningsWhile { RenderedBody.of(panel).pointAt("https://example.com/docs") }

        assertEquals(emptyList<String>(), warnings)
        assertEquals(emptyList<String>(), browser.opened)
    }

    fun testAnAngleBracketedAndABareWebAddressAreBothLinks() {
        val body = rendered("See <https://example.com/a> or https://example.com/b.")

        assertEquals("https://example.com/a", body.linkOn("https://example.com/a"))
        assertEquals("https://example.com/b", body.linkOn("https://example.com/b"))
    }

    fun testACodeLinkIsALinkToo() {
        val body = rendered("Look at [the helper]($SAMPLE#L3).")

        assertEquals("$SAMPLE#L3", body.linkOn("the helper"))
    }

    fun testAFencedBlockShowsAsReadOnlyCodeInItsLanguageBetweenTheProse() {
        val body = rendered("Before.\n```java\nclass A { int x = 1; }\n```\nAfter.")

        assertEquals(listOf("prose", "code", "prose"), body.kinds())
        val code = body.codeBlocks.single()
        assertEquals("class A { int x = 1; }", code.text)
        assertEquals("JAVA", code.fileType.name)
        assertTrue(code.isViewer)
    }

    fun testAFenceWithoutALanguageOrWithAnUnknownOneIsPlainCode() {
        val body = rendered("```\nplain\n```\n```nosuchlanguage\nstill plain\n```")

        assertEquals(listOf("code", "code"), body.kinds())
        assertEquals(listOf(PlainTextFileType.INSTANCE, PlainTextFileType.INSTANCE), body.codeBlocks.map { it.fileType })
        assertEquals(listOf("plain", "still plain"), body.codeBlocks.map { it.text })
    }

    fun testABodyThatIsOnlyCodeShowsNoEmptyProse() {
        val body = rendered("```kotlin\nval x = 1\n```")

        assertEquals(listOf("code"), body.kinds())
    }

    fun testAnUnclosedFenceRunsToTheEndAsPreformattedProseWithoutHighlighting() {
        val body = rendered("Start:\n```java\nclass A {}\n  indented()")

        assertEquals(listOf("prose"), body.kinds())
        assertEquals(listOf("Start:", "class A {}", "indented()"), body.lines())
        assertTrue(body.enclosingBlocks("class A").contains("pre"))
        assertTrue(body.enclosingBlocks("indented()").contains("pre"))
        assertFalse(body.enclosingBlocks("Start:").contains("pre"))
    }

    fun testAnImageShowsAsAPlaceholder() {
        val body = rendered("Diagram: ![the flow](https://example.com/flow.png) above.")

        assertEquals(listOf("Diagram: [image] above."), body.lines())
    }

    fun testRawHtmlIsReducedToItsTextAndTheAllowedConstructs() {
        val body = rendered("A <b>bold</b> claim <em>kept</em>, <span style=\"color:red\">plain</span>.\n\n<script>alert(1)</script>")

        assertEquals(listOf("A bold claim kept, plain.", "alert(1)"), body.lines())
        assertEquals(emptySet<String>(), body.markedAs("bold"))
        assertEquals(setOf("em"), body.markedAs("kept"))
        assertEquals(emptySet<String>(), body.markedAs("plain"))
        assertFalse(body.text().contains("<"))
    }

    fun testALessThanSignInProseIsShownAsTyped() {
        val body = rendered("Only when a < b and b > c.")

        assertEquals(listOf("Only when a < b and b > c."), body.lines())
    }

    fun testABlankLineStartsANewParagraph() {
        val body = rendered("First paragraph.\n\nSecond paragraph.")

        assertEquals(listOf("First paragraph.", "Second paragraph."), body.lines())
    }

    fun testTwoTrailingSpacesBreakTheLine() {
        val body = rendered("Line one  \nLine two")

        assertEquals(listOf("Line one", "Line two"), body.lines())
    }

    fun testASingleLineBreakStartsANewLine() {
        val body = rendered("Line one\nLine two")

        assertEquals(listOf("Line one", "Line two"), body.lines())
    }

    fun testASingleEnterIsOneLineBreak() {
        val body = rendered("Alphaline first\nBravoline second")

        assertEquals(1, LINE_BREAK.findAll(body.html()).count())
    }

    fun testTwoTrailingSpacesStillGiveExactlyOneLineBreak() {
        val body = rendered("Alphaline first  \nBravoline second")

        assertEquals(1, LINE_BREAK.findAll(body.html()).count())
    }

    fun testASecondBlankLineAddsAnEmptyLine() {
        val body = rendered("Alphablank one\n\nBravoblank two\n\n\nCharlieblank three")

        assertEquals(listOf("Alphablank one", "Bravoblank two", "", "Charlieblank three"), body.blocks())
    }

    fun testAWideTableBetweenProseIsATableOfItsOwn() {
        val body = rendered(WIDE_TABLE_BETWEEN_PROSE)

        assertEquals(listOf("prose", "table", "prose"), body.kinds())
    }

    fun testStrikethroughAfterAWideTableIsStillStruckThrough() {
        val body = rendered(WIDE_TABLE_BETWEEN_PROSE)

        assertTrue(body.markedAs("struck out").contains("s"))
    }

    fun testAWideTableIsLaidOutWiderThanThePanelAndMayScrollSideways() {
        val table = rendered(WIDE_TABLE_BETWEEN_PROSE).tables.single()
        val pane = table.viewport.view as JEditorPane

        assertFalse(table.horizontalScrollBarPolicy == ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        assertFalse(pane.scrollableTracksViewportWidth)
        assertTrue("${pane.preferredSize.width} vs ${table.preferredSize.width}", pane.preferredSize.width > table.preferredSize.width)
    }

    fun testATableIsDrawnAsATableOfCells() {
        val body = rendered("| Name | Age |\n|------|-----|\n| Ann  | 3   |")

        assertEquals(listOf("table"), body.kinds())
        assertEquals(listOf("Name", "Age", "Ann", "3"), body.lines())
        assertTrue(body.enclosingBlocks("Name").containsAll(listOf("th", "tr", "table")))
        assertTrue(body.enclosingBlocks("Ann").containsAll(listOf("td", "tr", "table")))
    }

    fun testATaskListShowsWhichItemsAreDone() {
        val body = rendered("- [ ] todo\n- [x] done")

        assertEquals(listOf("☐ todo", "☑ done"), body.lines())
    }

    fun testADeeplyNestedRawTableRendersAsOneTableKeepingItsText() {
        val body = rendered("<table><tr><td>".repeat(2_000) + "deep inside" + "</td></tr></table>".repeat(2_000))

        assertEquals(listOf("deep inside"), body.lines())
        assertEquals(1, body.enclosingBlocks("deep inside").count { it == "table" })
    }

    fun testStrikethroughIsStruckThroughWithoutItsTildes() {
        val body = rendered("Use ~~old~~ new.")

        assertEquals(listOf("Use old new."), body.lines())
        assertTrue(body.markedAs("old").contains("s"))
        assertFalse(body.markedAs("new").contains("s"))
    }

    fun testAOneLinePreviewKeepsOnlyTheStartOfAFencedBlock() {
        agent.addOnLine(5, "body" to "Like this:\n```kotlin\nfun total() = items.sumOf { it.price + it.quantity + 1 }\n```\nThen done.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

        assertTrue(tooltip, tooltip.contains("Like this:"))
        assertTrue(tooltip, tooltip.contains("fun total() = items.sumOf { it.price + i"))
        assertFalse(tooltip, tooltip.contains("quantity"))
        assertFalse(tooltip, tooltip.contains("kotlin"))
        assertTrue(tooltip, tooltip.contains("Then done."))
    }

    fun testAOneLinePreviewReadsATableAsItsCellsWithoutPipesOrTheDelimiterRow() {
        agent.addOnLine(5, "body" to "Totals:\n\n| Name | Age | Note |\n|:-----|----:|------|\n| Ann  | 3   |      |\n| **Bo** | ~~4~~ 5 | late |\n\nThen done.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

        assertTrue(tooltip, tooltip.contains("Totals: Name · Age · Note · Ann · 3 · Bo · 4 5 · late Then done."))
        assertFalse(tooltip, tooltip.contains("|"))
        assertFalse(tooltip, tooltip.contains("--"))
    }

    fun testAOneLinePreviewReadsATableInsideAQuoteAsItsCellsToo() {
        agent.addOnLine(5, "body" to "> | Name | Age |\n> |------|-----|\n> | Ann  | 3   |")

        assertTrue(user.gutterIcon(SAMPLE, 5)!!.tooltipText.contains("Name · Age · Ann · 3"))
    }

    fun testAOneLinePreviewDropsTheTildesOfStrikethrough() {
        agent.addOnLine(5, "body" to "Use ~~old~~ new.")

        assertTrue(user.gutterIcon(SAMPLE, 5)!!.tooltipText.contains("Use old new."))
    }

    fun testAOneLinePreviewKeepsAPipeOrTildeInOrdinaryProse() {
        agent.addOnLine(5, "body" to "Pick a | b, then wait ~5 minutes.")

        assertTrue(user.gutterIcon(SAMPLE, 5)!!.tooltipText.contains("Pick a | b, then wait ~5 minutes."))
    }

    fun testATableRightAfterALineOfTextStaysRawPipesUnlikeOnGitHub() {
        val body = rendered("Totals:\n| Name | Age |\n|------|-----|\n| Ann  | 3   |")

        assertEquals(listOf("prose"), body.kinds())
        assertTrue(body.text(), body.text().contains("| Name | Age |"))
    }

    fun testTheContextMenuSelectsAllAndOffersCopyOnceSomethingIsSelected() {
        val body = rendered("Copy this sentence.")
        assertEquals(mapOf("Copy" to false, "Select All" to true), body.contextMenu("Copy this"))

        body.clickMenu("Copy this", "Select All")

        assertEquals("Copy this sentence.", body.selection("Copy this")?.trim())
        assertEquals(mapOf("Copy" to true, "Select All" to true), body.contextMenu("Copy this"))
    }

    private companion object {
        val LINE_BREAK = Regex("<br")
        val WIDE_TABLE_BETWEEN_PROSE = "Before the table, prose that should keep wrapping to the panel.\n\n" +
            (1..24).joinToString(" | ", "| ", " |\n") { "column $it" } +
            "|" + "---|".repeat(24) + "\n" +
            (1..24).joinToString(" | ", "| ", " |\n") { "value $it" } +
            "\nAnd ~~struck out~~ text after it."
    }
}
