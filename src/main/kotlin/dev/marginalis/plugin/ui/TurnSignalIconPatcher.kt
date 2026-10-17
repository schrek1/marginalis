package dev.marginalis.plugin.ui

import com.intellij.ide.FileIconPatcher
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

class TurnSignalIconPatcher : FileIconPatcher {

    override fun patchIcon(baseIcon: Icon, file: VirtualFile, flags: Int, project: Project?): Icon {
        if (withdrawn || project == null || project.isDisposed || file.isDirectory) return baseIcon
        val turn = FileTurn.of(project, file) ?: return baseIcon
        return MarginalisIcons.withTurnBadge(baseIcon, turn)
    }

    companion object {
        @Volatile
        private var withdrawn = false

        fun withdraw() {
            withdrawn = true
        }

        @TestOnly
        internal fun reinstate() {
            withdrawn = false
        }
    }
}
