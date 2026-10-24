package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadStoreOrderTest {

    private val sameInstant = Instant.parse("2026-10-17T08:00:00Z")

    private fun threadAt(createdAt: Instant) = CommentThread(file = "a.py", line = 3, anchorText = "def f():", createdAt = createdAt)

    private fun ThreadStore.listedIds() = all().map { it.id }

    @Test
    fun `threads created in the same instant list in the order they arrived`() {
        val store = ThreadStore()
        val arrived = List(50) { threadAt(sameInstant) }.onEach(store::add)

        assertEquals(arrived.map { it.id }, store.listedIds())
    }

    @Test
    fun `an earlier thread lists first even when it arrives later`() {
        val store = ThreadStore()
        val later = threadAt(sameInstant.plusMillis(1)).also(store::add)
        val earlier = threadAt(sameInstant).also(store::add)

        assertEquals(listOf(earlier.id, later.id), store.listedIds())
    }

    @Test
    fun `a thread removed and added again arrives anew`() {
        val store = ThreadStore()
        val first = threadAt(sameInstant).also(store::add)
        val second = threadAt(sameInstant).also(store::add)
        store.remove(first.id)
        store.add(first)

        assertEquals(listOf(second.id, first.id), store.listedIds())
    }

    @Test
    fun `threads cleared and added again list in their new arrival order`() {
        val store = ThreadStore()
        val threads = List(50) { threadAt(sameInstant) }.onEach(store::add)
        store.clear()
        threads.reversed().forEach(store::add)

        assertEquals(threads.reversed().map { it.id }, store.listedIds())
    }

    @Test
    fun `threads rehydrated silently list in the order they were loaded`() {
        val store = ThreadStore()
        val loaded = List(50) { threadAt(sameInstant) }.onEach(store::addSilently)

        assertEquals(loaded.map { it.id }, store.listedIds())
    }

    @Test
    fun `threads saved in listing order come back in it after a reload`() {
        val saved = ThreadStore().apply { List(50) { threadAt(sameInstant) }.forEach(::add) }
        val reloaded = ThreadStore()

        ThreadsCodec.decode(ThreadsCodec.encode(saved.all())).forEach(reloaded::addSilently)

        assertEquals(saved.listedIds(), reloaded.listedIds())
    }
}
