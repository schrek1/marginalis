package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LeavingAStepTest {

    @Test
    fun `an unknown or missing choice folds the step you leave, as before the choice existed`() {
        assertEquals(LeavingAStep.FOLD, LeavingAStep.fromStored("COLLAPSE"))
        assertEquals(LeavingAStep.FOLD, LeavingAStep.fromStored(""))
    }

    @Test
    fun `every choice reads back as itself`() {
        LeavingAStep.entries.forEach { assertEquals(it, LeavingAStep.fromStored(it.stored)) }
    }
}
