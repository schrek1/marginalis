package dev.marginalis.core

enum class StripeBadge {
    BLOCKER,
    AWAITING_YOU;

    companion object {
        fun of(threads: List<CommentThread>): StripeBadge? {
            val open = threads.filter { it.status is ThreadStatus.Open }
            return when {
                AggregateState.of(open) == AggregateState.OPEN_BLOCKER -> BLOCKER
                TurnTally.of(open).user > 0 -> AWAITING_YOU
                else -> null
            }
        }
    }
}
