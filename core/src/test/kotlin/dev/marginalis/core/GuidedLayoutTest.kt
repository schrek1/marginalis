package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class GuidedLayoutTest {

    @Test
    fun `a stored layout reads back as itself`() {
        for (layout in GuidedLayout.entries) {
            assertEquals(layout, GuidedLayout.fromStored(layout.stored))
        }
    }

    @Test
    fun `nothing stored, or an unknown value, reads as the step-number list`() {
        assertEquals(GuidedLayout.BY_STEP_NUMBER, GuidedLayout.fromStored(null))
        assertEquals(GuidedLayout.BY_STEP_NUMBER, GuidedLayout.fromStored("BY_COLOUR"))
    }
}
