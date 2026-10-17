package dev.marginalis.core

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class ThreadStore {
    private val threads = ConcurrentHashMap<String, CommentThread>()

    // Threads created in the same clock tick (Windows' clock is coarse) tie on createdAt; they keep the order they
    // came into the store in, which a ConcurrentHashMap alone does not.
    private val arrivals = ConcurrentHashMap<String, Long>()
    private val nextArrival = AtomicLong()
    private val byCreation = compareBy<CommentThread>({ it.createdAt }, { arrivals[it.id] ?: Long.MAX_VALUE })
    private val listeners = CopyOnWriteArrayList<(CommentThread) -> Unit>()
    private val _deletedRelays = ConcurrentHashMap.newKeySet<Relayed.Key>()
    private val relayLock = Any()

    val deletedRelays: Set<Relayed.Key>
        get() = _deletedRelays.toSet()

    fun isDeletedRelay(key: Relayed.Key): Boolean = key in _deletedRelays

    fun restoreDeletedRelays(keys: Set<Relayed.Key>) {
        _deletedRelays.addAll(keys)
    }

    fun add(thread: CommentThread) {
        put(thread)
        notifyChanged(thread)
    }

    /** Rehydration only. */
    fun addSilently(thread: CommentThread) {
        put(thread)
    }

    private fun put(thread: CommentThread) {
        arrivals.computeIfAbsent(thread.id) { nextArrival.incrementAndGet() }
        threads[thread.id] = thread
    }

    fun byId(id: String): CommentThread? = threads[id]

    fun relaying(key: Relayed.Key): CommentThread? = all().firstOrNull { it.relayedMessage(key) != null }

    fun relayInto(thread: CommentThread, message: Message): RelayOutcome {
        val key = requireNotNull(message.relayed) { "only a relayed message can be relayed" }.key
        synchronized(relayLock) {
            if (isDeletedRelay(key)) return RelayOutcome.Deleted
            relaying(key)?.takeIf { it !== thread }?.let { return RelayOutcome.Elsewhere(it) }
            val kept = thread.addRelayedOnce(message)
            return if (kept === message) RelayOutcome.Added(message) else RelayOutcome.Existing(kept)
        }
    }

    fun all(): List<CommentThread> = threads.values.sortedWith(byCreation)

    fun query(
        file: String? = null,
        status: ThreadStatus.Kind? = null,
        intent: Intent? = null,
        awaiting: Turn? = null,
        awaitingFor: String? = null,
        unreadFor: String? = null,
        /** Strictly after: handing back the newest [CommentThread.updatedAt] seen returns only what happened since. */
        updatedAfter: Instant? = null,
    ): List<CommentThread> = all().filter { thread ->
        (file == null || thread.file == file) &&
            (status == null || thread.status.kind == status) &&
            (intent == null || thread.intent == intent) &&
            (awaiting == null || thread.turnFor(awaitingFor) == awaiting) &&
            (unreadFor == null || thread.unreadCountFor(unreadFor) > 0) &&
            (updatedAfter == null || thread.updatedAt > updatedAfter)
    }

    fun hasAwaiting(agent: Author.Agent): Boolean =
        query(awaiting = Turn.AGENT_OWES, awaitingFor = agent.receiptKey).isNotEmpty()

    fun awaitingSnapshot(): (Author.Agent) -> Boolean {
        val addressees = query(awaiting = Turn.AGENT_OWES).map { it.lastSpoken?.to }
        return { agent -> addressees.any { it == null || it == Addressee.Agent(agent.receiptKey) } }
    }

    fun remove(id: String): CommentThread? {
        val removed = threads.remove(id)
        arrivals.remove(id)
        removed?.let { notifyChanged(it) }
        return removed
    }

    fun delete(id: String): CommentThread? {
        threads[id]?.messages?.firstOrNull()?.relayed?.let { _deletedRelays.add(it.key) }
        return remove(id)
    }

    fun clear(): List<CommentThread> {
        val removed = all()
        threads.clear()
        arrivals.clear()
        _deletedRelays.clear()
        removed.forEach { notifyChanged(it) }
        return removed
    }

    /** Listeners run on whatever thread mutated; UI listeners must marshal to their toolkit thread themselves. */
    fun addListener(listener: (CommentThread) -> Unit) {
        listeners.add(listener)
    }

    private fun removeListener(listener: (CommentThread) -> Unit) {
        listeners.remove(listener)
    }

    fun observe(listener: (CommentThread) -> Unit): AutoCloseable {
        addListener(listener)
        return AutoCloseable { removeListener(listener) }
    }

    val listenerCount: Int get() = listeners.size

    fun watch(id: String, onChange: (CommentThread?) -> Unit): AutoCloseable =
        observe { changed -> if (changed.id == id) onChange(byId(id)) }

    fun notifyChanged(thread: CommentThread) {
        for (listener in listeners) listener(thread)
    }
}
