package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlSanitizerTest {

    private fun clean(html: String) = HtmlSanitizer.sanitize(html)

    @Test
    fun `the markdown generator's own markup passes untouched`() {
        val generated = "<h2>T</h2><p>a <em>b</em> <strong>c</strong> <code>d</code></p>" +
            "<ul><li>x</li></ul><ol start=\"3\"><li>y</li></ol><blockquote><p>q</p></blockquote><hr /><pre><code>z</code></pre>"

        assertEquals(generated.replace("<hr />", "<hr>"), clean(generated))
    }

    @Test
    fun `a GFM table and strikethrough pass, and cells keep only their alignment`() {
        assertEquals(
            "<table><thead><tr><th align=\"left\">a</th></tr></thead><tbody><tr><td align=\"right\"><s>b</s></td></tr></tbody></table>",
            clean(
                "<table style=\"background:url(http://t)\"><thead><tr><th align=\"left\" style=\"x\">a</th></tr></thead>" +
                    "<tbody><tr><td align=\"right\" background=\"http://t\"><s>b</s></td></tr></tbody></table>",
            ),
        )
    }

    @Test
    fun `a link keeps its target and title, and nothing else`() {
        assertEquals(
            "<a href=\"https://x\" title=\"t\">go</a>",
            clean("<a HREF=\"https://x\" onclick=\"y()\" style=\"color:red\" title=\"t\">go</a>"),
        )
    }

    @Test
    fun `attributes on any other tag are dropped, so no stylesheet can fetch a background`() {
        assertEquals("<p>x</p>", clean("<p style=\"background-image:url(http://t/p.gif)\">x</p>"))
    }

    @Test
    fun `images become a placeholder whatever their case, so nothing is fetched`() {
        assertEquals("[image] [image]", clean("<IMG SRC=\"http://t/p.gif\"> <img src='x'/>"))
    }

    @Test
    fun `tags the generator never emits are dropped, keeping their text readable`() {
        assertEquals(
            "Walkthrough<br>Changed files<br>",
            clean("<details><summary>Walkthrough</summary><sub>Changed files</sub></details>"),
        )
        assertEquals("", clean("<iframe src=\"http://t\"></iframe><form action=\"http://t\"><input name=q></form><base href=\"http://t\">"))
    }

    @Test
    fun `comments and declarations vanish`() {
        assertEquals("ab", clean("a<!-- hidden <img src=x> -->b<!DOCTYPE html>"))
    }

    @Test
    fun `tags are normalised to lower case and quotes inside a link target stay inside it`() {
        assertEquals("<em>x</em>", clean("<EM>x</EM>"))
        assertEquals("<a href=\"a&quot;b\">x</a>", clean("<a href='a\"b'>x</a>"))
    }

    @Test
    fun `a tag left unclosed is text, so nothing it names is ever fetched`() {
        val pixel = "<div>\nLooks good <img src=\"http://127.0.0.1:5005/pixel.png?u=reviewer\" alt=\"x"
        assertEquals("\nLooks good &lt;img src=\"http://127.0.0.1:5005/pixel.png?u=reviewer\" alt=\"x", clean(pixel))
        assertEquals("&lt;table background=\"http://t/t.png\"", clean("<table background=\"http://t/t.png\""))
        assertEquals("&lt;div\n&lt;img src=\"http://t/a.png\"", clean("<div\n<img src=\"http://t/a.png\""))
    }

    @Test
    fun `a lone angle bracket is text`() {
        assertEquals("&lt; img src=x>", clean("< img src=x>"))
    }
}
