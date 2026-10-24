package dev.marginalis.core

/**
 * Whether the Margin tab opens with the project. On the project's first open it opens only with an open project
 * thread in it, and unpinned. After a plugin reload it comes back as it was, pin included. A restart is left to the
 * platform's editor restore.
 */
object ProjectTabStartup {

    enum class Outcome { STAY_CLOSED, OPEN, OPEN_PINNED }

    fun outcome(threads: List<CommentThread>, wasOpen: Boolean, wasPinned: Boolean, firstOpen: Boolean): Outcome = when {
        wasOpen && wasPinned -> Outcome.OPEN_PINNED
        wasOpen -> Outcome.OPEN
        firstOpen && threads.any { it.isProjectLevel && it.status is ThreadStatus.Open } -> Outcome.OPEN
        else -> Outcome.STAY_CLOSED
    }
}
