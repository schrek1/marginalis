package dev.marginalis.plugin.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.WalkPosition
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.diff.ReviewDiffMarkers
import dev.marginalis.plugin.ui.diff.ReviewDiffSteps
import dev.marginalis.plugin.ui.tab.ProjectTab

object WalkthroughNavigator {

    fun walkFrom(project: Project, thread: CommentThread): WalkPosition =
        Walkthrough.walkFrom(MarginalisStore.getInstance(project).threads.all(), thread)

    fun stableTotal(project: Project, thread: CommentThread): Int? =
        Walkthrough.stableTotal(MarginalisStore.getInstance(project).threads.all(), thread)

    /**
     * @param from the editor the move starts in; a step in the file it shows scrolls that editor, a diff included,
     *   and a step in another file of a review diff switches that diff to the file
     */
    fun navigateTo(project: Project, thread: CommentThread, revealing: Message? = null, from: Editor? = null) {
        val path = thread.file ?: return ProjectTab.reveal(project, thread, revealing)
        val currentEditor = from?.takeIf { !it.isDisposed }
        val stayed = when {
            currentEditor == null -> false
            currentEditor.shows(project, path) -> showStepIn(project, currentEditor, thread, revealing)
            else -> ReviewDiffSteps.switchTo(project, currentEditor, path, thread, revealing)
        }
        if (!stayed) openStepInFileTab(project, thread, revealing)
    }

    /**
     * Scrolls [editor] to the step and opens its thread there.
     *
     * @return false when the text [editor] shows holds no anchor for the step; nothing was changed then
     */
    fun showStepIn(project: Project, editor: Editor, thread: CommentThread, revealing: Message?): Boolean {
        val diffLine = editor.getUserData(ThreadInlayManager.THREAD_LINE)
        val line = if (diffLine != null) diffLine(thread) ?: return false
        else MarginalisStore.getInstance(project).syncLine(thread) ?: 0
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread, revealing)
        return true
    }

    fun openStepInFileTab(project: Project, thread: CommentThread, revealing: Message?) {
        val path = thread.file ?: return
        val file = project.guessProjectDir()?.findFileByRelativePath(path) ?: return
        val line = MarginalisStore.getInstance(project).syncLine(thread) ?: 0
        OpenFileDescriptor(project, file, line, 0).navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        ThreadInlayManager.open(project, editor, thread, revealing)
    }

    // A review diff's editor holds a revision's copy of the file, so only its tag names the file it shows.
    private fun Editor.shows(project: Project, path: String): Boolean {
        getUserData(ReviewDiffMarkers.PROJECT_PATH)?.let { return it == path }
        val file = FileDocumentManager.getInstance().getFile(document) ?: return false
        return file == project.guessProjectDir()?.findFileByRelativePath(path)
    }
}
