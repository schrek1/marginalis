package dev.marginalis.core

object HtmlSanitizer {

    private val MARKUP = Regex("<!--.*?-->|<[!?][^>]*>|<(/?)([A-Za-z][A-Za-z0-9]*)([^>]*)>|<", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("([A-Za-z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))")

    private val GENERATED = setOf(
        "p", "br", "hr", "em", "strong", "code", "pre", "blockquote", "ul", "ol", "li",
        "h1", "h2", "h3", "h4", "h5", "h6", "a", "del",
        "table", "thead", "tbody", "tr", "th", "td",
    )
    private val KEPT_ATTRIBUTES = mapOf(
        "a" to listOf("href", "title"),
        "ol" to listOf("start"),
        "th" to listOf("align"),
        "td" to listOf("align"),
    )
    private val LINE_ENDING = setOf("summary", "details", "div")

    fun sanitize(html: String): String = MARKUP.replace(html) { match ->
        val (closing, rawName, rawAttributes) = match.destructured
        val name = rawName.lowercase()
        when {
            match.value == "<" -> "&lt;"
            name.isEmpty() -> ""
            name == "img" -> "[image]"
            name in GENERATED -> "<$closing$name${if (closing.isEmpty()) keptAttributes(name, rawAttributes) else ""}>"
            closing.isNotEmpty() && name in LINE_ENDING -> "<br>"
            else -> ""
        }
    }

    private fun keptAttributes(tag: String, raw: String): String {
        val kept = KEPT_ATTRIBUTES[tag] ?: return ""
        val values = ATTRIBUTE.findAll(raw).associate { attribute ->
            val (name, doubleQuoted, singleQuoted, bare) = attribute.destructured
            name.lowercase() to doubleQuoted.ifEmpty { singleQuoted.ifEmpty { bare } }
        }
        return kept.mapNotNull { name -> values[name]?.let { " $name=\"${it.replace("\"", "&quot;")}\"" } }.joinToString("")
    }
}
