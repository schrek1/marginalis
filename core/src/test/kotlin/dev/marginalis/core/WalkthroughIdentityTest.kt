package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WalkthroughIdentityTest {

    private val user = Author.User("Muhammad")
    private var tick = 0L

    private fun step(order: Int, walkthroughId: String?, label: String? = null, createdAt: Instant = Instant.ofEpochSecond(tick++)) =
        CommentThread(
            file = "a.py", line = order, anchorText = "x",
            createdAt = createdAt, order = order, walkthrough = label, walkthroughId = walkthroughId,
        )

    private fun comment() = CommentThread("a.py", 1, "x", createdAt = Instant.ofEpochSecond(tick++))

    private fun MutableList<CommentThread>.arrive(label: String?, vararg orders: Int): List<CommentThread> {
        return orders.map { order -> step(order, Walkthrough.forArrival(this, label, order), label).also(::add) }
    }

    private fun walkthroughsOf(vararg steps: List<CommentThread>): List<Set<String?>> =
        steps.map { group -> group.map { it.walkthroughId }.toSet() }

    @Test
    fun `two unlabelled walkthroughs walk apart`() {
        val first = listOf(step(1, "w1"), step(2, "w1"))
        val second = listOf(step(1, "w2"), step(2, "w2"))
        val (walk, position) = Walkthrough.walkFrom(first + second, second[0])
        assertEquals(second, walk)
        assertEquals(0, position)
    }

    @Test
    fun `an unlabelled batch arriving while another unlabelled walkthrough is open starts its own`() {
        val threads = mutableListOf<CommentThread>()
        val first = threads.arrive(null, 1, 2, 3)
        val second = threads.arrive(null, 1, 2, 3)
        val (firstIds, secondIds) = walkthroughsOf(first, second)
        assertEquals(1, firstIds.size)
        assertEquals(1, secondIds.size)
        assertNotEquals(firstIds, secondIds)
        assertEquals(first, Walkthrough.walkFrom(threads, first[1]).steps)
    }

    @Test
    fun `steps appended to a walkthrough still open join it`() {
        val threads = mutableListOf<CommentThread>()
        val opening = threads.arrive("tour", 1, 2, 3, 4, 5)
        val appended = threads.arrive("tour", 6, 7, 8, 9)
        assertEquals(walkthroughsOf(opening), walkthroughsOf(appended))
        assertEquals(opening + appended, Walkthrough.walkFrom(threads, appended[0]).steps)
    }

    @Test
    fun `a batch whose first step the open walkthrough already has starts a new one and keeps the rest of the batch with it`() {
        val threads = mutableListOf<CommentThread>()
        val older = threads.arrive("tour", 1, 2, 3)
        val newer = threads.arrive("tour", 1, 2, 3, 4, 5)
        val (olderIds, newerIds) = walkthroughsOf(older, newer)
        assertEquals(1, newerIds.size)
        assertNotEquals(olderIds, newerIds)
        assertEquals(newer, Walkthrough.walkFrom(threads, newer[4]).steps)
    }

    @Test
    fun `a single step joins the most recently started walkthrough that lacks its number`() {
        val threads = mutableListOf<CommentThread>()
        threads.arrive("tour", 1, 2)
        val newer = threads.arrive("tour", 1)
        val two = threads.arrive("tour", 2)
        assertEquals(walkthroughsOf(newer), walkthroughsOf(two))
    }

    @Test
    fun `a step passes over a walkthrough that already has its number, even resolved`() {
        val threads = mutableListOf<CommentThread>()
        threads.arrive("tour", 1, 2)
        val newer = threads.arrive("tour", 1, 2).onEach { if (it.order == 2) it.resolve(user) }
        val older = threads.filter { it !in newer }
        val two = threads.arrive("tour", 2)
        assertEquals(1, walkthroughsOf(two).single().size)
        assertNotEquals(walkthroughsOf(newer).single(), walkthroughsOf(two).single())
        assertNotEquals(walkthroughsOf(older).single(), walkthroughsOf(two).single())
    }

    @Test
    fun `a walkthrough with nothing open takes no new steps`() {
        val threads = mutableListOf<CommentThread>()
        val finished = threads.arrive("tour", 1, 2).onEach { it.resolve(user) }
        val three = threads.arrive("tour", 3)
        assertNotEquals(walkthroughsOf(finished), walkthroughsOf(three))
    }

    @Test
    fun `a step never joins a walkthrough with another label`() {
        val threads = mutableListOf<CommentThread>()
        val a = threads.arrive("A", 1)
        val b = threads.arrive("B", 2)
        val unlabelled = threads.arrive(null, 3)
        assertEquals(3, walkthroughsOf(a, b, unlabelled).toSet().size)
    }

    @Test
    fun `one batch keeps each label's steps together and its labels apart`() {
        val threads = mutableListOf<CommentThread>()
        val (a1) = threads.arrive("A", 1)
        val (b1) = threads.arrive("B", 1)
        val (a2) = threads.arrive("A", 2)
        assertEquals(a1.walkthroughId, a2.walkthroughId)
        assertNotEquals(a1.walkthroughId, b1.walkthroughId)
    }

    @Test
    fun `a number repeated within one batch starts the next walkthrough, as separate calls would`() {
        val threads = mutableListOf<CommentThread>()
        val steps = threads.arrive("A", 1, 2, 1, 2)
        val ids = steps.map { it.walkthroughId }
        assertEquals(ids[0], ids[1])
        assertEquals(ids[2], ids[3])
        assertNotEquals(ids[0], ids[2])
    }

    @Test
    fun `a batch number already stored in the walkthrough it joined, even resolved, starts the next walkthrough`() {
        val threads = mutableListOf(step(1, "w", "tour"), step(3, "w", "tour").also { it.resolve(user) })
        val (two, three) = threads.arrive("tour", 2, 3)
        assertEquals("w", two.walkthroughId)
        assertNotEquals("w", three.walkthroughId)
    }

    @Test
    fun `a batch step that was never created leaves later steps to join the open walkthrough`() {
        val threads = mutableListOf<CommentThread>()
        val open = threads.arrive("A", 1).single()
        Walkthrough.forArrival(threads, "A", 1)
        assertEquals(open.walkthroughId, Walkthrough.forArrival(threads, "A", 2))
    }

    @Test
    fun `a reopened or rescued step keeps its walkthrough`() {
        val resolved = step(1, "w1").also { it.resolve(user) }
        val orphaned = step(2, "w1").also { it.markOrphaned() }
        resolved.reopen()
        orphaned.rescueTo(7, "y")
        assertEquals(listOf("w1", "w1"), listOf(resolved.walkthroughId, orphaned.walkthroughId))
    }

    @Test
    fun `stored steps without a walkthrough replay by label in creation order, a repeated number starting the next`() {
        val a1 = step(1, null, "A")
        val a2 = step(2, null, "A")
        val b1 = step(1, null, "B")
        val again1 = step(1, null, "A")
        val again3 = step(3, null, "A")
        val loose1 = step(1, null)
        val loose2 = step(2, null)
        val plain = comment()

        val replayed = Walkthrough.replay(listOf(again3, plain, a2, loose2, b1, again1, a1, loose1))

        val walkthroughOf = { thread: CommentThread -> replayed[thread.id] }
        assertEquals(walkthroughOf(a1), walkthroughOf(a2))
        assertEquals(walkthroughOf(again1), walkthroughOf(again3))
        assertEquals(walkthroughOf(loose1), walkthroughOf(loose2))
        assertEquals(4, setOf(walkthroughOf(a1), walkthroughOf(again1), walkthroughOf(b1), walkthroughOf(loose1)).size)
        assertEquals(null, walkthroughOf(plain))
    }

    @Test
    fun `an empty label and no label are the same label`() {
        val threads = mutableListOf<CommentThread>()
        val empty = threads.arrive("", 1, 2)
        val omitted = threads.arrive(null, 3)
        val repeated = threads.arrive(null, 2)

        assertEquals(walkthroughsOf(empty), walkthroughsOf(omitted))
        assertNotEquals(walkthroughsOf(empty), walkthroughsOf(repeated))
    }

    @Test
    fun `replay counts an empty label and no label as one`() {
        val empty = step(1, null, "")
        val omitted = step(2, null)

        val replayed = Walkthrough.replay(listOf(empty, omitted))

        assertEquals(replayed[empty.id], replayed[omitted.id])
    }

    @Test
    fun `replay leaves steps that already belong to a walkthrough alone`() {
        val known = step(1, "w1", "A")
        val legacy = step(2, null, "A")
        val replayed = Walkthrough.replay(listOf(known, legacy))
        assertEquals(setOf(legacy.id), replayed.keys)
    }

    @Test
    fun `stable total is the highest step number in its own walkthrough, never a same-label neighbor's`() {
        val older = listOf(step(1, "old", "A"), step(2, "old", "A"), step(5, "old", "A"))
        val s1 = step(1, "new", "A").also { it.resolve(user) }
        val s2 = step(2, "new", "A")
        val s3 = step(3, "new", "A").also { it.resolve(user) }
        assertEquals(3, Walkthrough.stableTotal(older + listOf(s1, s2, s3), s2))
    }
}
