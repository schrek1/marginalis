package dev.marginalis.plugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.openapi.util.text.StringUtil
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
     * @param leaving runs once the move is sure to land, e.g. to fold the step being left; not when the step's file
     *   could not be shown (a balloon says so then)
     */
    fun navigateTo(
        project: Project,
        thread: CommentThread,
        revealing: Message? = null,
        from: Editor? = null,
        leaving: () -> Unit = {},
    ) {
        val path = thread.file ?: return ProjectTab.reveal(project, thread, revealing).also { leaving() }
        val stayed = when {
            from == null || from.isDisposed -> false
            from.shows(project, path) -> navigateWithin(from, project, thread, revealing, leaving)
            else -> ReviewDiffSteps.switchTo(project, from, path, thread, revealing).also { switched -> if (switched) leaving() }
        }
        if (!stayed) openStepInFileTab(project, thread, revealing, leaving)
    }

    /**
     * Moves [editor] to the step and opens its thread there, as one step of the IDE's Back / Forward history.
     *
     * @param leaving runs right before the move, so a panel it removes above the step does not shift the scrolled view
     * @return false when [editor] is released, shows another file, or (a review diff) holds no anchor for the step;
     *   nothing was changed then
     */
    fun navigateWithin(
        editor: Editor,
        project: Project,
        thread: CommentThread,
        revealing: Message? = null,
        leaving: () -> Unit = {},
    ): Boolean {
        val path = thread.file ?: return false
        if (editor.isDisposed || !editor.shows(project, path)) return false
        val shown = editor.getUserData(ThreadInlayManager.SHOWN_THREADS)
        if (shown != null) {
            val line = shown.lineOf(thread) ?: return false
            leaving()
            recordingHistory(project) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        } else {
            val place = placeOf(project, thread) ?: return false
            leaving()
            recordingHistory(project) { place.navigateIn(editor) }
        }
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread, revealing)
        return true
    }

    /**
     * @param leaving runs once the step's file has stayed open with the step shown; when the IDE does not keep the
     *   file open, a balloon names the step and its file instead
     */
    fun openStepInFileTab(project: Project, thread: CommentThread, revealing: Message?, leaving: () -> Unit = {}) {
        val place = placeOf(project, thread) ?: return stepNotShown(project, thread)
        val editors = FileEditorManager.getInstance(project)
        // The step file's own editor: when its tab closes while opening, the selected one is still the previous file's.
        val editor = editors.openTextEditor(place, true) ?: return stepNotShown(project, thread)
        ThreadInlayManager.open(project, editor, thread, revealing)
        // A file whose preview editor fails to build (Swagger UI on an OpenAPI spec) opens and is closed right after.
        ApplicationManager.getApplication().invokeLater({
            if (editor.isDisposed || !editors.isFileOpen(place.file)) stepNotShown(project, thread) else leaving()
        }, project.disposed)
    }

    private fun stepNotShown(project: Project, thread: CommentThread) {
        val step = thread.order?.let { order -> stableTotal(project, thread)?.let { total -> "step $order/$total" } } ?: "this thread"
        NotificationGroupManager.getInstance().getNotificationGroup("Marginalis")
            .createNotification(
                "Couldn't show $step: the IDE didn't keep <code>${StringUtil.escapeXmlEntities(thread.file.orEmpty())}</code> open, " +
                    "so the walk stays where it was.",
                NotificationType.WARNING,
            )
            .notify(project)
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
