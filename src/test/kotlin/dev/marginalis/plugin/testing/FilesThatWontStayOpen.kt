package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile

class FilesThatWontStayOpen(private val project: Project, private val disposable: Disposable) {

    fun closeOnOpening(path: String) = whenOpened(path) { manager, file -> manager.closeFile(file) }

    fun closeJustAfterOpening(path: String) = whenOpened(path) { manager, file ->
        ApplicationManager.getApplication().invokeLater { manager.closeFile(file) }
    }

    private fun whenOpened(path: String, close: (FileEditorManager, VirtualFile) -> Unit) {
        val target = project.guessProjectDir()!!.findFileByRelativePath(path)!!
        project.messageBus.connect(disposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (file == target) close(source, file)
                }
            },
        )
    }
}
