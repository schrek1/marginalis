package dev.marginalis.plugin.ui

import dev.marginalis.plugin.testing.SavedProjectTestCase

class AmbiguousReferenceTest : SavedProjectTestCase() {

    private fun thread(id: String, line: Int, body: String) =
        """{"id":"$id","file":"$SAMPLE","line":${line - 1},"anchor_text":"${lineText(line)}","status":"OPEN","created_at":"2026-10-01T10:00:00Z",
            |"messages":[{"id":"$id-m","author":{"kind":"AGENT","id":"claude-main","name":"Claude"},"body":"$body","created_at":"2026-10-01T10:00:00Z"}]}""".trimMargin()

    fun testAReferenceSharedByTwoThreadsWarnsAndOpensNeither() {
        openShop(
            saved = """{"version":1,"threads":[${
                listOf(
                    thread("abcdef12-0001", 5, "First."),
                    thread("abcdef12-0002", 10, "Second."),
                    thread("c0ffee00-0003", 15, "As in mg:abcdef12, again."),
                ).joinToString(",")
            }]}""",
        )
        val panel = user.open("c0ffee00-0003")

        val warnings = user.warningsWhile { user.followLink(panel, "mg:abcdef12") }

        assertEquals(listOf("mg:abcdef12 names 4 threads or messages in this project"), warnings)
        assertEquals(listOf("As in mg:abcdef12, again."), user.panels().map { user.shownText(it).lines().first { line -> line.startsWith("As in") } })
    }
}
