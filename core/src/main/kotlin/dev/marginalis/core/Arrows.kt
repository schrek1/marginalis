package dev.marginalis.core

data class StepMark(val walkthroughId: String?, val order: Int, val threadId: String) {
    companion object {
        fun of(thread: CommentThread): StepMark? = thread.order?.let { StepMark(thread.walkthroughId, it, thread.id) }
    }
}

class Arrows(
    val walk: List<CommentThread>,
    val first: CommentThread?,
    val previous: CommentThread?,
    val next: CommentThread?,
    val last: CommentThread?,
) {
    companion object {
        val NONE = Arrows(emptyList(), null, null, null, null)

        fun along(walk: List<CommentThread>, index: Int): Arrows = Arrows(
            walk,
            first = walk.firstOrNull().takeIf { index != 0 },
            previous = walk.getOrNull(index - 1),
            next = walk.getOrNull(index + 1),
            last = walk.lastOrNull().takeIf { index != walk.lastIndex },
        )

        fun fromTheStart(walk: List<CommentThread>): Arrows =
            Arrows(walk, walk.firstOrNull(), null, walk.firstOrNull(), walk.lastOrNull())

        fun byStepNumber(walk: List<CommentThread>, order: Int): Arrows = Arrows(
            walk,
            first = walk.firstOrNull(),
            previous = walk.lastOrNull { it.order!! < order },
            next = walk.firstOrNull { it.order!! > order },
            last = walk.lastOrNull(),
        )
    }
}
