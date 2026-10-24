package dev.marginalis.core

import dev.marginalis.core.ProjectTabLayout.Entry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HoldStillTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Muhammad")

    private var tick = 0L

    private fun at() = Instant.ofEpochSecond(tick++)

    private fun project(id: String) =
        CommentThread(file = null, line = null, anchorText = null, id = id, createdAt = at()).also { it.restoreUpdatedAt(at()) }

    private var commentIds = 100

    private fun relayed(id: String, pr: Int = 41) = project(id).also {
        val commentId = (commentIds++).toString()
        it.addMessage(
            Message(
                agent,
                "from GitHub",
                relayed = Relayed(Relayed.Source.GITHUB, commentId, "https://github.com/o/r/pull/$pr#issuecomment-$commentId", "Mona", "octocat"),
            ),
        )
    }

    private fun news(thread: CommentThread) = thread.addMessage(Message(agent, "news"))

    private fun HoldStill.look(vararg threads: CommentThread, inFront: Boolean = true) =
        update(ProjectTabLayout.arrange(threads.toList()), inFront)

    private fun lookingAt(vararg threads: CommentThread) = HoldStill().also { it.look(*threads) }

    private fun keys(view: HoldStill.View) = view.shown.active.map { it.key }

    @Test
    fun `while looking, the order holds even when activity would re-sort it`() {
        val (a, b, c) = listOf(project("a"), project("b"), project("c"))
        val hold = lookingAt(a, b, c)

        news(a)

        assertEquals(listOf("c", "b", "a"), keys(hold.look(a, b, c)))
    }

    @Test
    fun `an entry arriving while looking stays out of the list and raises the pending count`() {
        val (a, b) = listOf(project("a"), project("b"))

        val view = lookingAt(a, b).look(a, b, project("new"))

        assertEquals(listOf("b", "a"), keys(view))
        assertEquals(1, view.pending)
    }

    @Test
    fun `changes to entries already shown never raise the pending count`() {
        val (a, b) = listOf(project("a"), project("b"))
        val hold = lookingAt(a, b)

        news(a)

        assertEquals(0, hold.look(a, b).pending)
    }

    @Test
    fun `an entry resolved while looking keeps its place, marked resolved just now, and stays out of the fold`() {
        val (a, b, c) = listOf(project("a"), project("b"), project("c"))
        val hold = lookingAt(a, b, c)

        b.resolve(user)
        val view = hold.look(a, b, c)

        assertEquals(listOf("c", "b", "a"), keys(view))
        assertEquals(setOf("b"), view.dimmed)
        assertEquals(emptyList(), view.shown.resolved)
    }

    @Test
    fun `a deleted entry vanishes even while holding`() {
        val (a, b, c) = listOf(project("a"), project("b"), project("c"))

        val view = lookingAt(a, b, c).look(a, c)

        assertEquals(listOf("c", "a"), keys(view))
    }

    @Test
    fun `looking away and back adopts the live arrangement and folds what was resolved`() {
        val (a, b, c) = listOf(project("a"), project("b"), project("c"))
        val hold = lookingAt(a, b, c)
        a.resolve(user)
        b.resolve(user)
        val arrival = project("new")
        hold.look(a, b, c, arrival)

        hold.look(a, b, c, arrival, inFront = false)
        val back = hold.look(a, b, c, arrival)

        assertEquals(listOf("new", "c"), keys(back))
        assertEquals(setOf(b, a), back.shown.resolved.toSet())
        assertEquals(emptySet(), back.dimmed)
        assertEquals(0, back.pending)
    }

    @Test
    fun `releasing adopts the live arrangement and clears the pending count`() {
        val (a, b) = listOf(project("a"), project("b"))
        val hold = lookingAt(a, b)
        a.resolve(user)
        val arrival = project("new")
        hold.look(a, b, arrival)

        hold.release()
        val released = hold.look(a, b, arrival)

        assertEquals(listOf("new", "b"), keys(released))
        assertEquals(listOf(a), released.shown.resolved)
        assertEquals(emptySet(), released.dimmed)
        assertEquals(0, released.pending)
    }

    @Test
    fun `a conversation whose last open member is resolved while looking keeps its place and its member, marked resolved just now`() {
        val member = relayed("member")
        val other = project("other")
        val hold = lookingAt(member, other)

        member.resolve(user)
        val view = hold.look(member, other)

        assertEquals(listOf("conversation:PR #41", "other"), keys(view))
        assertEquals(listOf(member), view.shown.active.first().threads)
        assertEquals(setOf("member", "conversation:PR #41"), view.dimmed)
        assertEquals(emptyList(), view.shown.resolved)
    }

    @Test
    fun `turning grouping off while looking adopts the ungrouped arrangement and raises no pending count`() {
        val (first, second) = listOf(relayed("first"), relayed("second"))
        val hold = lookingAt(first, second)

        val view = hold.update(ProjectTabLayout.arrange(listOf(first, second), groupRelayed = false), inFront = true)

        assertEquals(listOf("second", "first"), keys(view))
        assertEquals(0, view.pending)
    }

    @Test
    fun `turning grouping on while looking adopts the grouped arrangement and raises no pending count`() {
        val (first, second) = listOf(relayed("first"), relayed("second"))
        val hold = HoldStill().also { it.update(ProjectTabLayout.arrange(listOf(first, second), groupRelayed = false), inFront = true) }

        val view = hold.look(first, second)

        assertEquals(listOf("conversation:PR #41"), keys(view))
        assertEquals(0, view.pending)
    }

    @Test
    fun `a member resolved while looking stays in its conversation, marked resolved just now`() {
        val (first, second) = listOf(relayed("first"), relayed("second"))
        val hold = lookingAt(first, second)

        first.resolve(user)
        val view = hold.look(first, second)

        assertEquals(listOf(second, first), (view.shown.active.single() as Entry.Conversation).threads)
        assertEquals(setOf("first"), view.dimmed)
        assertEquals(emptyList(), view.shown.resolved)
    }

    @Test
    fun `a conversation you expanded stays expanded when its last member is resolved while looking`() {
        val member = relayed("member")
        val hold = HoldStill()
        val expansion = ProjectTabExpansion()
        expansion.observe(hold.look(member).shown.active, expandOnYourMove = true)
        expansion.toggle("conversation:PR #41")

        member.resolve(user)
        expansion.observe(hold.look(member).shown.active, expandOnYourMove = true)

        assertTrue(expansion.isExpanded("conversation:PR #41"))
    }

    @Test
    fun `a thread reopened from the fold while looking shows at the top and never raises the pending count`() {
        val (a, b, folded) = listOf(project("a"), project("b"), project("folded"))
        folded.resolve(user)
        val hold = lookingAt(a, b, folded)

        folded.reopen()
        val view = hold.look(a, b, folded)

        assertEquals(listOf("folded", "b", "a"), keys(view))
        assertEquals(emptyList(), view.shown.resolved)
        assertEquals(0, view.pending)
    }

    @Test
    fun `a thread you start while looking shows at the top once admitted, and never raises the pending count`() {
        val (a, b) = listOf(project("a"), project("b"))
        val hold = lookingAt(a, b)
        val yours = project("yours")

        hold.admit(yours.id)
        val view = hold.look(a, b, yours)

        assertEquals(listOf("yours", "b", "a"), keys(view))
        assertEquals(0, view.pending)
    }

    @Test
    fun `an admitted thread keeps its place as later activity arrives`() {
        val (a, b) = listOf(project("a"), project("b"))
        val hold = lookingAt(a, b)
        val yours = project("yours")
        hold.admit(yours.id)
        hold.look(a, b, yours)

        news(a)

        assertEquals(listOf("yours", "b", "a"), keys(hold.look(a, b, yours)))
    }
}
