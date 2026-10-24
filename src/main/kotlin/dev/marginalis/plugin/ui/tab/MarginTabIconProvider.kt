package dev.marginalis.plugin.ui.tab

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.plugin.ui.MarginalisIcons
import javax.swing.Icon

class MarginTabIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        MarginalisIcons.Brand.takeIf { file is MarginFile }
}
