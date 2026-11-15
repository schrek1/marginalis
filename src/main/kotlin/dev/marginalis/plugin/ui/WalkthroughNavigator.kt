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
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ThreadListing
import dev.marginalis.core.WalkPosition
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.tab.ProjectTab
import java.time.Instant

object WalkthroughNavigator {

    fun walkFrom(project: Project, thread: CommentThread): WalkPosition =
        Walkthrough.walkFrom(MarginalisStore.getInstance(project).threads.all(), thread)

    fun stableTotal(project: Project, thread: CommentThread): Int? =
        Walkthrough.stableTotal(MarginalisStore.getInstance(project).threads.all(), thread)

    fun startTime(): (Instant) -> String = MarginalisSettings.getInstance().timeFormat.formatter()::format

    fun title(project: Project, thread: CommentThread): String =
        ThreadListing.titleOf(MarginalisStore.getInstance(project).threads.all(), thread, startTime())

    fun navigateTo(
        project: Project,
        thread: CommentThread,
        revealing: Message? = null,
        onShown: () -> Unit = {},
        onNotShown: (problem: String) -> Unit = { warn(project, it) },
    ) = navigateTo(project, thread, revealing, onShown, onNotShown, asText = false)

    private fun navigateTo(
        project: Project,
        thread: CommentThread,
        revealing: Message?,
        onShown: () -> Unit,
        onNotShown: (problem: String) -> Unit,
        asText: Boolean,
    ) {
        val lastStep = LastStep.getInstance(project)
        if (thread.file == null) {
            lastStep.landedOn(thread)
            ProjectTab.reveal(project, thread, revealing)
            return onShown()
        }
        val place = placeOf(project, thread) ?: return onNotShown(couldNotShow(project, thread, "${thread.file} is no longer in the project"))
        val editors = FileEditorManager.getInstance(project)
        fun closedAsItOpened(mayRetry: Boolean = true) {
            if (asText) TextOnlyEditorProvider.forget(project, place.file)
            if (!mayRetry || asText || !TextOnlyEditorProvider.canShowAsText(project, place.file)) {
                return onNotShown(couldNotShow(project, thread, "the IDE closed ${thread.file} as it opened"))
            }
            TextOnlyEditorProvider.closeToReopenAsText(project, place.file)
            navigateTo(project, thread, revealing, onShown, onNotShown, asText = true)
        }
        // The step file's own editor: when its tab closes while opening, the selected one is still the previous file's.
        // navigate asks the user to associate a file of unknown type in a modal dialog; such a file has no text editor anyway.
        if (place.file.fileType !is UnknownFileType) place.navigate(true)
        val editor = (editors.getSelectedEditor(place.file) as? TextEditor)?.editor
            ?: return if (editors.isFileOpen(place.file)) {
                onNotShown(couldNotShow(project, thread, "${thread.file} doesn't open in a text editor"))
            } else {
                closedAsItOpened()
            }
        if (editor.isDisposed) return closedAsItOpened()
        val stepBefore = lastStep.mark
        ThreadInlayManager.open(project, editor, thread, revealing)
        val landing = lastStep.landings
        // A file whose preview editor fails to build (Swagger UI on an OpenAPI spec) opens and is closed right after.
        ApplicationManager.getApplication().invokeLater({
            if (editor.isDisposed || !editors.isFileOpen(place.file)) {
                val movedOn = lastStep.landings != landing
                if (!movedOn) lastStep.returnTo(stepBefore)
                closedAsItOpened(mayRetry = !movedOn)
            } else {
                onShown()
            }
        }, project.disposed)
    }

    fun navigateWithin(editor: Editor, project: Project, thread: CommentThread, beforeMoving: () -> Unit = {}): Boolean {
        if (editor.isDisposed) return false
        val move = moveWithin(editor, project, thread) ?: return false
        beforeMoving()
        recordingHistory(project, move)
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread)
        return true
    }

    fun warn(project: Project, problem: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Marginalis")
            .createNotification(StringUtil.escapeXmlEntities(problem), NotificationType.WARNING)
            .notify(project)
    }

    private fun couldNotShow(project: Project, thread: CommentThread, why: String): String {
        val what = thread.order?.let { order -> "walkthrough step $order" + stableTotal(project, thread)?.let { "/$it" }.orEmpty() } ?: "the thread"
        return "Couldn't show $what: $why."
    }

    private fun placeOf(project: Project, thread: CommentThread): OpenFileDescriptor? {
        val path = thread.file ?: return null
        val file = project.guessProjectDir()?.findFileByRelativePath(path) ?: return null
        val line = MarginalisStore.getInstance(project).syncLine(thread) ?: 0
        return OpenFileDescriptor(project, file, line, 0)
    }

    private fun moveWithin(editor: Editor, project: Project, thread: CommentThread): (() -> Unit)? {
        val shown = editor.getUserData(ThreadInlayManager.SHOWN_THREADS)
        if (shown != null) {
            val shownLine = shown.takeIf { it.file == thread.file }?.lineOf(thread) ?: return null
            val line = if (thread.isFileLevel) 0 else shownLine
            return {
                editor.caretModel.removeSecondaryCarets()
                editor.selectionModel.removeSelection()
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
        val place = placeOf(project, thread)?.takeIf { editor.shows(it.file) } ?: return null
        return { place.navigateIn(editor) }
    }

    private fun Editor.shows(file: VirtualFile): Boolean = FileDocumentManager.getInstance().getFile(document) == file

    private fun recordingHistory(project: Project, move: () -> Unit) {
        CommandProcessor.getInstance().executeCommand(project, {
            IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
            move()
        }, null, null)
    }
}
