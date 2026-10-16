package dev.marginalis.core

/**
 * Whether the Margin tab opens with the project: only with an open project thread in it, and only on the project's
 * first open or when the tab was open as the project or the plugin went down. A pin survives only that way back.
 */
object ProjectTabStartup {

    enum class Outcome { STAY_CLOSED, OPEN, OPEN_PINNED }

    fun outcome(threads: List<CommentThread>, wasOpen: Boolean, wasPinned: Boolean, firstOpen: Boolean): Outcome {
        val hasContent = threads.any { it.isProjectLevel && it.status is ThreadStatus.Open }
        return when {
            !hasContent || !(wasOpen || firstOpen) -> Outcome.STAY_CLOSED
            wasOpen && wasPinned -> Outcome.OPEN_PINNED
            else -> Outcome.OPEN
        }
    }
}
