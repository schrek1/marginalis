package dev.marginalis.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

class TextOnlyEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean =
        project.serviceIfCreated<TextOnlyFiles>()?.isMarked(file) == true && canShowAsText(project, file)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TextEditorProvider.getInstance().createEditor(project, file)

    override fun getEditorTypeId(): String = "marginalis-text-only"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

    companion object {
        fun canShowAsText(project: Project, file: VirtualFile): Boolean = TextEditorProvider.getInstance().accept(project, file)

        fun closeToReopenAsText(project: Project, file: VirtualFile) {
            val editors = FileEditorManager.getInstance(project)
            if (editors.isFileOpen(file)) editors.closeFile(file)
            project.service<TextOnlyFiles>().mark(file)
        }

        fun forget(project: Project, file: VirtualFile) {
            project.serviceIfCreated<TextOnlyFiles>()?.forget(file)
        }

        fun closeEveryFileShownAsText(project: Project) {
            val marked = project.serviceIfCreated<TextOnlyFiles>()?.takeAll() ?: return
            val editors = FileEditorManager.getInstance(project)
            marked.filter(editors::isFileOpen).forEach(editors::closeFile)
        }
    }
}

@Service(Service.Level.PROJECT)
class TextOnlyFiles(project: Project) : Disposable {

    private val marked = ConcurrentHashMap.newKeySet<VirtualFile>()

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (!source.isFileOpen(file)) forget(file)
                }
            },
        )
    }

    fun mark(file: VirtualFile) {
        marked.add(file)
    }

    fun forget(file: VirtualFile) {
        marked.remove(file)
    }

    fun isMarked(file: VirtualFile): Boolean = file in marked

    fun takeAll(): List<VirtualFile> = marked.toList().also { marked.clear() }

    override fun dispose() = marked.clear()
}
