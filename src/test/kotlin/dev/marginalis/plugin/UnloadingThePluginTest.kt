package dev.marginalis.plugin

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.plugin.testing.PluginUnload
import dev.marginalis.plugin.ui.TurnSignalIconPatcher
import dev.marginalis.plugin.ui.tab.MarginFile
import dev.marginalis.plugin.ui.tab.MarginTabTestCase

class UnloadingThePluginTest : MarginTabTestCase() {

    private val base = AllIcons.FileTypes.Text

    private fun badge(): Any {
        val file = project.guessProjectDir()!!.findFileByRelativePath(SAMPLE)!!
        return TurnSignalIconPatcher().patchIcon(base, file, 0, project)
    }

    fun testUpdatingThePluginWithTheTabOpenTakesItsMarksPanelsBadgesAndTabOutOfTheIde() {
        sample()
        val thread = agent.addOnLine(5, "body" to "Still open in the editor")
        agent.add("file" to SAMPLE, "body" to "About the whole file")
        user.open(thread)
        tab.settle()
        assertNotNull(user.gutterIcon(SAMPLE, 5))
        assertEquals(1, user.gutterIconsOnLine(SAMPLE, 1).size)
        assertEquals(1, user.panels().size)
        assertNotSame(base, badge())
        var badgeWhileUnloading: Any? = null
        var tabValidWhileUnloading = true

        val failure = tab.unloadPluginForUpdate {
            badgeWhileUnloading = badge()
            tabValidWhileUnloading = MarginFile.isValid
        }

        assertNull(failure)
        assertSame(base, badgeWhileUnloading)
        assertFalse(tabValidWhileUnloading)
        assertFalse(tab.isOpen)
        assertEquals(emptyList<Any>(), user.gutterIconsOnLine(SAMPLE, 5))
        assertEquals(emptyList<Any>(), user.gutterIconsOnLine(SAMPLE, 1))
        assertEquals(emptyList<Any>(), user.panels())
    }

    fun testUnloadingAnotherPluginLeavesTheMarginAsItWas() {
        sample()
        val thread = agent.addOnLine(5, "body" to "Still open in the editor")
        user.open(thread)

        val failure = PluginUnload.forUpdate(of = PluginManagerCore.getPlugin(PluginManagerCore.CORE_ID)!!)
        tab.settle()

        assertNull(failure)
        assertTrue(tab.isOpen)
        assertNotNull(user.gutterIcon(SAMPLE, 5))
        assertEquals(1, user.panels().size)
        assertNotSame(base, badge())
    }
}
