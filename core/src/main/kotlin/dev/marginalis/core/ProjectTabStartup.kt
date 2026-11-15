package dev.marginalis.core

object ProjectTabStartup {

    enum class Outcome { LEAVE, OPEN, OPEN_PINNED, CLOSE }

    enum class TabState { CLOSED, OPEN, PINNED }

    fun outcome(
        shows: MarginTabShows,
        threads: List<CommentThread>,
        firstOpen: Boolean,
        beforeReload: TabState,
        restored: TabState,
    ): Outcome = when {
        beforeReload == TabState.PINNED -> Outcome.OPEN_PINNED
        shows == MarginTabShows.ALWAYS && firstOpen -> Outcome.OPEN_PINNED
        beforeReload == TabState.OPEN -> Outcome.OPEN
        shows == MarginTabShows.ALWAYS -> Outcome.LEAVE
        restored == TabState.OPEN -> if (hasOpenProjectThread(threads)) Outcome.LEAVE else Outcome.CLOSE
        restored == TabState.PINNED -> Outcome.LEAVE
        firstOpen && hasOpenProjectThread(threads) -> Outcome.OPEN
        else -> Outcome.LEAVE
    }

    fun hasOpenProjectThread(threads: List<CommentThread>): Boolean =
        threads.any { it.isProjectLevel && it.status is ThreadStatus.Open }
}
