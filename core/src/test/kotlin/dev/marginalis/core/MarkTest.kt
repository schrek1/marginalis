package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkTest {

    private fun onLine(intent: Intent? = null) = CommentThread(file = "a.py", line = 1, anchorText = "x", intent = intent)
    private fun onFile(intent: Intent? = null) = CommentThread(file = "a.py", line = null, anchorText = null, intent = intent)
    private fun onProject(intent: Intent? = null) = CommentThread(file = null, line = null, anchorText = null, intent = intent)
    private fun stepOnLine(intent: Intent? = null) = CommentThread(file = "a.py", line = 1, anchorText = "x", intent = intent, order = 1, walkthroughId = "w")
    private fun stepOnFile() = CommentThread(file = "a.py", line = null, anchorText = null, order = 1, walkthroughId = "w")
    private fun stepOnProject() = CommentThread(file = null, line = null, anchorText = null, order = 1, walkthroughId = "w")

    @Test
    fun `each width wears its own subject`() {
        assertEquals(Mark(MarkSubject.LINE, null), Mark.of(listOf(onLine())))
        assertEquals(Mark(MarkSubject.FILE, null), Mark.of(listOf(onFile())))
        assertEquals(Mark(MarkSubject.PROJECT, null), Mark.of(listOf(onProject())))
    }

    @Test
    fun `an agreed intent colors the mark`() {
        assertEquals(Mark(MarkSubject.LINE, Intent.FINDING), Mark.of(listOf(onLine(Intent.FINDING), onLine(Intent.FINDING))))
        assertEquals(Mark(MarkSubject.FILE, Intent.GUIDANCE), Mark.of(listOf(onFile(Intent.GUIDANCE))))
        assertEquals(Mark(MarkSubject.PROJECT, Intent.QUESTION), Mark.of(listOf(onProject(Intent.QUESTION))))
        assertEquals(Mark(MarkSubject.LINE, Intent.FYI), Mark.of(listOf(onLine(Intent.FYI))))
    }

    @Test
    fun `disagreeing intents fall back to the ordinary mark`() {
        assertEquals(Mark(MarkSubject.LINE, null), Mark.of(listOf(onLine(Intent.FINDING), onLine(Intent.QUESTION))))
        assertEquals(Mark(MarkSubject.LINE, null), Mark.of(listOf(onLine(Intent.FINDING), onLine())))
    }

    @Test
    fun `the narrowest subject present names a mixed set`() {
        assertEquals(MarkSubject.LINE, Mark.of(listOf(onFile(), onLine())).subject)
        assertEquals(MarkSubject.FILE, Mark.of(listOf(onProject(), onFile())).subject)
    }

    @Test
    fun `every subject and intent pairing is a distinct mark`() {
        val all = Mark.all()
        assertEquals(20, all.size)
        assertEquals(20, all.toSet().size)
    }

    @Test
    fun `a step on a line wears the step mark in its intent`() {
        assertEquals(Mark(MarkSubject.LINE, Intent.QUESTION, step = true), Mark.of(listOf(stepOnLine(Intent.QUESTION))))
        assertEquals(Mark(MarkSubject.LINE, null, step = true), Mark.of(listOf(stepOnLine(), stepOnLine())))
    }

    @Test
    fun `a line shared by a step and a comment wears the comment mark`() {
        assertEquals(Mark(MarkSubject.LINE, null), Mark.of(listOf(stepOnLine(), onLine())))
    }

    @Test
    fun `file and project steps keep their subjects ordinary marks`() {
        assertEquals(Mark(MarkSubject.FILE, null), Mark.of(listOf(stepOnFile())))
        assertEquals(Mark(MarkSubject.PROJECT, null), Mark.of(listOf(stepOnProject())))
    }

    @Test
    fun `every intent has a step mark on a line and nowhere else`() {
        val steps = Mark.all().filter { it.step }
        assertEquals(setOf(MarkSubject.LINE), steps.map { it.subject }.toSet())
        assertEquals((listOf(null) + Intent.entries).toSet(), steps.map { it.intent }.toSet())
    }
}
