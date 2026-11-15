package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalkthroughTest {

    private val user = Author.User("Muhammad")
    private var tick = 0L

    private fun thread(
        file: String,
        line: Int = 1,
        order: Int? = null,
        walkthrough: String? = null,
        createdAt: Instant = Instant.ofEpochSecond(tick++),
    ) = CommentThread(
        file = file, line = line, anchorText = "x",
        createdAt = createdAt, order = order, walkthrough = walkthrough,
        walkthroughId = order?.let { walkthrough ?: "unlabelled" },
    )

    @Test
    fun `an ordered step walks its own walkthrough in step order, never a neighbor's`() {
        val a2 = thread("b.py", order = 2, walkthrough = "A")
        val a1 = thread("a.py", order = 1, walkthrough = "A")
        val b1 = thread("a.py", order = 1, walkthrough = "B")
        val plain = thread("c.py")
        val (walk, position) = Walkthrough.walkFrom(listOf(a2, a1, b1, plain), a2)
        assertEquals(listOf(a1, a2), walk)
        assertEquals(1, position)
    }

    @Test
    fun `steps sort by number wherever they sit, a tie going to the earlier step`() {
        val inFolder = thread("src/deep/x.py", order = 5)
        val inDocs = thread("docs/notes.md", order = 7)
        val earlierTwo = thread("z.py", order = 2)
        val laterTwo = thread("a.py", order = 2)
        val first = thread("z.py", order = 1)

        val walked = listOf(inFolder, inDocs, laterTwo, earlierTwo, first).sortedWith(Walkthrough.byStepNumber)

        assertEquals(listOf(first, earlierTwo, laterTwo, inFolder, inDocs), walked)
    }

    @Test
    fun `an ordinary comment walks only the open ordinary comments, passing every step by`() {
        val later = thread("src/b.py")
        val step = thread("src/a.py", order = 1, walkthrough = "tour")
        val unlabelledStep = thread("readme.md", order = 1)
        val earlier = thread("src/a.py", line = 3)
        val (walk, position) = Walkthrough.walkFrom(listOf(later, step, unlabelledStep, earlier), later)
        assertEquals(listOf(earlier, later), walk)
        assertEquals(1, position)
    }

    @Test
    fun `an ordinary comment walks the open ordinary comments in directory-tree order`() {
        val deep = thread("src/util/a.py", line = 5)
        val shallow = thread("readme.md")
        val sibling = thread("src/main.py", line = 9)
        val resolved = thread("src/zzz.py").also { it.resolve(user) }
        val (walk, _) = Walkthrough.walkFrom(listOf(shallow, sibling, deep, resolved), shallow)
        assertEquals(listOf(deep, sibling, shallow), walk)
    }

    @Test
    fun `a file-level thread leads the walk through its file`() {
        val line = thread("src/a.py", line = 12)
        val whole = CommentThread("src/a.py", line = null, anchorText = null, createdAt = Instant.ofEpochSecond(tick++))
        val (walk, _) = Walkthrough.walkFrom(listOf(line, whole), line)
        assertEquals(listOf(whole, line), walk)
    }

    @Test
    fun `a project-level comment leads the whole walk`() {
        val line = thread("src/a.py", line = 12)
        val whole = CommentThread("src/a.py", line = null, anchorText = null, createdAt = Instant.ofEpochSecond(tick++))
        val project = CommentThread(file = null, line = null, anchorText = null, createdAt = Instant.ofEpochSecond(tick++))
        val (walk, _) = Walkthrough.walkFrom(listOf(line, whole, project), line)
        assertEquals(listOf(project, whole, line), walk)
    }

    @Test
    fun `a resolved thread is not a member of the walk`() {
        val open = thread("a.py")
        val resolved = thread("b.py").also { it.resolve(user) }
        val (walk, position) = Walkthrough.walkFrom(listOf(open, resolved), resolved)
        assertEquals(listOf(open), walk)
        assertNull(position)
    }

    @Test
    fun `stable total is null off the walkthrough or once nothing is open`() {
        val plain = thread("a.py")
        assertNull(Walkthrough.stableTotal(listOf(plain), plain))
        val concluded = thread("a.py", order = 1).also { it.resolve(user) }
        assertNull(Walkthrough.stableTotal(listOf(concluded), concluded))
    }

    @Test
    fun `trie rendering and pathOrder agree on the file sequence`() {
        val files = listOf(
            "readme.md", "src/util/deep/x.py", "src/a.py", "build.gradle.kts",
            "src/util/b.py", "docs/guide.md", "src/z.py",
        )
        val trie = PathTrie().apply { files.map { thread(it) }.forEach(::insert) }
        assertEquals(
            files.sortedWith { a, b -> PathTrie.pathOrder(a, b) },
            flatten(trie, ""),
        )
    }

    private fun flatten(trie: PathTrie, prefix: String): List<String> =
        trie.dirs.flatMap { (name, child) -> flatten(child, "$prefix$name/") } +
            trie.files.keys.map { "$prefix$it" }

    private fun step(walkthroughId: String, order: Int, label: String? = null, at: Long) = CommentThread(
        file = "a.py", line = order, anchorText = "x", createdAt = Instant.ofEpochSecond(at),
        order = order, walkthrough = label, walkthroughId = walkthroughId,
    )

    @Test
    fun `an unnamed walkthrough is titled Walkthrough and a named one by its name`() {
        assertEquals("Walkthrough", Walkthrough.title(null))
        assertEquals("Walkthrough", Walkthrough.title(""))
        assertEquals("Walkthrough", Walkthrough.title("   "))
        assertEquals("How the split works", Walkthrough.title("How the split works"))
        assertEquals("${"y".repeat(37)}...", Walkthrough.title("y".repeat(41)))
        assertEquals("y".repeat(40), Walkthrough.title("y".repeat(40)))
    }

    @Test
    fun `walkthroughs take colour slots in the order they started, wrapping around the palette`() {
        val third = step("c", 1, at = 30)
        val first = step("a", 2, at = 10)
        val firstLater = step("a", 1, at = 40)
        val second = step("b", 1, at = 20)
        val all = listOf(third, firstLater, second, first)

        assertEquals(0, Walkthrough.colourSlot(all, listOf(firstLater), slots = 2))
        assertEquals(1, Walkthrough.colourSlot(all, listOf(second), slots = 2))
        assertEquals(0, Walkthrough.colourSlot(all, listOf(third), slots = 2))
    }

    @Test
    fun `a resolved walkthrough keeps its place so later colours do not shift`() {
        val done = step("a", 1, at = 10).also { it.resolve(user) }
        val current = step("b", 1, at = 20)

        assertEquals(1, Walkthrough.colourSlot(listOf(done, current), listOf(current), slots = 4))
    }

    @Test
    fun `only steps of a single walkthrough have a colour`() {
        val a = step("a", 1, at = 10)
        val alsoA = step("a", 2, at = 11)
        val b = step("b", 1, at = 20)
        val plain = thread("a.py")
        val all = listOf(a, alsoA, b, plain)

        assertEquals(0, Walkthrough.colourSlot(all, listOf(a, alsoA), slots = 4))
        assertNull(Walkthrough.colourSlot(all, listOf(a, b), slots = 4))
        assertNull(Walkthrough.colourSlot(all, listOf(a, plain), slots = 4))
        assertNull(Walkthrough.colourSlot(all, listOf(plain), slots = 4))
    }
}
