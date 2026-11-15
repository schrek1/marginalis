package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadListingTest {

    private val user = Author.User("Muhammad")
    private var tick = 0L
    private val clock: (Instant) -> String = { "t${it.epochSecond}" }

    private fun step(order: Int, walkthroughId: String, label: String? = null, createdAt: Instant = Instant.ofEpochSecond(tick++)) =
        CommentThread(
            file = "a.py", line = order, anchorText = "x",
            createdAt = createdAt, order = order, walkthrough = label, walkthroughId = walkthroughId,
        )

    private fun comment() = CommentThread("a.py", 1, "x", createdAt = Instant.ofEpochSecond(tick++))

    private fun CommentThread.resolved() = apply { resolve(user) }

    private fun CommentThread.orphaned() = apply { markOrphaned() }

    @Test
    fun `open lists the ordinary comments alone and each walkthrough lists its steps`() {
        val loose = comment()
        val one = step(1, "w")
        val two = step(2, "w")
        val listing = ThreadListing.of(listOf(two, loose, one))

        assertEquals(listOf(loose), listing.open)
        assertEquals(listOf(listOf(one, two)), listing.walkthroughs.map { it.steps })
    }

    @Test
    fun `resolved and orphaned steps stay in their walkthrough and only comments go to those sections`() {
        val resolvedComment = comment().resolved()
        val orphanedComment = comment().orphaned()
        val first = step(1, "w").resolved()
        val second = step(2, "w").orphaned()
        val third = step(3, "w")
        val listing = ThreadListing.of(listOf(resolvedComment, orphanedComment, third, second, first))

        assertEquals(listOf(listOf(first, second, third)), listing.walkthroughs.map { it.steps })
        assertEquals(listOf(resolvedComment), listing.resolved)
        assertEquals(listOf(orphanedComment), listing.orphaned)
    }

    @Test
    fun `a walkthrough whose every step is resolved is listed as resolved`() {
        val doneOne = step(1, "done", "a-tour").resolved()
        val doneTwo = step(2, "done", "a-tour").resolved()
        val open = step(1, "open", "b-tour")
        val listing = ThreadListing.of(listOf(open, doneTwo, doneOne))

        assertEquals(listOf("b-tour"), listing.walkthroughs.map { it.title })
        assertEquals(listOf(listOf(doneOne, doneTwo)), listing.resolvedWalkthroughs.map { it.steps })
        assertEquals(listOf("a-tour"), listing.resolvedWalkthroughs.map { it.title })
        assertEquals(emptyList(), listing.resolved)
    }

    @Test
    fun `a walkthrough with an orphaned step and nothing open stays active`() {
        val done = step(1, "w").resolved()
        val lost = step(2, "w").orphaned()
        val listing = ThreadListing.of(listOf(done, lost))

        assertEquals(listOf(listOf(done, lost)), listing.walkthroughs.map { it.steps })
        assertEquals(emptyList(), listing.resolvedWalkthroughs)
    }

    @Test
    fun `a walkthrough whose open step is filtered out is still active`() {
        val done = step(1, "w").resolved()
        val hidden = step(2, "w")
        val listing = ThreadListing.of(shown = listOf(done), all = listOf(done, hidden))

        assertEquals(listOf(listOf(done)), listing.walkthroughs.map { it.steps })
        assertEquals(emptyList(), listing.resolvedWalkthroughs)
    }

    @Test
    fun `reopening a step of a resolved walkthrough makes the whole walkthrough active again`() {
        val one = step(1, "w").resolved()
        val two = step(2, "w").resolved()
        two.reopen()
        val listing = ThreadListing.of(listOf(one, two))

        assertEquals(listOf(listOf(one, two)), listing.walkthroughs.map { it.steps })
        assertEquals(emptyList(), listing.resolvedWalkthroughs)
    }

    @Test
    fun `a group numbers its steps out of the whole walkthrough even when the rest is filtered out or finished`() {
        val one = step(1, "w").resolved()
        val two = step(2, "w").resolved()
        val three = step(3, "w").resolved()
        val listing = ThreadListing.of(shown = listOf(two), all = listOf(one, two, three))

        assertEquals(3, listing.resolvedWalkthroughs.single().total)
    }

    @Test
    fun `two walkthroughs under one label are two groups ordered by when they started`() {
        val later = step(1, "later", "tour", createdAt = Instant.ofEpochSecond(200))
        val earlier = step(1, "earlier", "tour", createdAt = Instant.ofEpochSecond(100))
        val listing = ThreadListing.of(listOf(later, earlier))

        assertEquals(listOf("earlier", "later"), listing.walkthroughs.map { it.walkthroughId })
    }

    @Test
    fun `walkthroughs are ordered by label, the unlabelled first`() {
        val labelled = step(1, "r2", "r2")
        val plain = step(1, "plain")
        val shop = step(1, "shop", "SHOP-42")
        val listing = ThreadListing.of(listOf(labelled, plain, shop), startTime = clock)

        assertEquals(listOf("Walkthrough", "SHOP-42", "r2"), listing.walkthroughs.map { it.title })
    }

    @Test
    fun `groups that would read the same gain their start time and the rest stay plain`() {
        val first = step(1, "first", createdAt = Instant.ofEpochSecond(100))
        val firstLater = step(2, "first", createdAt = Instant.ofEpochSecond(150))
        val second = step(1, "second", createdAt = Instant.ofEpochSecond(300))
        val tour = step(1, "tour", "tour")
        val listing = ThreadListing.of(listOf(second, firstLater, first, tour), startTime = clock)

        assertEquals(listOf("Walkthrough · t100", "Walkthrough · t300", "tour"), listing.walkthroughs.map { it.title })
    }

    @Test
    fun `a walkthrough starts when its earliest step was created, shown or not`() {
        val hidden = step(1, "w", createdAt = Instant.ofEpochSecond(50)).resolved()
        val shown = step(2, "w", createdAt = Instant.ofEpochSecond(400))
        val other = step(1, "v", createdAt = Instant.ofEpochSecond(500))
        val listing = ThreadListing.of(shown = listOf(shown, other), all = listOf(hidden, shown, other), startTime = clock)

        assertEquals(listOf("Walkthrough · t50", "Walkthrough · t500"), listing.walkthroughs.map { it.title })
    }

    @Test
    fun `namesakes are told apart whatever state their steps are in`() {
        val openStep = step(1, "w", createdAt = Instant.ofEpochSecond(100))
        val resolvedStep = step(2, "v", createdAt = Instant.ofEpochSecond(200)).resolved()
        val listing = ThreadListing.of(listOf(openStep, resolvedStep), startTime = clock)

        assertEquals(listOf("Walkthrough · t100"), listing.walkthroughs.map { it.title })
        assertEquals(listOf("Walkthrough · t200"), listing.resolvedWalkthroughs.map { it.title })
    }

    @Test
    fun `a walkthrough keeps its told-apart title while its namesake is filtered out`() {
        val shown = step(1, "shown", "tour", createdAt = Instant.ofEpochSecond(100))
        val hidden = step(1, "hidden", "tour", createdAt = Instant.ofEpochSecond(300))
        val all = listOf(shown, hidden)
        val listing = ThreadListing.of(shown = listOf(shown), all = all, startTime = clock)

        assertEquals(listOf("tour · t100"), listing.walkthroughs.map { it.title })
        assertEquals("tour · t100", ThreadListing.titleOf(all, shown, clock))
    }

    @Test
    fun `a walkthrough with finished steps has one title`() {
        val open = step(2, "w", "tour", createdAt = Instant.ofEpochSecond(100))
        val done = step(1, "w", "tour", createdAt = Instant.ofEpochSecond(90)).resolved()
        val namesake = step(1, "other", "tour", createdAt = Instant.ofEpochSecond(300)).resolved()
        val listing = ThreadListing.of(listOf(open, done, namesake), startTime = clock)

        assertEquals(listOf("tour · t90"), listing.walkthroughs.map { it.title })
        assertEquals(listOf(listOf(done, open)), listing.walkthroughs.map { it.steps })
        assertEquals(listOf("tour · t300"), listing.resolvedWalkthroughs.map { it.title })
    }

    @Test
    fun `groups whose start times read the same are numbered in the order they started`() {
        val first = step(1, "first", createdAt = Instant.ofEpochSecond(100))
        val second = step(1, "second", createdAt = Instant.ofEpochSecond(110))
        val other = step(1, "other", createdAt = Instant.ofEpochSecond(5000))
        val listing = ThreadListing.of(listOf(second, other, first), startTime = { if (it.epochSecond < 1000) "14:40" else "15:55" })

        assertEquals(listOf("Walkthrough · 14:40 (1)", "Walkthrough · 14:40 (2)", "Walkthrough · 15:55"), listing.walkthroughs.map { it.title })
    }

    @Test
    fun `long labels are told apart by their shortened titles and keep the whole time suffix`() {
        val base = "x".repeat(45)
        val first = step(1, "first", base + "A", createdAt = Instant.ofEpochSecond(100))
        val second = step(1, "second", base + "B", createdAt = Instant.ofEpochSecond(300))
        val listing = ThreadListing.of(listOf(first, second), startTime = clock)

        assertEquals(listOf("${"x".repeat(37)}... · t100", "${"x".repeat(37)}... · t300"), listing.walkthroughs.map { it.title })
    }

    @Test
    fun `a thread's walkthrough title is the one its section shows`() {
        val first = step(1, "first", "tour", createdAt = Instant.ofEpochSecond(100))
        val second = step(1, "second", "tour", createdAt = Instant.ofEpochSecond(300))
        val alone = step(1, "alone", "solo")
        val resolvedTwin = step(2, "second", "tour", createdAt = Instant.ofEpochSecond(310)).resolved()
        val all = listOf(first, second, alone, resolvedTwin)

        assertEquals("tour · t300", ThreadListing.titleOf(all, second, clock))
        assertEquals("solo", ThreadListing.titleOf(all, alone, clock))
        assertEquals("tour · t300", ThreadListing.titleOf(all, resolvedTwin, clock))
    }
}
