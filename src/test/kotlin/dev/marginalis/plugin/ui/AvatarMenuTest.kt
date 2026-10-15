package dev.marginalis.plugin.ui

import dev.marginalis.plugin.testing.NeedsADisplay
import dev.marginalis.plugin.ui.toolwindow.ToolWindowTestCase
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
class AvatarMenuTest : ToolWindowTestCase() {

    fun testClickingAnAvatarOffersToStopThatAgent() {
        val waiting = agent.startWait("stay" to true)

        window.clickAvatar("Claude", choose = "Stop Claude")

        assertEquals("stopped", agent.await(waiting)["reason"]!!.asString)
        assertEquals(emptyList<String>(), window.presence())
        assertFalse(window.isEnabled("Submit round"))
    }
}
