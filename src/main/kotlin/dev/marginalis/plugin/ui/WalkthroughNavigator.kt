package dev.marginalis.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.WalkPosition
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.tab.ProjectTab

object WalkthroughNavigator {

    fun walkFrom(project: Project, thread: CommentThread): WalkPosition =
        Walkthrough.walkFrom(MarginalisStore.getInstance(project).threads.all(), thread)

    fun stableTotal(project: Project, thread: CommentThread): Int? =
        Walkthrough.stableTotal(MarginalisStore.getInstance(project).threads.all(), thread)

    /**
     * @param from the editor the move starts in; a step in the file it shows scrolls that editor, a diff included
     */
    fun navigateTo(project: Project, thread: CommentThread, revealing: Message? = null, from: Editor? = null) {
        val path = thread.file ?: return ProjectTab.reveal(project, thread, revealing)
        val base = project.guessProjectDir() ?: return
        val targetFile = base.findFileByRelativePath(path) ?: return
        val line = MarginalisStore.getInstance(project).syncLine(thread) ?: 0
        val currentEditor = from?.takeIf { !it.isDisposed }
        if (currentEditor != null && currentEditor.shows(targetFile)) {
            scrollToStep(project, currentEditor, line, thread, revealing)
        } else {
            openStepInFileTab(project, targetFile, line, thread, revealing)
        }
    }

    private fun Editor.shows(file: VirtualFile): Boolean = FileDocumentManager.getInstance().getFile(document) == file

    private fun scrollToStep(project: Project, editor: Editor, line: Int, thread: CommentThread, revealing: Message?) {
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread, revealing)
    }

    private fun openStepInFileTab(
        project: Project,
        file: VirtualFile,
        line: Int,
        thread: CommentThread,
        revealing: Message?,
    ) {
        OpenFileDescriptor(project, file, line, 0).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        ThreadInlayManager.open(project, editor, thread, revealing)
    }
}
