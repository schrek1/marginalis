package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StepRunsTest {

    private fun runs(vararg files: String?): List<List<String?>> = StepRuns.of(files.toList()) { it }

    @Test
    fun `consecutive steps in one file form a run`() {
        assertEquals(listOf(listOf("A", "A"), listOf("B")), runs("A", "A", "B"))
    }

    @Test
    fun `a file the walk comes back to starts a new run`() {
        assertEquals(listOf(listOf("A"), listOf("B", "B"), listOf("A")), runs("A", "B", "B", "A"))
    }

    @Test
    fun `consecutive steps without a file form a run`() {
        assertEquals(listOf(listOf(null, null, null), listOf("A")), runs(null, null, null, "A"))
    }

    @Test
    fun `steps without a file after a file's steps start a new run`() {
        assertEquals(listOf(listOf(null, null), listOf("A"), listOf(null, null)), runs(null, null, "A", null, null))
    }

    @Test
    fun `a lone step without a file stays a run of its own`() {
        assertEquals(listOf(listOf("A"), listOf(null), listOf("B")), runs("A", null, "B"))
    }

    @Test
    fun `no steps, no runs`() {
        assertEquals(emptyList(), runs())
    }
}
