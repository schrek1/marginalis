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
    fun `an unordered thread walks every open thread in directory-tree order`() {
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
    fun `a project-level step leads the whole walk`() {
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
    fun `stable total drops finished walkthroughs but keeps mid-walk resolutions counted`() {
        val old1 = thread("a.py", order = 1).also { it.resolve(user) }
        val old2 = thread("a.py", order = 2).also { it.resolve(user) }
        val born = Instant.ofEpochSecond(100)
        val s1 = thread("a.py", order = 1, createdAt = born).also { it.resolve(user) }
        val s2 = thread("b.py", order = 2, createdAt = born)
        val s3 = thread("c.py", order = 3, createdAt = born)
        assertEquals(3, Walkthrough.stableTotal(listOf(old1, old2, s1, s2, s3), s2))
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
}
