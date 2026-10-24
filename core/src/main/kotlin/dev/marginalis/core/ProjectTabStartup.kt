package dev.marginalis.core

/**
 * Whether the Margin tab opens with the project. With [outcome]'s `showOnOpen` on, a project's first open shows it
 * only with an open project thread in it, and unpinned, and a restart is left to the platform's editor restore. With
 * it off, no project open shows it. After a plugin reload it comes back as it was, pin included, either way.
 */
object ProjectTabStartup {

    enum class Outcome { STAY_CLOSED, OPEN, OPEN_PINNED }

    fun outcome(
        threads: List<CommentThread>,
        wasOpen: Boolean,
        wasPinned: Boolean,
        firstOpen: Boolean,
        showOnOpen: Boolean,
    ): Outcome = when {
        wasOpen && wasPinned -> Outcome.OPEN_PINNED
        wasOpen -> Outcome.OPEN
        showOnOpen && firstOpen && threads.any { it.isProjectLevel && it.status is ThreadStatus.Open } -> Outcome.OPEN
        else -> Outcome.STAY_CLOSED
    }
}
