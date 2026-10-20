package dev.marginalis.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenedTest {

    private var tick = 100L

    private fun thread(file: String? = "src/a.kt", line: Int? = 4, createdAt: Instant = Instant.ofEpochSecond(tick++)) =
        CommentThread(file = file, line = line, anchorText = line?.let { "x" }, createdAt = createdAt)

    private fun onLine(line: Int, vararg threads: CommentThread, lineOf: (CommentThread) -> Int? = { it.line }) =
        Opened.onLine("src/a.kt", line, threads.toList(), lineOf)

    @Test
    fun `the thread on the caret's line is the one opened`() {
        val here = thread(line = 4)
        val elsewhere = thread(line = 9)
        assertEquals(Opened.MarginThread(here.id), onLine(4, elsewhere, here))
    }

    @Test
    fun `a line without a thread opens its file`() {
        assertEquals(Opened.File("src/a.kt"), onLine(5, thread(line = 4)))
    }

    @Test
    fun `of two threads on one line the older is opened, as the gutter and the tree list it first`() {
        val newer = thread(createdAt = Instant.ofEpochSecond(50))
        val older = thread(createdAt = Instant.ofEpochSecond(10))
        assertEquals(Opened.MarginThread(older.id), onLine(4, newer, older))
    }

    @Test
    fun `a resolved thread is no longer on its line`() {
        val resolved = thread(createdAt = Instant.ofEpochSecond(1)).apply { resolve(Author.User("Muhammad")) }
        val open = thread(createdAt = Instant.ofEpochSecond(2))
        assertEquals(Opened.MarginThread(open.id), onLine(4, resolved, open))
        assertEquals(Opened.File("src/a.kt"), onLine(4, resolved))
    }

    @Test
    fun `an orphan has no line to be on`() {
        val orphan = thread().apply { markOrphaned() }
        assertEquals(Opened.File("src/a.kt"), onLine(4, orphan))
    }

    @Test
    fun `a thread about the whole file is on no line, not even the first`() {
        assertEquals(Opened.File("src/a.kt"), onLine(0, thread(line = null)) { 0 })
    }

    @Test
    fun `a thread in another file is not on this file's line`() {
        assertEquals(Opened.File("src/a.kt"), onLine(4, thread(file = "src/b.kt")))
    }

    @Test
    fun `the line comes from where the editor shows the thread, not where the file has it`() {
        val shifted = thread(line = 4)
        assertEquals(Opened.MarginThread(shifted.id), onLine(7, shifted) { 7 })
        assertEquals(Opened.File("src/a.kt"), onLine(4, shifted) { 7 })
        assertEquals(Opened.File("src/a.kt"), onLine(4, shifted) { null })
    }

    @Test
    fun `a thread is listed while the margin holds it, whatever its status`() {
        val resolved = thread().apply { resolve(Author.User("Muhammad")) }
        assertTrue(Opened.MarginThread(resolved.id).isListedIn(listOf(resolved)))
        assertFalse(Opened.MarginThread("gone").isListedIn(listOf(resolved)))
    }

    @Test
    fun `a file is listed while any thread is about it`() {
        val resolved = thread(line = null).apply { resolve(Author.User("Muhammad")) }
        assertTrue(Opened.File("src/a.kt").isListedIn(listOf(resolved)))
        assertFalse(Opened.File("src/b.kt").isListedIn(listOf(resolved)))
    }

    @Test
    fun `the project's threads are listed once there is one`() {
        assertFalse(Opened.ProjectThreads.isListedIn(listOf(thread())))
        assertTrue(Opened.ProjectThreads.isListedIn(listOf(thread(file = null, line = null))))
    }
}
