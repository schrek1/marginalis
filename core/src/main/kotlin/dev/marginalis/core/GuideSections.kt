package dev.marginalis.core

object GuideSections {

    const val SUMMARY_LINE = "summary-line"

    fun served(guide: String, shown: Set<String>): String = MARKED
        .replace(guide) { section -> if (section.groupValues[1] in shown) section.groupValues[2] + section.groupValues[3] else "" }

    private val MARKED = Regex(
        """^<!-- when:([a-z-]+) -->\r?\n(.*?)^<!-- end:\1 -->\r?\n(\r?\n(?=.))?""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )
}
