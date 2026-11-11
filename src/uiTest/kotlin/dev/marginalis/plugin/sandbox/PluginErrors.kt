package dev.marginalis.plugin.sandbox

/**
 * The `ERROR` / `SEVERE` entries of an IDE log that pass through the plugin's code: the entry's header (its logger)
 * or any line under it (message, stack trace) names `dev.marginalis`. These are what a user sees as "IDE error
 * occurred" while every check of a scenario still finds the state it expects.
 */
object PluginErrors {

    private val ENTRY = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d+ \[ *\d+] +(\w+) - (.*)$""")
    private const val PLUGIN_PACKAGE = "dev.marginalis"

    /**
     * Each entry as its header line, followed by the first line under it that names the plugin.
     *
     * @param since a timestamp in the log's own format; entries written before it are left out
     */
    fun inLog(lines: List<String>, since: String? = null): List<String> {
        val entries = mutableListOf<List<String>>()
        for (line in if (since == null) lines else writtenSince(lines, since)) {
            when {
                ENTRY.matches(line) -> entries.add(listOf(line))
                entries.isNotEmpty() -> entries[entries.lastIndex] = entries.last() + line
            }
        }
        return entries
            .filter { entry -> ENTRY.matchEntire(entry.first())!!.groupValues[1] in setOf("ERROR", "SEVERE") }
            .filter { entry -> entry.any { PLUGIN_PACKAGE in it } }
            .map { entry ->
                val where = entry.drop(1).firstOrNull { PLUGIN_PACKAGE in it }?.trim()
                listOfNotNull(entry.first(), where).joinToString(" | ")
            }
    }

    /**
     * The lines of a log from its first entry written at or after `since` on; empty when every entry is older.
     *
     * @param since a timestamp in the log's own format
     */
    fun writtenSince(lines: List<String>, since: String): List<String> {
        val first = lines.indexOfFirst { line -> ENTRY.matches(line) && line.take(since.length) >= since }
        return if (first < 0) emptyList() else lines.drop(first)
    }
}
