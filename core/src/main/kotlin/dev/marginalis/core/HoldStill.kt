package dev.marginalis.core

import dev.marginalis.core.ProjectTabLayout.Entry
import dev.marginalis.core.ProjectTabLayout.Layout

class HoldStill {
    private var frozen: List<Entry>? = null
    private val welcomed = HashSet<String>()

    data class View(val shown: Layout, val dimmed: Set<String>, val pending: Int)

    fun update(live: Layout, inFront: Boolean): View {
        if (!inFront || regrouped(live)) release()
        val held = frozen
        if (held == null) {
            if (inFront) {
                frozen = live.active
                live.resolved.mapTo(welcomed) { it.id }
            }
            return View(shown = live, dimmed = emptySet(), pending = 0)
        }
        val now = Arrangement(live)
        val heldKeys = held.mapTo(HashSet()) { it.key }
        val (welcome, unseen) = live.active
            .filter { it.key !in heldKeys }
            .partition { entry -> entry.threads.any { it.id in welcomed } }
        val shown = welcome + held.mapNotNull(now::holding)
        frozen = shown
        val dimmed = shown.flatMapTo(LinkedHashSet(), now::resolvedJustNow)
        return View(
            shown = Layout(active = shown, resolved = live.resolved.filter { it.id !in dimmed }),
            dimmed = dimmed,
            pending = unseen.size,
        )
    }

    fun admit(threadId: String) {
        welcomed += threadId
    }

    fun release() {
        frozen = null
        welcomed.clear()
    }

    private fun regrouped(live: Layout): Boolean {
        val heldKeyOf = frozen.orEmpty().flatMap { entry -> entry.threads.map { it.id to entry.key } }.toMap()
        return live.active.any { entry -> entry.threads.any { thread -> heldKeyOf[thread.id].let { it != null && it != entry.key } } }
    }

    private class Arrangement(live: Layout) {
        private val open = live.active.flatMap { it.threads }.associateBy { it.id }
        private val folded = live.resolved.associateBy { it.id }
        private val entries = live.active.associateBy { it.key }

        fun holding(entry: Entry): Entry? = when (entry) {
            is Entry.Single -> find(entry.thread.id)?.let(Entry::Single)
            is Entry.Conversation -> {
                val heldIds = entry.threads.mapTo(HashSet()) { it.id }
                val joined = entries[entry.key]?.threads.orEmpty().filter { it.id !in heldIds }
                val members = joined + entry.threads.mapNotNull { find(it.id) }
                members.takeIf { it.isNotEmpty() }?.let { Entry.Conversation(entry.discussion, it) }
            }
        }

        fun resolvedJustNow(entry: Entry): List<String> {
            val resolvedIds = entry.threads.map { it.id }.filter { it in folded }
            val wholeConversation = entry is Entry.Conversation && resolvedIds.size == entry.threads.size
            return if (wholeConversation) resolvedIds + entry.key else resolvedIds
        }

        private fun find(id: String): CommentThread? = open[id] ?: folded[id]
    }
}
