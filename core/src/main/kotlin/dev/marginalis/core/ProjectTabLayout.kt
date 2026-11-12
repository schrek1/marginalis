package dev.marginalis.core

object ProjectTabLayout {

    sealed interface Entry {
        val key: String
        val threads: List<CommentThread>

        data class Single(val thread: CommentThread) : Entry {
            override val key: String
                get() = thread.id

            override val threads: List<CommentThread>
                get() = listOf(thread)
        }

        data class Conversation(val discussion: String, override val threads: List<CommentThread>) : Entry {
            override val key: String
                get() = keyOf(discussion)

            val count: Int
                get() = threads.size

            val newCount: Int
                get() = threads.sumOf { it.unreadByUserCount() }

            companion object {
                private const val KEY_PREFIX = "conversation:"

                fun keyOf(discussion: String): String = KEY_PREFIX + discussion
            }
        }
    }

    data class Layout(val active: List<Entry>, val resolved: List<CommentThread>)

    fun arrange(
        threads: List<CommentThread>,
        previous: List<Entry> = emptyList(),
        holdsDraft: (String) -> Boolean = { false },
        groupRelayed: Boolean = true,
        order: ProjectTabOrder = ProjectTabOrder.DEFAULT,
        keepDraftsInPlace: Boolean = true,
    ): Layout {
        val previouslyActive = previous.flatMapTo(HashSet()) { entry -> entry.threads.map { it.id } }
        val (open, folded) = threads.filter { it.isProjectLevel }.sortedWith(order.comparator)
            .partition { it.status !is ThreadStatus.Resolved || (holdsDraft(it.id) && it.id in previouslyActive) }
            .let { (open, folded) -> open to stepsInStepOrder(folded) { it } }
        val entries = if (groupRelayed) grouped(open) else open.map { Entry.Single(it) }
        val previousIndex = previous.withIndex().associate { (index, entry) -> entry.key to index }
        val (held, free) = entries.partition { entry ->
            keepDraftsInPlace && entry.key in previousIndex && entry.threads.any { holdsDraft(it.id) }
        }
        val active = free.toMutableList()
        held.map { previousIndex.getValue(it.key) to it }.sortedBy { it.first }
            .forEach { (index, entry) -> active.add(index.coerceAtMost(active.size), entry) }
        return Layout(active = stepsInStepOrder(active) { (it as? Entry.Single)?.thread }, resolved = folded)
    }

    private fun <T> stepsInStepOrder(sorted: List<T>, stepOf: (T) -> CommentThread?): List<T> {
        val result = sorted.toMutableList()
        sorted.withIndex()
            .mapNotNull { (slot, item) -> stepOf(item)?.takeIf { it.order != null }?.let { SlottedStep(slot, item, it) } }
            .groupBy { it.step.walkthroughId }
            .values
            .forEach { steps ->
                val inStepOrder = steps.sortedWith(compareBy(Walkthrough.byStepNumber) { it.step })
                steps.map { it.slot }.zip(inStepOrder).forEach { (slot, step) -> result[slot] = step.item }
            }
        return result
    }

    private data class SlottedStep<T>(val slot: Int, val item: T, val step: CommentThread)

    private fun grouped(orderedActive: List<CommentThread>): List<Entry> {
        val byDiscussion = orderedActive.groupBy { it.discussion }
        return orderedActive.mapNotNull { thread ->
            val discussion = thread.discussion ?: return@mapNotNull Entry.Single(thread)
            val members = byDiscussion.getValue(discussion)
            Entry.Conversation(discussion, members).takeIf { members.first() === thread }
        }
    }
}
