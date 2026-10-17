package dev.marginalis.core

import io.kotest.matchers.collections.shouldContainExactly
import java.time.Instant
import kotlin.test.Test

class ThreadStoreOrderTest {

    private val sameInstant = Instant.parse("2026-10-17T08:00:00Z")

    private fun threadAt(createdAt: Instant) = CommentThread(file = "a.py", line = 3, anchorText = "def f():", createdAt = createdAt)

    private fun ThreadStore.listedIds() = all().map { it.id }

    @Test
    fun `threads created in the same instant list in the order they arrived`() {
        // GIVEN - many threads that tie on their creation time, as a coarse clock makes them
        val store = ThreadStore()
        val arrived = List(50) { threadAt(sameInstant) }.onEach(store::add)

        // WHEN - the store lists them
        val listed = store.listedIds()

        // THEN - ties keep the order they came in, whatever their ids hash to
        listed shouldContainExactly arrived.map { it.id }
    }

    @Test
    fun `an earlier thread lists first even when it arrives later`() {
        // GIVEN - the later thread reaches the store first
        val store = ThreadStore()
        val later = threadAt(sameInstant.plusMillis(1)).also(store::add)
        val earlier = threadAt(sameInstant).also(store::add)

        // WHEN - the store lists them
        val listed = store.listedIds()

        // THEN - creation time still decides
        listed shouldContainExactly listOf(earlier.id, later.id)
    }

    @Test
    fun `a thread removed and added again arrives anew`() {
        // GIVEN - two threads of the same instant, the first one removed and added back
        val store = ThreadStore()
        val first = threadAt(sameInstant).also(store::add)
        val second = threadAt(sameInstant).also(store::add)
        store.remove(first.id)
        store.add(first)

        // WHEN - the store lists them
        val listed = store.listedIds()

        // THEN - the re-added thread counts as the latest arrival
        listed shouldContainExactly listOf(second.id, first.id)
    }
}
