package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadStoreOrderTest {

    private val sameInstant = Instant.parse("2026-10-17T08:00:00Z")

    private fun threadAt(createdAt: Instant) = CommentThread(file = "a.py", line = 3, anchorText = "def f():", createdAt = createdAt)

    @Test
    fun `threads created in the same instant list in the order they arrived`() {
        // GIVEN - many threads that tie on their creation time, as a coarse clock makes them
        val store = ThreadStore()
        val arrived = List(50) { threadAt(sameInstant) }
        arrived.forEach(store::add)

        // WHEN - the store lists them
        val listed = store.all()

        // THEN - ties keep the order they came in, whatever their ids hash to
        assertEquals(arrived.map { it.id }, listed.map { it.id })
    }

    @Test
    fun `an earlier thread lists first even when it arrives later`() {
        val store = ThreadStore()
        val later = threadAt(sameInstant.plusMillis(1)).also(store::add)
        val earlier = threadAt(sameInstant).also(store::add)

        assertEquals(listOf(earlier.id, later.id), store.all().map { it.id })
    }

    @Test
    fun `a thread removed and added again arrives anew`() {
        val store = ThreadStore()
        val first = threadAt(sameInstant).also(store::add)
        val second = threadAt(sameInstant).also(store::add)

        store.remove(first.id)
        store.add(first)

        assertEquals(listOf(second.id, first.id), store.all().map { it.id })
    }
}
