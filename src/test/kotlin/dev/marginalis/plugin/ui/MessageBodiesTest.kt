package dev.marginalis.plugin.ui

import com.intellij.openapi.fileTypes.PlainTextFileType
import dev.marginalis.plugin.testing.Browser
import dev.marginalis.plugin.testing.RenderedBody
import dev.marginalis.plugin.transport.HttpContractTestCase
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

    fun testASingleLineBreakStartsANewLine() {
        val body = rendered("Line one\nLine two")

        assertEquals(listOf("Line one", "Line two"), body.lines())
    }

    fun testASecondBlankLineBetweenParagraphsStaysAnEmptyLine() {
        val body = rendered("One.\n\n\nTwo.")

        assertEquals(listOf("One.", "", "Two."), body.text().lines().map { it.trim() })
    }

    fun testASingleLineBreakInsideAListItemStartsANewLine() {
        val body = rendered("- one\n  more\n- two")

        assertEquals(listOf("one", "more", "two"), body.screen())
    }

    fun testASingleLineBreakInsideAQuoteStartsANewLine() {
        val body = rendered("> one\n> more")

        assertEquals(listOf("one", "more"), body.screen())
    }

    fun testTwoTrailingSpacesBreakTheLineOnlyOnce() {
        val body = rendered("Line one  \nLine two")

        assertEquals(listOf("Line one", "Line two"), body.screen())
    }

    fun testABackslashBreaksTheLineOnlyOnce() {
        val body = rendered("Line one\\\nLine two")

        assertEquals(listOf("Line one", "Line two"), body.screen())
    }

    fun testASecondBlankLineBeforeAndAfterACodeBlockStaysAnEmptyLine() {
        val body = rendered("Before.\n\n\n```\ncode\n```\n\n\nAfter.")

        assertEquals(listOf("Before.", "", "[code]", "", "After."), body.screen())
    }

    fun testASecondBlankLineBetweenTwoCodeBlocksStaysAnEmptyLine() {
        val body = rendered("```\none\n```\n\n\n```\ntwo\n```")

        assertEquals(listOf("[code]", "", "[code]"), body.screen())
    }

    fun testASecondBlankLineBeforeAndAfterATableStaysAnEmptyLine() {
        val body = rendered("Before.\n\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n\nAfter.")

        assertEquals(listOf("Before.", "", "[table]", "", "After."), body.screen())
    }

    fun testASingleBlankLineAroundACodeBlockOrATableAddsNoEmptyLine() {
        val body = rendered("Before.\n\n```\ncode\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nAfter.")

        assertEquals(listOf("Before.", "[code]", "[table]", "After."), body.screen())
    }

    fun testTrailingBlankLinesAfterACodeBlockAddNoEmptyLine() {
        val body = rendered("```\ncode\n```\n\n\n")

        assertEquals(listOf("[code]"), body.screen())
    }

    fun testWindowsLineEndingsBreakLinesAndParagraphsAsPlainOnesDo() {
        val body = rendered("Intro.\r\n\r\n- a\r\n- b\r\n\r\nOne\r\nTwo.\r\n\r\n\r\nEnd.")

        assertEquals(listOf("Intro.", "a", "b", "One", "Two.", "", "End."), body.screen())
    }

    fun testAFencedBlockWithWindowsLineEndingsIsStillCode() {
        val body = rendered("Before.\r\n\r\n\r\n```\r\ncode\r\n```\r\n\r\n\r\nAfter.")

        assertEquals(listOf("Before.", "", "[code]", "", "After."), body.screen())
    }

    fun testParagraphsAreSpacedApartAndTheFirstSitsFlush() {
        val body = rendered("One.\n\nTwo.")

        assertEquals(0, body.marginAboveInPx("One.", "p"))
        assertEquals(10, body.marginAboveInPx("Two.", "p"))
    }

    fun testAListSitsCloseUnderItsIntroAndItsItemsSitTogether() {
        val body = rendered("Intro.\n\n- one\n- two")

        assertEquals(3, body.marginAboveInPx("one", "ul"))
        assertEquals(0, body.marginAboveInPx("one", "li"))
        assertEquals(0, body.marginAboveInPx("two", "li"))
    }

    fun testAListThatOpensTheMessageSitsFlush() {
        val body = rendered("1. one\n2. two")

        assertEquals(0, body.marginAboveInPx("one", "ol"))
    }

    fun testANumberedListThatOpensTheMessageFromAboveOneSitsFlushAndKeepsItsNumber() {
        val body = rendered("3. three\n4. four")

        assertEquals(0, body.marginAboveInPx("three", "ol"))
        assertEquals("3", body.attributeOfBlock("three", "ol", HTML.Attribute.START))
    }

    fun testTheItemsOfAListWithBlankLinesBetweenThemSitTogether() {
        val body = rendered("Intro.\n\n- one\n\n- two\n\n- three")

        assertEquals(3, body.marginAboveInPx("one", "ul"))
        assertEquals(listOf(0, 0, 0), listOf("one", "two", "three").map { body.marginAboveInPx(it, "p") })
    }

    fun testASecondParagraphInsideAListItemIsStillSpacedApart() {
        val body = rendered("- one\n\n  more\n- two")

        assertEquals(10, body.marginAboveInPx("more", "p"))
    }

    fun testAListWithBlankLinesBetweenItsItemsThatOpensTheMessageSitsFlush() {
        val body = rendered("- one\n\n- two")

        assertEquals(0, body.marginAboveInPx("one", "ul"))
        assertEquals(0, body.marginAboveInPx("one", "p"))
    }

    fun testAQuoteThatOpensTheMessageSitsFlush() {
        val body = rendered("> quoted\n\nThen.")

        assertEquals(0, body.marginAboveInPx("quoted", "blockquote"))
        assertEquals(0, body.marginAboveInPx("quoted", "p"))
        assertEquals(10, body.marginAboveInPx("Then.", "p"))
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

    fun testAOneLinePreviewKeepsOnlyTheStartOfAFencedBlockWithWindowsLineEndings() {
        agent.addOnLine(5, "body" to "Like this:\r\n```kotlin\r\nfun total() = items.sumOf { it.price + it.quantity + 1 }\r\n```\r\nThen done.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

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

    fun testAOneLinePreviewKeepsTheUnderscoresOfACodeSpan() {
        agent.addOnLine(5, "body" to "`legacy_helper` has no callers left.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

        assertTrue(tooltip, tooltip.contains("legacy_helper has no callers left."))
    }

    fun testAOneLinePreviewKeepsMarkdownCharactersInsideACodeSpanOrInOrdinaryProse() {
        agent.addOnLine(5, "body" to "Free `*ptr` once, as #12 says: a > b.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

        assertTrue(tooltip, tooltip.contains("Free *ptr once, as #12 says: a &gt; b."))
    }

    fun testAOneLinePreviewDropsTheMarkersOfEmphasisHeadingsAndQuotes() {
        agent.addOnLine(5, "body" to "## Plan\n\n> Keep _this_ and __that__, *here* and **there**.")

        val tooltip = user.gutterIcon(SAMPLE, 5)!!.tooltipText

        assertTrue(tooltip, tooltip.contains("Plan Keep this and that, here and there."))
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
}
