package dev.marginalis.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class HandBack(
    private val snapshotAwaiting: () -> (Author.Agent) -> Boolean = { { true } },
    private val hasUnseen: (agentKey: String, threadId: String) -> Boolean = { _, _ -> true },
    private val clock: () -> Instant = Instant::now,
) {

    private val lock = Any()
    private val waiters = LinkedHashMap<CompletableFuture<Wake>, Waiter>()
    private val present = LinkedHashMap<String, Author.Agent>()
    private val workingSince = HashMap<String, Instant>()
    private val staying = HashSet<String>()
    private val stopping = HashSet<String>()
    private val liveSubmits = LinkedHashMap<LiveKey, Instant>()
    private val liveDelivered = HashMap<LiveKey, Instant>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var lastAt: Instant? = null
        private set

    private var lastTargets: ((Author.Agent) -> Boolean)? = null

    private var newestStamp: Instant? = null

    private var closed = false

    internal val waiting: Int
        get() = synchronized(lock) { waiters.size }

    val waitingAgents: List<Author.Agent>
        get() = synchronized(lock) { waiters.values.map { it.agent }.distinct() }

    val presence: List<Presence>
        get() = synchronized(lock) { presenceNow() }

    val canSubmitRound: Boolean
        get() = synchronized(lock) { waiters.isNotEmpty() }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun restore(at: Instant?) {
        synchronized(lock) {
            if (at != null && lastAt.let { it == null || at > it }) {
                lastAt = at
                newestStamp = newestStamp?.let { maxOf(it, at) } ?: at
            }
        }
    }

    fun record(): Instant {
        val at: Instant
        val woken = synchronized(lock) {
            at = stamp(clock())
            lastAt = at
            val awaitingNow = snapshotAwaiting()
            val owed = waiters.filterValues { awaitingNow(it.agent) }.keys.toList()
            lastTargets = awaitingNow.takeIf { owed.isNotEmpty() }
            if (owed.isNotEmpty()) wakeToWork(owed, at) else endRound(at)
        }
        deliver(woken, Wake.HandedBack(at), at)
        return at
    }

    fun recordLive(threadId: String, agentKey: String, submittedAt: Instant): Instant {
        val at: Instant
        val woken = synchronized(lock) {
            at = stamp(submittedAt)
            val key = LiveKey(agentKey, threadId)
            liveSubmits.remove(key)
            liveSubmits[key] = at
            val listening = wakeToWork(waitersOf(agentKey), at)
            if (listening.isNotEmpty()) markDelivered(agentKey, listOf(threadId))
            listening
        }
        deliver(woken, Wake.Live(at, listOf(threadId)), at)
        return at
    }

    fun recordLiveNow(threadId: String, agentKey: String): Instant = recordLive(threadId, agentKey, clock())

    fun forgetLive(threadId: String) {
        synchronized(lock) {
            liveSubmits.keys.removeAll { it.threadId == threadId }
            liveDelivered.keys.removeAll { it.threadId == threadId }
        }
    }

    fun liveDeliveredAt(agentKey: String, threadId: String): Instant? =
        synchronized(lock) { liveDelivered[LiveKey(agentKey, threadId)] }

    fun await(
        since: Instant?,
        timeout: Duration,
        agent: Author.Agent = Author.Agent.ANONYMOUS,
        stay: Boolean = false,
    ): CompletableFuture<Wake> {
        val arrival = synchronized(lock) { arrive(since, agent, stay) }
        if (arrival.announce) announceChange()
        val waiter = arrival.waiter
        if (!arrival.parked) return waiter
        waiter.whenComplete { wake, _ ->
            if (synchronized(lock) { leave(waiter, wake) }) announceChange()
        }
        waiter.completeOnTimeout(Wake.TimedOut, timeout.toMillis(), TimeUnit.MILLISECONDS)
        return waiter
    }

    fun stop(agentKey: String) {
        finishStops(synchronized(lock) { listOfNotNull(stopNow(agentKey)) })
    }

    fun stopAll() {
        finishStops(synchronized(lock) { present.keys.toList().mapNotNull(::stopNow) })
    }

    fun close() {
        val released = synchronized(lock) {
            closed = true
            val anyonePresent = present.isNotEmpty()
            present.clear()
            workingSince.clear()
            staying.clear()
            stopping.clear()
            drainWaiters().takeIf { it.isNotEmpty() || anyonePresent }
        }
        released?.keys?.forEach { it.complete(Wake.Closing) }
        if (released != null) announceChange()
    }

    private fun arrive(since: Instant?, agent: Author.Agent, stay: Boolean): Arrival {
        if (closed) return Arrival(CompletableFuture.completedFuture(Wake.Closing), parked = false, announce = false)
        val key = agent.receiptKey
        if (stopping.remove(key)) {
            staying.remove(key)
            return Arrival(CompletableFuture.completedFuture(Wake.Stopped), parked = false, announce = false)
        }
        if (stay) staying += key
        val before = presenceNow()
        present[key] = agent
        val deliveriesCleared = liveDelivered.keys.removeAll { it.agentKey == key }
        val pending = since?.let { pendingWake(it, agent) }
        if (pending == null) {
            workingSince.remove(key)
            val waiter = CompletableFuture<Wake>().also { waiters[it] = Waiter(agent, clock()) }
            return Arrival(waiter, parked = true, announce = true)
        }
        val delivered = when (pending) {
            is Wake.Live -> pending.threadIds
            is Wake.HandedBack -> pending.liveThreadIds
        }
        val roundEndsHere = key !in staying && delivered.isEmpty() && !snapshotAwaiting()(agent)
        when {
            !roundEndsHere -> workingSince[key] = clock()
            waitersOf(key).isEmpty() -> forget(key)
        }
        markDelivered(key, delivered)
        val announce = deliveriesCleared || delivered.isNotEmpty() || presenceNow() != before
        return Arrival(CompletableFuture.completedFuture(pending), parked = false, announce = announce)
    }

    private fun leave(waiter: CompletableFuture<Wake>, wake: Wake?): Boolean {
        val dropped = waiters.remove(waiter) ?: return false
        val key = dropped.agent.receiptKey
        when {
            waitersOf(key).isNotEmpty() -> Unit
            workingSince[key]?.let { it >= dropped.openedAt } == true -> Unit
            wake == Wake.TimedOut && key in staying -> workingSince[key] = clock()
            else -> forget(key)
        }
        return true
    }

    private fun wakeToWork(woken: List<CompletableFuture<Wake>>, at: Instant): Map<CompletableFuture<Wake>, String> =
        woken.mapNotNull { future ->
            waiters.remove(future)?.agent?.receiptKey?.let { key -> workingSince[key] = at; future to key }
        }.toMap()

    private fun endRound(at: Instant): Map<CompletableFuture<Wake>, String> {
        val ended = waiters.values.map { it.agent.receiptKey }.toSet()
        ended.forEach { key -> if (key in staying) workingSince[key] = at else forget(key) }
        return drainWaiters()
    }

    private fun stopNow(agentKey: String): Map<CompletableFuture<Wake>, String>? {
        if (agentKey !in present) return null
        val listening = waitersOf(agentKey).onEach(waiters::remove)
        if (listening.isEmpty()) stopping += agentKey
        staying.remove(agentKey)
        forget(agentKey)
        return listening.associateWith { agentKey }
    }

    private fun finishStops(stopped: List<Map<CompletableFuture<Wake>, String>>) {
        stopped.forEach { listening ->
            listening.forEach { (future, key) -> if (!future.complete(Wake.Stopped) && !future.isCancelled) stopAgain(key) }
        }
        if (stopped.isNotEmpty()) announceChange()
    }

    private fun stopAgain(agentKey: String) {
        val restopped = synchronized(lock) { stopNow(agentKey).also { if (it == null) stopping += agentKey } }
        finishStops(listOfNotNull(restopped))
    }

    private fun deliver(woken: Map<CompletableFuture<Wake>, String>, wake: Wake, at: Instant) {
        val (reached, missed) = woken.entries.partition { it.key.complete(wake) }
        val missedEntirely = missed.filter { miss -> reached.none { it.value == miss.value } }
        if (missedEntirely.isNotEmpty()) synchronized(lock) { missedEntirely.forEach { (future, key) -> settleMissedWake(future, key, at) } }
        if (woken.isNotEmpty()) announceChange()
    }

    private fun settleMissedWake(future: CompletableFuture<Wake>, agentKey: String, at: Instant) {
        val stillWorkingFromThisWake = workingSince[agentKey] == at && waitersOf(agentKey).isEmpty()
        if (stillWorkingFromThisWake && (future.isCancelled || agentKey !in staying)) forget(agentKey)
    }

    private fun forget(agentKey: String) {
        present.remove(agentKey)
        workingSince.remove(agentKey)
    }

    private fun waitsOf(agentKey: String): Map<CompletableFuture<Wake>, Waiter> =
        waiters.filterValues { it.agent.receiptKey == agentKey }

    private fun waitersOf(agentKey: String): List<CompletableFuture<Wake>> = waitsOf(agentKey).keys.toList()

    private fun presenceNow(): List<Presence> = present.mapNotNull { (key, agent) ->
        val stays = key in staying
        val listeningSince = waitsOf(key).values.minOfOrNull { it.openedAt }
        when {
            listeningSince != null -> Presence(agent, Presence.State.LISTENING, listeningSince, stays)
            else -> workingSince[key]?.let { Presence(agent, Presence.State.WORKING, it, stays) }
        }
    }

    private fun markDelivered(agentKey: String, threadIds: List<String>) {
        val deliveredAt = clock()
        threadIds.forEach { liveDelivered[LiveKey(agentKey, it)] = deliveredAt }
    }

    private class Waiter(val agent: Author.Agent, val openedAt: Instant)

    private class Arrival(val waiter: CompletableFuture<Wake>, val parked: Boolean, val announce: Boolean)

    private fun pendingWake(since: Instant, agent: Author.Agent): Wake.Delivery? {
        val live = liveSubmits.filter { (key, at) ->
            key.agentKey == agent.receiptKey && at > since && hasUnseen(key.agentKey, key.threadId)
        }
        val newestLive = live.values.maxOrNull()
        val handedBack = lastAt?.takeIf { it > since && lastTargets?.invoke(agent) != false }
        return when {
            handedBack != null -> Wake.HandedBack(maxOf(handedBack, newestLive ?: handedBack), live.keys.map { it.threadId })
            newestLive != null -> Wake.Live(newestLive, live.keys.map { it.threadId })
            else -> null
        }
    }

    private fun stamp(candidate: Instant): Instant =
        (newestStamp?.takeIf { candidate <= it }?.plusNanos(1) ?: candidate).also { newestStamp = it }

    private data class LiveKey(val agentKey: String, val threadId: String)

    private fun drainWaiters(): Map<CompletableFuture<Wake>, String> =
        waiters.mapValues { it.value.agent.receiptKey }.also { waiters.clear() }

    private fun announceChange() = listeners.forEach { it() }

    companion object {
        fun over(threads: ThreadStore, clock: () -> Instant = Instant::now): HandBack = HandBack(
            snapshotAwaiting = threads::awaitingSnapshot,
            hasUnseen = { agentKey, threadId -> LiveThread.hasUnseen(threads.byId(threadId), agentKey) },
            clock = clock,
        )
    }
}
