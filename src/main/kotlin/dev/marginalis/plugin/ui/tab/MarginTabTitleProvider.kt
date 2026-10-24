package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.ProjectTabTally
import dev.marginalis.plugin.store.MarginalisStore

class MarginTabTitleProvider : EditorTabTitleProvider, DumbAware {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        if (file is MarginFile) ProjectTabTally.of(MarginalisStore.getInstance(project).threads.all()).title else null
}
