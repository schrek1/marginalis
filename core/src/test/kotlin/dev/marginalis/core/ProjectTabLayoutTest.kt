package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTabLayoutTest {

    private val agent = Author.Agent("Claude", "claude-main")

    private val user = Author.User("Muhammad")

    private var tick = 0L

    private fun at() = Instant.ofEpochSecond(tick++)

    private fun project() = CommentThread(file = null, line = null, anchorText = null, createdAt = at())

    private fun project(id: String, createdAt: Long, activeAt: Long) =
        CommentThread(file = null, line = null, anchorText = null, id = id, createdAt = Instant.ofEpochSecond(createdAt))
            .also { it.restoreUpdatedAt(Instant.ofEpochSecond(activeAt)) }

    private fun resolved(id: String, createdAt: Long, activeAt: Long) =
        project(id, createdAt, activeAt).also {
            it.resolve(user)
            it.restoreUpdatedAt(Instant.ofEpochSecond(activeAt))
        }

    private var commentIds = 100

    private fun relayedFrom(url: String) = (commentIds++).toString().let { id ->
        Relayed(Relayed.Source.GITHUB, id, "$url#issuecomment-$id", "Mona", "octocat")
    }

    private fun relayed(id: String, pr: Int, activeAt: Long, url: String = "https://github.com/o/r/pull/$pr") =
        project(id, createdAt = activeAt, activeAt = activeAt).also {
            it.addMessage(Message(agent, "from GitHub", relayed = relayedFrom(url)))
            it.restoreUpdatedAt(Instant.ofEpochSecond(activeAt))
        }

    private fun keys(layout: ProjectTabLayout.Layout) = layout.active.map { it.key }

    @Test
    fun `only project-level threads appear — file and line threads never do`() {
        val aboutTheProject = project()
        val aboutAFile = CommentThread(file = "a.py", line = null, anchorText = null, createdAt = at())
        val onALine = CommentThread(file = "a.py", line = 3, anchorText = "x", createdAt = at())

        val layout = ProjectTabLayout.arrange(listOf(aboutAFile, aboutTheProject, onALine))

        assertEquals(listOf(aboutTheProject.id), keys(layout))
        assertEquals(emptyList(), layout.resolved)
    }

    @Test
    fun `the thread with the latest activity comes first, ties broken by newest creation, then id`() {
        val quiet = project("q", createdAt = 1, activeAt = 10)
        val busy = project("b", createdAt = 2, activeAt = 50)
        val olderOfTwins = project("o", createdAt = 3, activeAt = 40)
        val newerOfTwins = project("n", createdAt = 4, activeAt = 40)
        val sameAsNewerButLaterId = project("z", createdAt = 4, activeAt = 40)

        val layout = ProjectTabLayout.arrange(listOf(quiet, sameAsNewerButLaterId, olderOfTwins, busy, newerOfTwins))

        assertEquals(listOf("b", "n", "z", "o", "q"), keys(layout))
    }

    @Test
    fun `a new message moves its thread to the top`() {
        val first = project("first", createdAt = 1, activeAt = 1)
        val second = project("second", createdAt = 2, activeAt = 2)

        first.addMessage(Message(agent, "news"))

        assertEquals(listOf("first", "second"), keys(ProjectTabLayout.arrange(listOf(second, first))))
    }

    @Test
    fun `a thread holding an unsent reply keeps its previous place while others re-sort around it`() {
        val top = project("top", createdAt = 1, activeAt = 30)
        val drafting = project("drafting", createdAt = 2, activeAt = 20)
        val bottom = project("bottom", createdAt = 3, activeAt = 10)
        val previous = ProjectTabLayout.arrange(listOf(top, drafting, bottom)).active

        bottom.addMessage(Message(agent, "news"))
        drafting.addMessage(Message(agent, "even newer"))

        val layout = ProjectTabLayout.arrange(listOf(top, drafting, bottom), previous, holdsDraft = { it == "drafting" })

        assertEquals(listOf("bottom", "drafting", "top"), keys(layout))
    }

    @Test
    fun `a thread holding an unsent reply that gets resolved stays in the active list, not the fold`() {
        val drafting = project("drafting", createdAt = 1, activeAt = 20)
        val other = project("other", createdAt = 2, activeAt = 10)
        val previous = ProjectTabLayout.arrange(listOf(drafting, other)).active

        drafting.resolve(agent)

        val layout = ProjectTabLayout.arrange(listOf(drafting, other), previous, holdsDraft = { it == "drafting" })

        assertEquals(listOf("drafting", "other"), keys(layout))
        assertEquals(emptyList(), layout.resolved)
    }

    @Test
    fun `once the reply is sent or cleared, the thread sorts and folds normally again`() {
        val drafting = project("drafting", createdAt = 1, activeAt = 10)
        val other = project("other", createdAt = 2, activeAt = 20)
        val resolvedWhileDrafting = project("resolved", createdAt = 3, activeAt = 5)
        val previous = ProjectTabLayout.arrange(listOf(drafting, other, resolvedWhileDrafting)).active

        drafting.addMessage(Message(user, "sent"))
        resolvedWhileDrafting.resolve(user)

        val layout = ProjectTabLayout.arrange(listOf(drafting, other, resolvedWhileDrafting), previous, holdsDraft = { false })

        assertEquals(listOf("drafting", "other"), keys(layout))
        assertEquals(listOf(resolvedWhileDrafting), layout.resolved)
    }

    @Test
    fun `resolved threads fold into the resolved list, latest first`() {
        val later = resolved("later", createdAt = 1, activeAt = 30)
        val open = project("open", createdAt = 2, activeAt = 2)
        val earlier = resolved("earlier", createdAt = 3, activeAt = 10)

        val layout = ProjectTabLayout.arrange(listOf(earlier, open, later))

        assertEquals(listOf("open"), keys(layout))
        assertEquals(listOf(later, earlier), layout.resolved)
    }

    @Test
    fun `with grouping on, open relayed threads of one discussion form one conversation entry named by its discussion`() {
        val first = relayed("first", pr = 41, activeAt = 10)
        val second = relayed("second", pr = 41, activeAt = 20)

        val layout = ProjectTabLayout.arrange(listOf(first, second), groupRelayed = true)

        val conversation = layout.active.single() as ProjectTabLayout.Entry.Conversation
        assertEquals("PR #41", conversation.discussion)
        assertEquals("conversation:PR #41", conversation.key)
    }

    private fun conversation(layout: ProjectTabLayout.Layout, discussion: String) =
        layout.active.filterIsInstance<ProjectTabLayout.Entry.Conversation>().single { it.discussion == discussion }

    @Test
    fun `a conversation sorts by its most recent member and lists its members latest first`() {
        val older = relayed("older", pr = 41, activeAt = 10)
        val middle = project("middle", createdAt = 2, activeAt = 20)
        val newest = relayed("newest", pr = 41, activeAt = 30)

        val layout = ProjectTabLayout.arrange(listOf(older, middle, newest))

        assertEquals(listOf("conversation:PR #41", "middle"), keys(layout))
        assertEquals(listOf(newest, older), conversation(layout, "PR #41").threads)
    }

    @Test
    fun `a conversation counts its threads and its unread messages`() {
        val read = relayed("read", pr = 41, activeAt = 10).also { it.markReadByUser() }
        val twoUnread = relayed("twoUnread", pr = 41, activeAt = 20).also {
            it.addMessage(Message(agent, "a second relay", relayed = relayedFrom("https://github.com/o/r/pull/41")))
        }

        val group = conversation(ProjectTabLayout.arrange(listOf(read, twoUnread)), "PR #41")

        assertEquals(2, group.count)
        assertEquals(2, group.newCount)
    }

    @Test
    fun `relayed threads from two pull requests form two conversations`() {
        val layout = ProjectTabLayout.arrange(
            listOf(relayed("a", pr = 41, activeAt = 10), relayed("b", pr = 42, activeAt = 20), relayed("c", pr = 41, activeAt = 5)),
        )

        assertEquals(listOf("conversation:PR #42", "conversation:PR #41"), keys(layout))
        assertEquals(2, conversation(layout, "PR #41").count)
    }

    @Test
    fun `a relayed thread without a discussion stays a single thread`() {
        val commitComment = relayed("commit", pr = 0, activeAt = 10, url = "https://github.com/o/r/commit/abc123")

        assertEquals(listOf("commit"), keys(ProjectTabLayout.arrange(listOf(commitComment))))
    }

    @Test
    fun `with grouping off, every relayed thread is its own entry`() {
        val layout = ProjectTabLayout.arrange(
            listOf(relayed("a", pr = 41, activeAt = 10), relayed("b", pr = 41, activeAt = 20)),
            groupRelayed = false,
        )

        assertEquals(listOf("b", "a"), keys(layout))
    }

    @Test
    fun `a resolved relayed thread folds individually, never into a conversation`() {
        val open = relayed("open", pr = 41, activeAt = 10)
        val done = relayed("done", pr = 41, activeAt = 20).also {
            it.resolve(user)
            it.restoreUpdatedAt(Instant.ofEpochSecond(20))
        }

        val layout = ProjectTabLayout.arrange(listOf(open, done))

        assertEquals(listOf(open), conversation(layout, "PR #41").threads)
        assertEquals(listOf(done), layout.resolved)
    }

    @Test
    fun `a resolved thread in the fold that gains an unsent reply stays in the fold`() {
        val open = project("open", createdAt = 1, activeAt = 10)
        val done = resolved("done", createdAt = 2, activeAt = 20)
        val previous = ProjectTabLayout.arrange(listOf(open, done)).active

        val layout = ProjectTabLayout.arrange(listOf(open, done), previous, holdsDraft = { it == "done" })

        assertEquals(listOf("open"), keys(layout))
        assertEquals(listOf(done), layout.resolved)
    }

    @Test
    fun `a conversation member holding an unsent reply that gets resolved keeps its conversation in place`() {
        val top = project("top", createdAt = 1, activeAt = 30)
        val drafting = relayed("drafting", pr = 41, activeAt = 20)
        val bottom = project("bottom", createdAt = 3, activeAt = 10)
        val previous = ProjectTabLayout.arrange(listOf(top, drafting, bottom)).active

        drafting.resolve(user)
        bottom.addMessage(Message(agent, "news"))

        val layout = ProjectTabLayout.arrange(listOf(top, drafting, bottom), previous, holdsDraft = { it == "drafting" })

        assertEquals(listOf("bottom", "conversation:PR #41", "top"), keys(layout))
        assertEquals(listOf(drafting), conversation(layout, "PR #41").threads)
        assertEquals(emptyList(), layout.resolved)
    }
}
