package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StepNumbersTest {

    @Test
    fun `a single step is named alone`() {
        assertEquals("step 5", StepNumbers.label(listOf(5)))
    }

    @Test
    fun `consecutive steps read as a range`() {
        assertEquals("steps 2–4", StepNumbers.label(listOf(2, 3, 4)))
    }

    @Test
    fun `two adjacent steps read as a range`() {
        assertEquals("steps 2–3", StepNumbers.label(listOf(2, 3)))
    }

    @Test
    fun `steps with a gap are listed apart`() {
        assertEquals("steps 2, 4", StepNumbers.label(listOf(2, 4)))
    }

    @Test
    fun `ranges and lone steps mix`() {
        assertEquals("steps 2–3, 6", StepNumbers.label(listOf(2, 3, 6)))
        assertEquals("steps 1, 3–5, 9–10", StepNumbers.label(listOf(1, 3, 4, 5, 9, 10)))
    }

    @Test
    fun `the numbers are read in order whatever order they come in`() {
        assertEquals("steps 2–3, 6", StepNumbers.label(listOf(6, 3, 2)))
    }

    @Test
    fun `a number given twice counts once`() {
        assertEquals("step 4", StepNumbers.label(listOf(4, 4)))
    }
}
