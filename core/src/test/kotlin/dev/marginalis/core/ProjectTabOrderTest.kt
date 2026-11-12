package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTabOrderTest {

    @Test
    fun `a saved order reads back from the strings earlier versions wrote`() {
        assertEquals(ProjectTabOrder.OLDEST_FIRST, ProjectTabOrder.fromStored("OLDEST_FIRST"))
        assertEquals(ProjectTabOrder.NEWEST_FIRST, ProjectTabOrder.fromStored("NEWEST_FIRST"))
        assertEquals(ProjectTabOrder.RECENT_ACTIVITY, ProjectTabOrder.fromStored("RECENT_ACTIVITY"))
    }

    @Test
    fun `no saved order or an unknown one falls back to the default`() {
        assertEquals(ProjectTabOrder.DEFAULT, ProjectTabOrder.fromStored(null))
        assertEquals(ProjectTabOrder.DEFAULT, ProjectTabOrder.fromStored("SIDEWAYS"))
    }
}
