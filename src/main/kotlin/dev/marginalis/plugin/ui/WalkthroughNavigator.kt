package dev.marginalis.plugin.ui

import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.WalkPosition
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.store.MarginalisStore
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
        val stayed = when {
            from == null || from.isDisposed -> false
            from.shows(project, path) -> navigateWithin(from, project, thread, revealing)
            else -> ReviewDiffSteps.switchTo(project, from, path, thread, revealing)
        }
        if (!stayed) openStepInFileTab(project, thread, revealing)
    }

    /**
     * Moves [editor] to the step and opens its thread there, as one step of the IDE's Back / Forward history.
     *
     * @return false when [editor] is released, shows another file, or (a review diff) holds no anchor for the step;
     *   nothing was changed then
     */
    fun navigateWithin(editor: Editor, project: Project, thread: CommentThread, revealing: Message? = null): Boolean {
        val path = thread.file ?: return false
        if (editor.isDisposed || !editor.shows(project, path)) return false
        val shown = editor.getUserData(ThreadInlayManager.SHOWN_THREADS)
        if (shown != null) {
            val line = shown.lineOf(thread) ?: return false
            recordingHistory(project) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        } else {
            val place = placeOf(project, thread) ?: return false
            recordingHistory(project) { place.navigateIn(editor) }
        }
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread, revealing)
        return true
    }

    fun openStepInFileTab(project: Project, thread: CommentThread, revealing: Message?) {
        val place = placeOf(project, thread) ?: return
        place.navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        ThreadInlayManager.open(project, editor, thread, revealing)
    }

    private fun placeOf(project: Project, thread: CommentThread): OpenFileDescriptor? {
        val path = thread.file ?: return null
        val file = project.guessProjectDir()?.findFileByRelativePath(path) ?: return null
        val line = MarginalisStore.getInstance(project).syncLine(thread) ?: 0
        return OpenFileDescriptor(project, file, line, 0)
    }

    private fun recordingHistory(project: Project, move: () -> Unit) {
        CommandProcessor.getInstance().executeCommand(project, {
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            move()
        }, null, null)
    }

    // A diff's editor may hold a revision's copy of the file, so only its tag names the file it shows.
    private fun Editor.shows(project: Project, path: String): Boolean {
        getUserData(ThreadInlayManager.SHOWN_THREADS)?.let { return it.file == path }
        val file = FileDocumentManager.getInstance().getFile(document) ?: return false
        return file == project.guessProjectDir()?.findFileByRelativePath(path)
    }
}
