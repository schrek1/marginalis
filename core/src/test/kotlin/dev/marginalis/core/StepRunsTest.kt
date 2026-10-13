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
    fun `steps without a file never join a run`() {
        assertEquals(listOf(listOf(null), listOf(null), listOf("A")), runs(null, null, "A"))
    }

    @Test
    fun `no steps, no runs`() {
        assertEquals(emptyList(), runs())
    }
}
