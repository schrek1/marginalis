package dev.marginalis.core

enum class MarkSubject { LINE, FILE, PROJECT }

data class Mark(val subject: MarkSubject, val intent: Intent?, val step: Boolean = false) {
    companion object {
        fun of(threads: List<CommentThread>): Mark {
            val subject = threads.minOf { it.subject }
            return Mark(
                subject = subject,
                intent = threads.map { it.intent }.distinct().singleOrNull(),
                step = subject == MarkSubject.LINE && threads.all { it.order != null },
            )
        }

        fun all(): List<Mark> {
            val intents = listOf(null) + Intent.entries
            return MarkSubject.entries.flatMap { subject -> intents.map { Mark(subject, it) } } +
                intents.map { Mark(MarkSubject.LINE, it, step = true) }
        }

        private val CommentThread.subject: MarkSubject
            get() = when {
                isProjectLevel -> MarkSubject.PROJECT
                isFileLevel -> MarkSubject.FILE
                else -> MarkSubject.LINE
            }
    }
}
