package dev.marginalis.core

data class ProjectTabTally(val open: Int, val turns: TurnTally, val signals: TurnTally, val newCount: Int) {

    val title: String
        get() = TurnSignal.glyphs(signals).ifEmpty { NOTHING_WAITS }

    companion object {
        const val NOTHING_WAITS = "Margin"

        fun of(threads: List<CommentThread>): ProjectTabTally {
            val open = threads.filter { it.isProjectLevel && it.status is ThreadStatus.Open }
            return ProjectTabTally(
                open = open.size,
                turns = TurnTally.of(open),
                signals = TurnTally.signalled(open),
                newCount = open.sumOf { it.unreadByUserCount() },
            )
        }
    }
}
