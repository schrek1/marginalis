package dev.marginalis.plugin.testing

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.marginalis.plugin.MarginalisUnloadListener
import dev.marginalis.plugin.ui.TurnSignalIconPatcher
import dev.marginalis.plugin.ui.tab.MarginFile

object PluginUnload {

    val marginalis: IdeaPluginDescriptor get() = PluginManagerCore.getPlugin(PluginId.getId("dev.marginalis.plugin"))!!

    fun forUpdate(of: IdeaPluginDescriptor = marginalis, whileUnloaded: () -> Unit = {}): Throwable? = try {
        runCatching { MarginalisUnloadListener().beforePluginUnload(of, true) }.exceptionOrNull().also { whileUnloaded() }
    } finally {
        TurnSignalIconPatcher.reinstate()
        MarginFile.reinstate()
    }
}
