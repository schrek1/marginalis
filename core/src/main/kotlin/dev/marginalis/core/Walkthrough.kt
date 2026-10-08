package dev.marginalis.core

data class WalkPosition(val steps: List<CommentThread>, val index: Int?)

object Walkthrough {

    /** The order steps are walked in: by step number, a tie going to the earlier step. */
    val byStepNumber: Comparator<CommentThread> = compareBy({ it.order }, { it.createdAt })

    fun walkFrom(threads: List<CommentThread>, thread: CommentThread): WalkPosition {
        val open = threads.filter { it.status is ThreadStatus.Open }
        val walk = if (thread.order != null) {
            open.filter { it.order != null && (it.walkthrough ?: "") == (thread.walkthrough ?: "") }
                .sortedWith(byStepNumber)
        } else {
            open.sortedWith(ThreadOrder.byAnchor)
        }
        return WalkPosition(walk, walk.indexOfFirst { it.id == thread.id }.takeIf { it >= 0 })
    }

    /**
     * A label alone can't identify one walkthrough (every unlabeled run shares
     * ""), so the cohort is same-label steps created at or after the earliest
     * open one: finished walkthroughs drop out, steps resolved mid-walk stay counted.
     */
    fun stableTotal(threads: List<CommentThread>, thread: CommentThread): Int? {
        if (thread.order == null) return null
        val label = thread.walkthrough ?: ""
        val sameLabel = threads.filter { it.order != null && (it.walkthrough ?: "") == label }
        val earliestOpen = sameLabel.filter { it.status is ThreadStatus.Open }
            .minOfOrNull { it.createdAt } ?: return null
        return sameLabel.filter { it.createdAt >= earliestOpen }.maxOf { it.order!! }
    }
}
