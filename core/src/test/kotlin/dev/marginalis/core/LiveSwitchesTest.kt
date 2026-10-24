package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSwitchesTest {

    private val user = Author.User("Muhammad")
    private val switches = LiveSwitches()

    private fun thread() = CommentThread(file = "a.py", line = 1, anchorText = "x")

    @Test
    fun `a thread's own switch wins over the project`() {
        val on = thread()
        val off = thread()

        switches.switch(on.id, true)
        switches.switch(off.id, false)

        assertTrue(switches.isLive(on, projectLive = false))
        assertFalse(switches.isLive(off, projectLive = true))
    }

    @Test
    fun `turning the project clears every thread's own switch and tells which threads changed`() {
        val switchedOn = thread()
        val switchedOff = thread()
        val untouched = thread()
        switches.switch(switchedOn.id, true)
        switches.switch(switchedOff.id, false)

        val turned = switches.turnProject(listOf(switchedOn, switchedOff, untouched), wasLive = false, isLive = true)

        assertEquals(listOf(switchedOff, untouched), turned.nowLive)
        assertEquals(emptyList<CommentThread>(), turned.noLongerLive)
        assertTrue(switches.isLive(switchedOff, projectLive = true))
    }

    @Test
    fun `turning the project off reports every thread that stopped and forgets whom it was waking`() {
        val switchedOn = thread()
        val following = thread()
        switches.switch(switchedOn.id, true)
        switches.target(switchedOn.id, "claude-main")
        switches.target(following.id, "claude-main")

        val turned = switches.turnProject(listOf(switchedOn, following), wasLive = true, isLive = false)

        assertEquals(listOf(switchedOn, following), turned.noLongerLive)
        assertFalse(switches.isLive(switchedOn, projectLive = false))
        assertNull(switches.targetOf(switchedOn.id))
        assertNull(switches.targetOf(following.id))
    }

    @Test
    fun `withdrawing a thread forgets whom it was waking but keeps its switch`() {
        val t = thread()
        switches.switch(t.id, true)
        switches.target(t.id, "claude-main")

        switches.withdraw(t.id)

        assertNull(switches.targetOf(t.id))
        assertTrue(switches.isLive(t, projectLive = false))
    }

    @Test
    fun `a thread that ends forgets its switch and resumes once it is open again`() {
        val t = thread()
        switches.switch(t.id, false)
        switches.target(t.id, "claude-main")

        switches.end(t.id)

        assertNull(switches.targetOf(t.id))
        assertTrue(switches.isLive(t, projectLive = true))
        assertTrue(switches.resumed(t.id))
        assertFalse(switches.resumed(t.id))
    }

    @Test
    fun `a thread that never ended or was deleted does not resume`() {
        val ongoing = thread()
        val deleted = thread().also { it.resolve(user) }
        switches.end(deleted.id)

        switches.forget(deleted.id)

        assertFalse(switches.resumed(ongoing.id))
        assertFalse(switches.resumed(deleted.id))
    }
}
