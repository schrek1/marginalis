package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResumingAWalkthroughTest {

    private val user = Author.User("Muhammad")
    private var tick = 0L

    private fun walkthrough(id: String, label: String?, vararg orders: Int): List<CommentThread> =
        orders.map { order ->
            CommentThread(
                file = "a.py", line = order, anchorText = "x",
                createdAt = Instant.ofEpochSecond(tick++), order = order, walkthrough = label, walkthroughId = id,
            )
        }

    private fun comment() = CommentThread("a.py", 1, "x", createdAt = Instant.ofEpochSecond(tick++))

    private fun List<CommentThread>.step(order: Int) = single { it.order == order }

    private fun Arrows.orders() = listOf(first, previous, next, last).map { it?.order }

    @Test
    fun `with nothing remembered the arrows start at the first open step of the first guided walkthrough`() {
        val later = walkthrough("b", "b-tour", 1, 2, 3)
        val first = walkthrough("a", "a-tour", 1, 2, 3, 4)
        first.step(1).resolve(user)

        val arrows = Walkthrough.arrowsFrom(later + comment() + first, null)

        assertEquals(first.drop(1), arrows.walk)
        assertEquals(listOf(2, null, 2, 4), arrows.orders())
        assertEquals(first.step(2), arrows.next)
    }

    @Test
    fun `a remembered step still open walks on from its place`() {
        val tour = walkthrough("a", "tour", 1, 2, 3)

        val arrows = Walkthrough.arrowsFrom(tour, StepMark.of(tour.step(2)))

        assertEquals(listOf(1, 1, 3, 3), arrows.orders())
    }

    @Test
    fun `at either end of the walk only the arrows leading back are offered`() {
        val tour = walkthrough("a", "tour", 1, 2, 3)

        assertEquals(listOf(null, null, 2, 3), Walkthrough.arrowsFrom(tour, StepMark.of(tour.step(1))).orders())
        assertEquals(listOf(1, 2, null, null), Walkthrough.arrowsFrom(tour, StepMark.of(tour.step(3))).orders())
    }

    @Test
    fun `a remembered step that left the walk continues by its step number`() {
        val tour = walkthrough("a", "tour", 1, 2, 3, 4, 5)
        val mark = StepMark.of(tour.step(3))
        tour.step(3).resolve(user)

        val arrows = Walkthrough.arrowsFrom(tour, mark)

        assertEquals(listOf(1, 2, 4, 5), arrows.orders())
        assertEquals(tour.step(4), arrows.next)
    }

    @Test
    fun `a remembered walkthrough is walked alone, never with another under the same label`() {
        val older = walkthrough("old", "tour", 1, 2, 3)
        val newer = walkthrough("new", "tour", 1, 2)

        val arrows = Walkthrough.arrowsFrom(older + newer, StepMark.of(newer.step(1)))

        assertEquals(newer, arrows.walk)
        assertEquals(listOf(null, null, 2, 2), arrows.orders())
    }

    @Test
    fun `a remembered walkthrough with nothing left open gives way to the first guided walkthrough`() {
        val finished = walkthrough("done", "a-tour", 1, 2)
        val other = walkthrough("b", "b-tour", 1, 2)
        val mark = StepMark.of(finished.step(2))
        finished.forEach { it.resolve(user) }

        val arrows = Walkthrough.arrowsFrom(finished + other, mark)

        assertEquals(other, arrows.walk)
        assertEquals(listOf(1, null, 1, 2), arrows.orders())
    }

    @Test
    fun `a selected step walks its own walkthrough and gives no arrow once that walkthrough is finished`() {
        val finished = walkthrough("done", "a-tour", 1, 2)
        val other = walkthrough("b", "b-tour", 1, 2)
        finished.forEach { it.resolve(user) }

        val arrows = Walkthrough.arrowsOn(finished + other, StepMark.of(finished.step(1))!!)

        assertEquals(emptyList(), arrows.walk)
        assertEquals(listOf(null, null, null, null), arrows.orders())
    }

    @Test
    fun `a selected resolved step walks the open steps of its walkthrough from its number`() {
        val tour = walkthrough("a", "tour", 1, 2, 3)
        tour.step(2).resolve(user)

        val arrows = Walkthrough.arrowsOn(tour, StepMark.of(tour.step(2))!!)

        assertEquals(listOf(1, 1, 3, 3), arrows.orders())
    }

    @Test
    fun `with no open step anywhere no arrow leads anywhere`() {
        val tour = walkthrough("a", "tour", 1)
        tour.single().resolve(user)

        val arrows = Walkthrough.arrowsFrom(tour + comment(), StepMark.of(tour.single()))

        assertEquals(emptyList(), arrows.walk)
        assertEquals(listOf(null, null, null, null), arrows.orders())
    }

    @Test
    fun `only a step leaves a mark`() {
        assertNull(StepMark.of(comment()))
    }

    @Test
    fun `along a list of comments the arrows move from the one selected`() {
        val comments = List(3) { comment() }

        val arrows = Arrows.along(comments, 0)

        assertEquals(listOf(null, null, comments[1], comments[2]), listOf(arrows.first, arrows.previous, arrows.next, arrows.last))
    }
}
