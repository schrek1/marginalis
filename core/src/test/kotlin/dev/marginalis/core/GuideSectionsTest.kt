package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GuideSectionsTest {

    private val guide = """
        |## Message bodies
        |
        |Write Markdown.
        |
        |<!-- when:summary-line -->
        |Open with a summary.
        |<!-- end:summary-line -->
        |
        |## References
        |""".trimMargin()

    @Test
    fun `a shown section keeps its paragraph without the markers`() {
        assertEquals(
            "## Message bodies\n\nWrite Markdown.\n\nOpen with a summary.\n\n## References\n",
            GuideSections.served(guide, shown = setOf(GuideSections.SUMMARY_LINE)),
        )
    }

    @Test
    fun `a hidden section drops its paragraph and leaves one blank line between its neighbours`() {
        assertEquals(
            "## Message bodies\n\nWrite Markdown.\n\n## References\n",
            GuideSections.served(guide, shown = emptySet()),
        )
    }

    @Test
    fun `a guide with Windows line endings is served the same way`() {
        val windows = guide.replace("\n", "\r\n")

        assertEquals(
            "## Message bodies\r\n\r\nWrite Markdown.\r\n\r\nOpen with a summary.\r\n\r\n## References\r\n",
            GuideSections.served(windows, shown = setOf(GuideSections.SUMMARY_LINE)),
        )
        assertEquals(
            "## Message bodies\r\n\r\nWrite Markdown.\r\n\r\n## References\r\n",
            GuideSections.served(windows, shown = emptySet()),
        )
    }

    @Test
    fun `a section spanning several paragraphs goes as a whole`() {
        val several = "A.\n\n<!-- when:x -->\nB.\n\nC.\n<!-- end:x -->\n\nD.\n"

        assertEquals("A.\n\nD.\n", GuideSections.served(several, shown = emptySet()))
        assertEquals("A.\n\nB.\n\nC.\n\nD.\n", GuideSections.served(several, shown = setOf("x")))
    }

    @Test
    fun `each section follows its own switch`() {
        val two = "<!-- when:a -->\nA.\n<!-- end:a -->\n\n<!-- when:b -->\nB.\n<!-- end:b -->\n\nRest.\n"

        assertEquals("B.\n\nRest.\n", GuideSections.served(two, shown = setOf("b")))
    }

    @Test
    fun `a guide without markers is served as written`() {
        assertEquals("Plain.\n", GuideSections.served("Plain.\n", shown = emptySet()))
    }
}
