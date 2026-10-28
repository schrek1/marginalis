package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class MarginEditor(private val project: Project, private val file: MarginFile) : UserDataHolderBase(), FileEditor {

    private val content = ProjectTabPanel(project).also { Disposer.register(this, it) }

    fun handle(request: TabRequest) = when (request) {
        is TabRequest.Reveal -> content.reveal(request.threadId, request.message)
        TabRequest.DraftNew -> content.draftNew()
    }

    fun applyPrefs() = content.applyPrefs()

    override fun selectNotify() {
        // The platform also notifies an editor that arrives after its tab went behind another file.
        if (file !in FileEditorManager.getInstance(project).selectedFiles) return
        content.onShown(inFront = true)
        ProjectTabState.getInstance(project).takeRequest()?.let(::handle)
    }

    override fun deselectNotify() {
        content.onShown(inFront = false)
    }

    override fun getComponent(): JComponent = content.component

    override fun getPreferredFocusedComponent(): JComponent? = content.preferredFocus

    override fun getName(): String = "Margin"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}
}
