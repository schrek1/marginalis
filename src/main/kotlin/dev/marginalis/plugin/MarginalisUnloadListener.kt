package dev.marginalis.plugin

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.ProjectManager
import dev.marginalis.plugin.store.MarginalisPersistence
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.FileTurn
import dev.marginalis.plugin.ui.ThreadInlayManager
import dev.marginalis.plugin.ui.TurnSignalIconPatcher
import dev.marginalis.plugin.ui.diff.ReviewDiffMarkers
import dev.marginalis.plugin.ui.tab.ProjectTab

// Strips our classes from platform structures that outlive the classloader
// (file-icon badges, highlighters on the persistent document markup model,
// editor inlays and user data). Anything left pins the classloader and the
// IDE demands a restart instead of a dynamic unload.
class MarginalisUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != "dev.marginalis.plugin") return

        TurnSignalIconPatcher.withdraw()
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            FileTurn.redrawAll(project)
            val store = MarginalisStore.getInstance(project)
            store.syncLines()
            MarginalisPersistence.save(project, store.snapshot())
            val ours = store.threads.all().mapNotNull { store.removeMarker(it) } + store.clearFileGlyphs()
            for (marker in ours) {
                if (marker.isValid) {
                    DocumentMarkupModel.forDocument(marker.document, project, false)
                        ?.removeHighlighter(marker)
                }
            }
        }
        ProjectTab.closeEverywhere()
        ReviewDiffMarkers.disposeAll()
        ThreadInlayManager.disposeAll()
    }
}
