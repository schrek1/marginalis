package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ProjectTabTallyTest {

    private val agent = Author.Agent("Claude", "claude-main")
    private val user = Author.User("Muhammad")

    private fun project(lastWord: Author, intent: Intent? = null) =
        CommentThread(file = null, line = null, anchorText = null, intent = intent).also { it.addMessage(Message(lastWord, "…")) }

    private fun relay() = Message(
        agent,
        "from GitHub",
        relayed = Relayed(Relayed.Source.GITHUB, "7", "https://github.com/o/r/pull/41#issuecomment-7", "Mona", "octocat"),
    )

    private fun inFile(file: String, line: Int? = null, lastWord: Author = agent) =
        CommentThread(file = file, line = line, anchorText = line?.let { "x" }).also { it.addMessage(Message(lastWord, "…")) }

    private fun waiting(you: Int, agent: Int) =
        List(you) { project(this.agent) } + List(agent) { project(user) }

    @Test
    fun `counts open, your move and agent's move over open project threads only`() {
        val tally = ProjectTabTally.of(
            listOf(
                project(agent),
                project(agent),
                project(user),
                CommentThread(file = "a.py", line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) },
            ),
        )

        assertEquals(3, tally.open)
        assertEquals(TurnTally(user = 2, agent = 1), tally.turns)
    }

    @Test
    fun `total new sums unread messages across open project threads, conversation members included`() {
        val twoNew = project(agent).also { it.addMessage(Message(agent, "and more")) }
        val member = CommentThread(file = null, line = null, anchorText = null).also { it.addMessage(relay()) }
        val allRead = project(agent).also { it.markReadByUser() }

        assertEquals(3, ProjectTabTally.of(listOf(twoNew, member, allRead)).newCount)
    }

    @Test
    fun `resolved threads count toward none of the header numbers`() {
        val resolved = project(agent).also { it.resolve(user) }

        assertEquals(ProjectTabTally(open = 0, turns = TurnTally(user = 0, agent = 0), newCount = 0), ProjectTabTally.of(listOf(resolved)))
    }

    @Test
    fun `the title shows both turn signals when both sides wait`() {
        assertEquals("✉ 2 · ✈ 1", ProjectTabTally.of(waiting(you = 2, agent = 1)).title)
    }

    @Test
    fun `the title hides a part that is zero`() {
        assertEquals("✉ 2", ProjectTabTally.of(waiting(you = 2, agent = 0)).title)
        assertEquals("✈ 1", ProjectTabTally.of(waiting(you = 0, agent = 1)).title)
    }

    @Test
    fun `the title is Margin when nothing waits`() {
        assertEquals("Margin", ProjectTabTally.of(emptyList()).title)
    }

    @Test
    fun `an unread fyi counts as your move and a read fyi counts as nothing`() {
        val unread = project(agent, Intent.FYI)
        val read = project(agent, Intent.FYI).also { it.markReadByUser() }

        val tally = ProjectTabTally.of(listOf(unread, read))

        assertEquals(TurnTally(user = 1, agent = 0), tally.turns)
        assertEquals("✉ 1", tally.title)
    }

    @Test
    fun `file and line threads never change the title`() {
        val inAFile = CommentThread(file = "a.py", line = null, anchorText = null).also { it.addMessage(Message(agent, "…")) }
        val onALine = CommentThread(file = "a.py", line = 2, anchorText = "x").also { it.addMessage(Message(user, "…")) }

        assertEquals("Margin", ProjectTabTally.of(listOf(inAFile, onALine)).title)
    }

    @Test
    fun `elsewhere counts open file and line threads awaiting you`() {
        val elsewhere = Elsewhere.of(listOf(inFile("a.py"), inFile("b.py", line = 4), project(agent)))

        assertEquals(2, elsewhere?.count)
    }

    @Test
    fun `elsewhere points at the first of them in tool-window order`() {
        val deepLine = inFile("src/a.py", line = 9)
        val readme = inFile("readme.md")
        val wholeFile = inFile("src/a.py")

        assertSame(wholeFile, Elsewhere.of(listOf(readme, deepLine, wholeFile))?.first)
    }

    @Test
    fun `elsewhere ignores project threads, the agent's move and resolved threads`() {
        val elsewhere = Elsewhere.of(
            listOf(
                project(agent),
                inFile("a.py", lastWord = user),
                inFile("b.py").also { it.resolve(user) },
                inFile("c.py", line = 1),
            ),
        )

        assertEquals(1, elsewhere?.count)
        assertEquals("c.py", elsewhere?.first?.file)
    }

    @Test
    fun `elsewhere is absent when nothing elsewhere awaits you`() {
        assertNull(Elsewhere.of(listOf(project(agent), inFile("a.py", lastWord = user))))
    }
}
