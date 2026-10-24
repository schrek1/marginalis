package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OpeningLineTest {

    @Test
    fun `a one-line body is its own opening line`() {
        assertEquals("Extract this loop.", OpeningLine.of("Extract this loop."))
    }

    @Test
    fun `the opening line is the first paragraph`() {
        assertEquals("The plan holds.", OpeningLine.of("The plan holds.\n\nHere is why, at length."))
    }

    @Test
    fun `the first paragraph's lines join into one`() {
        assertEquals("The plan holds, as far as I can tell.", OpeningLine.of("The plan holds,\nas far as I can tell.\n\nMore."))
    }

    @Test
    fun `an underlined heading drops its underline`() {
        assertEquals("Summary", OpeningLine.of("Summary\n=======\n\nBody"))
        assertEquals("Plan", OpeningLine.of("Plan\n---\n\nBody"))
    }

    @Test
    fun `a leading rule is skipped`() {
        assertEquals("After the rule.", OpeningLine.of("---\n\nAfter the rule."))
        assertEquals("Starred rule.", OpeningLine.of("* * *\nStarred rule."))
    }

    @Test
    fun `whitespace collapses`() {
        assertEquals("Spaced out words", OpeningLine.of("  Spaced \t out\r\n   words  "))
    }

    @Test
    fun `leading blank lines are skipped`() {
        assertEquals("Late start.", OpeningLine.of("\n\n   \nLate start.\n\nNext."))
    }

    @Test
    fun `emphasis shows its words`() {
        assertEquals("Very important and quite so, truly struck", OpeningLine.of("**Very** important and _quite_ so, *truly* ~~struck~~"))
    }

    @Test
    fun `strong emphasis written with underscores shows its words`() {
        assertEquals("Bold words", OpeningLine.of("__Bold__ words"))
    }

    @Test
    fun `underscores inside identifiers stay`() {
        assertEquals("Rename snake_case_name now", OpeningLine.of("Rename snake_case_name now"))
    }

    @Test
    fun `code spans show their code`() {
        assertEquals("Use a sumOf call.", OpeningLine.of("Use a `sumOf` call."))
    }

    @Test
    fun `code spans keep their markdown-looking characters`() {
        assertEquals("Call a *b* and __init__", OpeningLine.of("Call `a *b*` and `__init__`"))
    }

    @Test
    fun `links show their text`() {
        assertEquals("See the docs today.", OpeningLine.of("See [the docs](https://example.com/docs) today."))
    }

    @Test
    fun `reference links show their text`() {
        assertEquals("See the docs today.", OpeningLine.of("See [the docs][1] today.\n\n[1]: https://example.com"))
    }

    @Test
    fun `images show their alt text`() {
        assertEquals("Look: the diagram", OpeningLine.of("Look: ![the diagram](diagram.png)"))
    }

    @Test
    fun `autolinks show their address`() {
        assertEquals("Read https://example.com first.", OpeningLine.of("Read <https://example.com> first."))
    }

    @Test
    fun `a heading is a paragraph of its own`() {
        assertEquals("The plan", OpeningLine.of("## The plan\nStep one, then step two."))
    }

    @Test
    fun `list bullets are dropped`() {
        assertEquals("First thing second thing third thing", OpeningLine.of("- First thing\n* second thing\n+ third thing"))
    }

    @Test
    fun `numbered list markers are dropped`() {
        assertEquals("Build it ship it", OpeningLine.of("1. Build it\n2) ship it"))
    }

    @Test
    fun `task checkboxes are dropped`() {
        assertEquals("Done thing open thing", OpeningLine.of("- [x] Done thing\n- [ ] open thing"))
    }

    @Test
    fun `quote markers are dropped`() {
        assertEquals("As you said, it holds.", OpeningLine.of("> As you said,\n> it holds."))
    }

    @Test
    fun `a body that opens with a code block opens with its code`() {
        assertEquals("val total = items.sumOf { it.price }", OpeningLine.of("```kotlin\nval total = items.sumOf { it.price }\n```\n\nThat's the idea."))
    }

    @Test
    fun `an empty code block is skipped`() {
        assertEquals("After the fence.", OpeningLine.of("```\n```\n\nAfter the fence."))
    }

    @Test
    fun `escaped characters show as written`() {
        assertEquals("Not *emphasis* here", OpeningLine.of("Not \\*emphasis\\* here"))
    }

    @Test
    fun `a blank body has an empty opening line`() {
        assertEquals("", OpeningLine.of("  \n\n "))
    }

    @Test
    fun `strikethrough shows its words`() {
        assertEquals("old new", OpeningLine.of("~~old~~ new"))
    }

    @Test
    fun `a closed heading drops its closing hashes`() {
        assertEquals("The plan", OpeningLine.of("## The plan ##"))
    }

    @Test
    fun `a trailing hash in prose stays`() {
        assertEquals("Tracked in issue #", OpeningLine.of("Tracked in issue #"))
    }

    @Test
    fun `windows line endings split paragraphs`() {
        assertEquals("First.", OpeningLine.of("First.\r\n\r\nSecond."))
    }
}
