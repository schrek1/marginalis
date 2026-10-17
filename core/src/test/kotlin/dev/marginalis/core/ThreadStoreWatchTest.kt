package dev.marginalis.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.test.Test

class ThreadStoreWatchTest {

    private val agent = Author.Agent("Claude")

    private fun thread() = CommentThread(file = "a.py", line = 3, anchorText = "def f():")

    @Test
    fun `closing an observer takes it off the store`() {
        val store = ThreadStore()
        val observer = store.observe {}
        store.listenerCount shouldBe 1

        observer.close()

        store.listenerCount shouldBe 0
    }

    @Test
    fun `a watcher hears its own thread change`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        watched.addMessage(Message(agent, "reply"))
        store.notifyChanged(watched)

        heard shouldHaveSize 1
        heard.single() shouldBeSameInstanceAs watched
    }

    @Test
    fun `a watcher ignores other threads`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.add(thread())

        heard.shouldBeEmpty()
    }

    @Test
    fun `a watcher hears its thread deleted as absence`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.remove(watched.id)

        heard shouldHaveSize 1
        heard.single().shouldBeNull()
    }

    @Test
    fun `a watcher hears its thread cleared as absence`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        store.add(thread())
        val heard = mutableListOf<CommentThread?>()
        store.watch(watched.id) { heard += it }

        store.clear()

        heard shouldContainExactly listOf(null)
    }

    @Test
    fun `a closed watch hears nothing more`() {
        val store = ThreadStore()
        val watched = thread().also(store::add)
        val heard = mutableListOf<CommentThread?>()
        val watch = store.watch(watched.id) { heard += it }

        watch.close()
        store.notifyChanged(watched)

        heard.shouldBeEmpty()
    }

    @Test
    fun `a draft's watcher hears it stored`() {
        val store = ThreadStore()
        val draft = thread()
        val heard = mutableListOf<CommentThread?>()
        store.watch(draft.id) { heard += it }

        store.add(draft)

        heard.single() shouldBeSameInstanceAs draft
    }

    @Test
    fun `an observer hears every thread's change`() {
        val store = ThreadStore()
        val heard = mutableListOf<CommentThread>()
        store.observe { heard += it }

        val first = thread().also(store::add)
        val second = thread().also(store::add)
        store.notifyChanged(first)

        heard shouldContainExactly listOf(first, second, first)
    }

    @Test
    fun `a closed observer hears nothing more`() {
        val store = ThreadStore()
        val heard = mutableListOf<CommentThread>()
        val observer = store.observe { heard += it }

        observer.close()
        store.add(thread())

        heard.shouldBeEmpty()
    }
}
