package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MarginTabShowsTest {

    @Test
    fun `an unknown or missing choice keeps the tab open, as before the choice existed`() {
        assertEquals(MarginTabShows.ALWAYS, MarginTabShows.fromStored("SOMETIMES"))
        assertEquals(MarginTabShows.ALWAYS, MarginTabShows.fromStored(""))
        MarginTabShows.entries.forEach { assertEquals(it, MarginTabShows.fromStored(it.stored)) }
    }
}
