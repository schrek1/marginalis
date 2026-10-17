package dev.marginalis.plugin.sandbox

/**
 * The `ERROR` / `SEVERE` entries of an IDE log that pass through the plugin's code: the entry's header (its logger)
 * or any line under it (message, stack trace) names `dev.marginalis`. These are what a user sees as "IDE error
 * occurred" while every check of a scenario still finds the state it expects.
 */
object PluginErrors {

    private val ENTRY = Regex("""^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d+ \[ *\d+] +(\w+) - (.*)$""")
    private const val PLUGIN_PACKAGE = "dev.marginalis"

    /** Each entry as its header line, followed by the first line under it that names the plugin. */
    fun inLog(lines: List<String>): List<String> {
        val entries = mutableListOf<List<String>>()
        for (line in lines) {
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
}
