package dev.marginalis.plugin.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
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
     * @param leaving runs once the step is shown, e.g. to fold the step being left; when the IDE does not keep the
     *   step's file open, a balloon names the step and its file instead
     */
    fun navigateTo(project: Project, thread: CommentThread, revealing: Message? = null, leaving: () -> Unit = {}) {
        if (thread.file == null) return ProjectTab.reveal(project, thread, revealing).also { leaving() }
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

    /** @param leaving runs right before the move, so a panel it removes above the step does not shift the scrolled view */
    fun navigateWithin(editor: Editor, project: Project, thread: CommentThread, leaving: () -> Unit = {}): Boolean {
        if (editor.isDisposed) return false
        val place = placeOf(project, thread)?.takeIf { editor.shows(it.file) } ?: return false
        leaving()
        place.navigateInRecordingHistory(editor)
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread)
        return true
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

    private fun Editor.shows(file: VirtualFile): Boolean = FileDocumentManager.getInstance().getFile(document) == file

    private fun OpenFileDescriptor.navigateInRecordingHistory(editor: Editor) {
        CommandProcessor.getInstance().executeCommand(project, {
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            navigateIn(editor)
        }, null, null)
    }
}
