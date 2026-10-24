package dev.marginalis.core

object OpeningLine {

    fun of(body: String): String = blocks(body.lines())
        .map { block -> if (block.isCode) block.lines.joinToString(" ") else prose(block.lines) }
        .map { it.replace(WHITESPACE, " ").trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

    private class Block(val lines: List<String>, val isCode: Boolean)

    private fun blocks(lines: List<String>): Sequence<Block> = sequence {
        val current = mutableListOf<String>()
        var inFence = false
        suspend fun SequenceScope<Block>.flush() {
            if (current.isNotEmpty()) yield(Block(current.toList(), isCode = inFence))
            current.clear()
        }
        for (line in lines) {
            when {
                FENCE.matches(line) -> {
                    flush()
                    inFence = !inFence
                }
                line.isBlank() -> flush()
                !inFence && current.isNotEmpty() && HEADING_UNDERLINE.matches(line) -> flush()
                !inFence && RULE.matches(line) -> flush()
                !inFence && HEADING.containsMatchIn(line) -> {
                    flush()
                    yield(Block(listOf(line), isCode = false))
                }
                else -> current += line
            }
        }
        flush()
    }

    private fun prose(lines: List<String>): String = inline(lines.joinToString(" ") { it.withoutBlockMarkers() })

    private fun String.withoutBlockMarkers(): String = this
        .replace(QUOTE_MARKERS, "")
        .withoutHeadingMarkers()
        .replace(LIST_MARKER, "")
        .replace(TASK_BOX, "")

    private fun String.withoutHeadingMarkers(): String =
        if (HEADING.containsMatchIn(this)) replace(HEADING, "").replace(CLOSING_HASHES, "") else this

    private fun inline(text: String): String = buildString {
        var consumedUpTo = 0
        for (span in CODE_SPAN.findAll(text)) {
            append(withoutInlineMarkup(text.substring(consumedUpTo, span.range.first)))
            append(span.groupValues[2].trim())
            consumedUpTo = span.range.last + 1
        }
        append(withoutInlineMarkup(text.substring(consumedUpTo)))
    }

    private fun withoutInlineMarkup(text: String): String = INLINE_MARKUP.fold(text) { stripped, (markup, kept) ->
        stripped.replace(markup, kept)
    }

    private val WHITESPACE = Regex("\\s+")
    private val FENCE = Regex("^\\s{0,3}(```|~~~).*$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}(\\s+|$)")
    private val HEADING_UNDERLINE = Regex("^\\s{0,3}(=+|-+)\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val CLOSING_HASHES = Regex("\\s+#+\\s*$")
    private val QUOTE_MARKERS = Regex("^\\s*(>\\s?)+")
    private val LIST_MARKER = Regex("^\\s*([-*+]|\\d{1,9}[.)])\\s+")
    private val TASK_BOX = Regex("^\\[[ xX]]\\s+")
    private val CODE_SPAN = Regex("(`+)(.+?)\\1")

    private val INLINE_MARKUP = listOf(
        Regex("!\\[([^\\]]*)]\\([^)]*\\)") to "$1",
        Regex("\\[([^\\]]*)]\\([^)]*\\)") to "$1",
        Regex("\\[([^\\]]+)]\\[[^\\]]*]") to "$1",
        Regex("<((?:https?|mailto|ftp):[^>\\s]+)>") to "$1",
        Regex("(?<!\\\\)\\*\\*(?=\\S)(.+?)(?<=\\S)(?<!\\\\)\\*\\*") to "$1",
        Regex("(?<![\\w\\\\])__(?=\\S)(.+?)(?<=\\S)__(?!\\w)") to "$1",
        Regex("(?<![\\\\*])\\*(?=[^\\s*])(.+?)(?<=[^\\s\\\\*])\\*(?!\\*)") to "$1",
        Regex("(?<![\\w\\\\])_(?=\\S)(.+?)(?<=\\S)_(?!\\w)") to "$1",
        Regex("~~(.+?)~~") to "$1",
        Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!~>|<])") to "$1",
    )
}
