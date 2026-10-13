package dev.marginalis.core

/**
 * A long thread shows where it started and where it stands: the first message, the last two, and every message the
 * user has not read; each run of read messages between them hides behind one row.
 */
object HistoryShortener {

    const val SHORTEN_ABOVE = 4
    private const val KEEP_TAIL = 2

    sealed interface Row {
        data class Shown(val message: Message) : Row
        data class Hidden(val messages: List<Message>) : Row
    }

    fun rows(messages: List<Message>, fullHistory: Boolean, keep: (Message) -> Boolean = { false }): List<Row> {
        if (fullHistory || messages.size <= SHORTEN_ABOVE) return messages.map(Row::Shown)
        val rows = mutableListOf<Row>()
        val run = mutableListOf<Message>()
        messages.forEachIndexed { index, message ->
            val shown = index == 0 || index >= messages.size - KEEP_TAIL || !message.readByUser || keep(message)
            if (shown) {
                if (run.isNotEmpty()) rows += Row.Hidden(run.toList()).also { run.clear() }
                rows += Row.Shown(message)
            } else {
                run += message
            }
        }
        return rows
    }
}
