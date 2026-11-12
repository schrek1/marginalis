package dev.marginalis.core

import java.util.UUID

data class WalkPosition(val steps: List<CommentThread>, val index: Int?)

object Walkthrough {

    val byStepNumber: Comparator<CommentThread> = compareBy({ it.order }, { it.createdAt })

    fun walkFrom(threads: List<CommentThread>, thread: CommentThread): WalkPosition {
        val open = threads.filter { it.status is ThreadStatus.Open }
        val walk = if (thread.order != null) {
            open.filter { it.order != null && it.walkthroughId == thread.walkthroughId }.sortedWith(byStepNumber)
        } else {
            open.filter { it.order == null }.sortedWith(ThreadOrder.byAnchor)
        }
        return WalkPosition(walk, walk.indexOfFirst { it.id == thread.id }.takeIf { it >= 0 })
    }

    fun stableTotal(threads: List<CommentThread>, thread: CommentThread): Int? {
        if (thread.order == null) return null
        val walkthrough = threads.filter { it.order != null && it.walkthroughId == thread.walkthroughId }
        if (walkthrough.none { it.status is ThreadStatus.Open }) return null
        return walkthrough.maxOf { it.order!! }
    }

    fun title(label: String?): String {
        val name = if (label.isNullOrBlank()) "Walkthrough" else label
        return if (name.length > LONGEST_TITLE) name.take(LONGEST_TITLE - ELLIPSIS.length) + ELLIPSIS else name
    }

    private const val LONGEST_TITLE = 40
    private const val ELLIPSIS = "..."

    fun colourSlot(threads: List<CommentThread>, shown: List<CommentThread>, slots: Int): Int? {
        if (shown.isEmpty() || shown.any { it.order == null }) return null
        val walkthroughIds = shown.map { it.walkthroughId }.distinct()
        if (walkthroughIds.size != 1) return null
        val walkthroughId = walkthroughIds.single()
        val positions = threads.withIndex().associate { (index, thread) -> thread.id to index }
        val rank = threads.filter { it.order != null }
            .groupBy { it.walkthroughId }
            .entries
            .sortedWith(compareBy({ (_, steps) -> steps.minOf { it.createdAt } }, { (_, steps) -> positions.getValue(steps.first().id) }))
            .indexOfFirst { it.key == walkthroughId }
        return rank.takeIf { it >= 0 }?.rem(slots)
    }

    fun arrowsOn(threads: List<CommentThread>, selected: StepMark): Arrows {
        val walk = threads.filter { it.order != null && it.status is ThreadStatus.Open && it.walkthroughId == selected.walkthroughId }
            .sortedWith(byStepNumber)
        val index = walk.indexOfFirst { it.id == selected.threadId }
        return when {
            walk.isEmpty() -> Arrows.NONE
            index >= 0 -> Arrows.along(walk, index)
            else -> Arrows.byStepNumber(walk, selected.order)
        }
    }

    fun arrowsFrom(threads: List<CommentThread>, remembered: StepMark?): Arrows {
        val arrows = remembered?.let { arrowsOn(threads, it) } ?: Arrows.NONE
        if (arrows.walk.isNotEmpty()) return arrows
        val firstOpenWalk = ThreadListing.of(threads).walkthroughs
            .map { walkthrough -> walkthrough.steps.filter { it.status is ThreadStatus.Open } }
            .firstOrNull { it.isNotEmpty() }
            ?: return Arrows.NONE
        return Arrows.fromTheStart(firstOpenWalk)
    }

    fun forArrival(threads: List<CommentThread>, label: String?, order: Int): String =
        threads.filter { it.order != null && it.walkthroughId != null && it.walkthrough.orEmpty() == label.orEmpty() }
            .groupBy { it.walkthroughId!! }
            .filterValues { steps -> steps.any { it.status is ThreadStatus.Open } && steps.none { it.order == order } }
            .maxWithOrNull(compareBy({ (_, steps) -> steps.minOf { it.createdAt } }, { (_, steps) -> threads.indexOf(steps.first()) }))
            ?.key
            ?: newWalkthrough()

    fun replay(threads: List<CommentThread>): Map<String, String> =
        threads.filter { it.order != null && it.walkthroughId == null }
            .sortedBy { it.createdAt }
            .groupBy { it.walkthrough.orEmpty() }
            .values
            .flatMap { sameLabel ->
                var current = newWalkthrough()
                val numbers = mutableSetOf<Int>()
                sameLabel.map { step ->
                    if (!numbers.add(step.order!!)) {
                        current = newWalkthrough()
                        numbers.clear()
                        numbers.add(step.order)
                    }
                    step.id to current
                }
            }
            .toMap()

    private fun newWalkthrough(): String = UUID.randomUUID().toString()
}
