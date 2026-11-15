package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WalkthroughLayoutTest {

    @Test
    fun `a stored layout reads back as itself`() {
        for (layout in WalkthroughLayout.entries) {
            assertEquals(layout, WalkthroughLayout.fromStored(layout.name))
        }
    }

    @Test
    fun `nothing stored, or an unknown value, reads as the step-number list`() {
        assertEquals(WalkthroughLayout.BY_STEP_NUMBER, WalkthroughLayout.fromStored(null))
        assertEquals(WalkthroughLayout.BY_STEP_NUMBER, WalkthroughLayout.fromStored("BY_COLOUR"))
    }

    @Test
    fun `each layout is offered under its own name`() {
        assertEquals(listOf("By Step Number", "By Folder", "By File"), WalkthroughLayout.entries.map { it.title })
    }
}
