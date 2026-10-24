package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadStoreWatchTest {

    private val agent = Author.Agent("Claude")

    private fun thread() = CommentThread(file = "a.py", line = 3, anchorText = "def f():")

    @Test
    fun `closing an observer takes it off the store`() {
        val store = ThreadStore()
        val observer = store.observe {}
        assertEquals(1, store.listenerCount)

        observer.close()

        assertEquals(0, store.listenerCount)
    }

    @Test
    fun `a watcher hears its own thread change`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        watched.addMessage(Message(agent, "reply"))
        store.notifyChanged(watched)

        assertEquals(1, heard.size)
        assertSame(watched, heard.single())
    }

    @Test
    fun `a watcher ignores other threads`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.add(thread())

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a watcher hears its thread deleted as absence`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.remove(watched.id)

        assertEquals(1, heard.size)
        assertNull(heard.single())
    }

    @Test
    fun `a watcher hears its thread cleared as absence`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        store.add(thread())
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.clear()

        assertEquals(listOf<CommentThread?>(null), heard)
    }

    @Test
    fun `a closed watch hears nothing more`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        val watch = store.watch(watched.id) { heard += it }

        watch.close()
        store.notifyChanged(watched)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `a draft's watcher hears it stored`() {
        val store = ThreadStore()
        val draft = thread()
        val heard = mutableListOf<CommentThread?>()
        store.watch(draft.id) { heard += it }

        store.add(draft)

        assertSame(draft, heard.single())
    }

    @Test
    fun `an observer hears every thread's change`() {
        val store = ThreadStore()
        val heard = mutableListOf<CommentThread>()
        store.observe { heard += it }

        val first = thread().also(store::add)
        val second = thread().also(store::add)
        store.notifyChanged(first)

        assertEquals(listOf(first, second, first), heard)
    }

    @Test
    fun `a closed observer hears nothing more`() {
        val store = ThreadStore()
        val heard = mutableListOf<CommentThread>()
        val observer = store.observe { heard += it }

        observer.close()
        store.add(thread())

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `every change moves its thread to a new revision`() {
        val store = ThreadStore()
        val thread = thread()
        val first = store.revisionOf(thread.id)
        store.add(thread)
        val added = store.revisionOf(thread.id)

        store.notifyChanged(thread)

        assertEquals(3, setOf(first, added, store.revisionOf(thread.id)).size)
    }

    @Test
    fun `a change to another thread moves no revision`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val other = thread().also(store::add)
        val before = store.revisionOf(watched.id)

        store.notifyChanged(other)

        assertEquals(before, store.revisionOf(watched.id))
    }

    @Test
    fun `rehydrating moves no revision`() {
        val store = ThreadStore()
        val thread = thread()
        val before = store.revisionOf(thread.id)

        store.addSilently(thread)

        assertEquals(before, store.revisionOf(thread.id))
    }
}
