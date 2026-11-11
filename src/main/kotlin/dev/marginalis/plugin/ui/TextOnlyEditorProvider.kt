package dev.marginalis.plugin.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/**
 * Shows a file [showAsText] marked as plain text alone: its other editors, a preview that fails to build included,
 * are never created. The mark lasts until the IDE restarts.
 */
class TextOnlyEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file.getUserData(TEXT_ONLY) == true

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TextEditorProvider.getInstance().createEditor(project, file)

    override fun getEditorTypeId(): String = "marginalis-text-only"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_OTHER_EDITORS

    companion object {
        private val TEXT_ONLY = Key.create<Boolean>("marginalis.textOnly")

        fun showAsText(file: VirtualFile) = file.putUserData(TEXT_ONLY, true)
    }
}
