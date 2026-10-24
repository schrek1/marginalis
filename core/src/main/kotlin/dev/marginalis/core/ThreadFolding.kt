package dev.marginalis.core

enum class LongThreadFolding(val stored: String) {
    FOLD_EVERY_READ_MESSAGE("FOLD_EVERY_READ_MESSAGE"),
    FOLD_LONG_READ_MESSAGES("FOLD_LONG_READ_MESSAGES"),
    FOLD_NOTHING("FOLD_NOTHING");

    fun folds(message: Message): Boolean = when (this) {
        FOLD_EVERY_READ_MESSAGE -> true
        FOLD_LONG_READ_MESSAGES -> ThreadFolding.isLong(message.body)
        FOLD_NOTHING -> false
    }

    companion object {
        fun fromStored(stored: String): LongThreadFolding = entries.firstOrNull { it.stored == stored } ?: FOLD_EVERY_READ_MESSAGE
    }
}

data class FoldingPrefs(
    val atDefault: LongThreadFolding = LongThreadFolding.FOLD_EVERY_READ_MESSAGE,
    val keepFirstOpen: Boolean = false,
)

object ThreadFolding {

    private const val OPEN_AT_THE_END = 2
    private const val STACK_ABOVE = 3
    private const val LONGER_THAN_LINES = 3
    private const val LINE_WIDTH = 80

    enum class Level {
        ALL_FOLDED, DEFAULT, ALL_EXPANDED;

        fun folded(): Level? = entries.getOrNull(ordinal - 1)

        fun expanded(): Level? = entries.getOrNull(ordinal + 1)
    }

    sealed interface Row {
        data class Open(val message: Message) : Row
        data class Folded(val message: Message) : Row
        data class Stack(val messages: List<Message>) : Row
    }

    data class View(
        val level: Level = Level.DEFAULT,
        val keptOpen: Set<String> = emptySet(),
        val editing: String? = null,
        val opened: Set<String> = emptySet(),
        val folded: Set<String> = emptySet(),
        val revealed: Set<String> = emptySet(),
    )

    fun isLong(body: String): Boolean =
        body.lines().filter(String::isNotBlank).sumOf { (it.length + LINE_WIDTH - 1) / LINE_WIDTH } > LONGER_THAN_LINES

    fun rows(messages: List<Message>, view: View, prefs: FoldingPrefs = FoldingPrefs()): List<Row> {
        val openByRule = messages.filterNot(Message::agrees).takeLast(OPEN_AT_THE_END).mapTo(mutableSetOf()) { it.id }
        if (prefs.keepFirstOpen) messages.firstOrNull()?.let { openByRule += it.id }
        val rows = mutableListOf<Row>()
        val run = mutableListOf<Message>()
        fun endRun() {
            if (run.size > STACK_ABOVE) rows += Row.Stack(run.toList()) else run.mapTo(rows, Row::Folded)
            run.clear()
        }
        messages.forEachIndexed { index, message ->
            when {
                !folds(message, view, prefs, openByRule) -> {
                    endRun()
                    rows += Row.Open(message)
                }
                index > 0 && stacks(message, view) -> run += message
                else -> {
                    endRun()
                    rows += Row.Folded(message)
                }
            }
        }
        endRun()
        return rows
    }

    private fun folds(message: Message, view: View, prefs: FoldingPrefs, openByRule: Set<String>): Boolean = when {
        message.agrees || message.id == view.editing || message.id in view.opened -> false
        message.id in view.folded -> true
        else -> when (view.level) {
            Level.ALL_FOLDED -> true
            Level.ALL_EXPANDED -> false
            Level.DEFAULT -> message.readByUser && message.id !in view.keptOpen && message.id !in openByRule && prefs.atDefault.folds(message)
        }
    }

    private fun stacks(message: Message, view: View): Boolean =
        view.level != Level.ALL_EXPANDED && message.id !in view.folded && message.id !in view.revealed
}
