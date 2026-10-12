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
    ): Layout {
        val previouslyActive = previous.flatMapTo(HashSet()) { entry -> entry.threads.map { it.id } }
        val (open, folded) = stepsInStepOrder(threads.filter { it.isProjectLevel }.sortedWith(order.comparator))
            .partition { it.status !is ThreadStatus.Resolved || (holdsDraft(it.id) && it.id in previouslyActive) }
        val entries = if (groupRelayed) grouped(open) else open.map { Entry.Single(it) }
        val previousIndex = previous.withIndex().associate { (index, entry) -> entry.key to index }
        val (held, free) = entries.partition { entry -> entry.key in previousIndex && entry.threads.any { holdsDraft(it.id) } }
        val active = free.toMutableList()
        held.map { previousIndex.getValue(it.key) to it }.sortedBy { it.first }
            .forEach { (index, entry) -> active.add(index.coerceAtMost(active.size), entry) }
        return Layout(active = active, resolved = folded)
    }

    /** A walkthrough's steps keep the slots the order gave them, refilled in step order, so they read 1, 2, 3 in any order. */
    private fun stepsInStepOrder(sorted: List<CommentThread>): List<CommentThread> {
        val result = sorted.toMutableList()
        sorted.withIndex()
            .filter { (_, thread) -> thread.order != null }
            .groupBy { (_, thread) -> thread.walkthrough ?: "" }
            .values
            .forEach { steps ->
                val slots = steps.map { it.index }
                val inStepOrder = steps.map { it.value }.sortedWith(compareBy({ it.order }, { it.createdAt }))
                slots.zip(inStepOrder).forEach { (slot, step) -> result[slot] = step }
            }
        return result
    }

    private fun grouped(orderedActive: List<CommentThread>): List<Entry> {
        val byDiscussion = orderedActive.groupBy { it.discussion }
        return orderedActive.mapNotNull { thread ->
            val discussion = thread.discussion ?: return@mapNotNull Entry.Single(thread)
            val members = byDiscussion.getValue(discussion)
            Entry.Conversation(discussion, members).takeIf { members.first() === thread }
        }
    }
}
