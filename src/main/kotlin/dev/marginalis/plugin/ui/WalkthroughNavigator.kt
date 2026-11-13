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
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ThreadListing
import dev.marginalis.core.WalkPosition
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.diff.ReviewDiffSteps
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

    /**
     * @param from the editor the move starts in; a step in the file it shows scrolls that editor, a diff included,
     *   and a step in another file of a review diff switches that diff to the file
     * @param onShown runs once the move is sure to land, e.g. to fold the step being left
     * @param onNotShown gets the reason when the step's file could not be shown; the walk then stays where it was
     */
    fun navigateTo(
        project: Project,
        thread: CommentThread,
        revealing: Message? = null,
        from: Editor? = null,
        onShown: () -> Unit = {},
        onNotShown: (problem: String) -> Unit = { warn(project, it) },
    ) {
        val path = thread.file ?: return run {
            LastStep.getInstance(project).landedOn(thread)
            ProjectTab.reveal(project, thread, revealing)
            onShown()
        }
        val stayed = when {
            from == null || from.isDisposed -> false
            from.shows(project, path) -> navigateWithin(from, project, thread, revealing, beforeMoving = onShown)
            else -> ReviewDiffSteps.switchTo(project, from, path, thread, revealing, onShown)
        }
        if (!stayed) openStepInFileTab(project, thread, revealing, onShown, onNotShown)
    }

    /**
     * Moves [editor] to the step and opens its thread there, as one step of the IDE's Back / Forward history.
     *
     * @param beforeMoving runs right before the move, so a panel it removes above the step does not shift the view
     * @return false when [editor] is released, shows another file, or (a review diff) holds no anchor for the step;
     *   nothing was changed then
     */
    fun navigateWithin(
        editor: Editor,
        project: Project,
        thread: CommentThread,
        revealing: Message? = null,
        beforeMoving: () -> Unit = {},
    ): Boolean {
        val path = thread.file ?: return false
        if (editor.isDisposed || !editor.shows(project, path)) return false
        val shown = editor.getUserData(ThreadInlayManager.SHOWN_THREADS)
        if (shown != null) {
            val line = shown.lineOf(thread) ?: return false
            beforeMoving()
            recordingHistory(project) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        } else {
            val place = placeOf(project, thread) ?: return false
            beforeMoving()
            recordingHistory(project) { place.navigateIn(editor) }
        }
        editor.contentComponent.requestFocusInWindow()
        ThreadInlayManager.open(project, editor, thread, revealing)
        return true
    }

    /**
     * Opens the step in its file's own tab. When the IDE does not keep the file open, the file opens again as plain
     * text alone; only when that fails too does [onNotShown] get the reason, with the walk back on the step it was on.
     */
    fun openStepInFileTab(
        project: Project,
        thread: CommentThread,
        revealing: Message?,
        onShown: () -> Unit = {},
        onNotShown: (problem: String) -> Unit = { warn(project, it) },
    ) {
        val place = placeOf(project, thread)
            ?: return onNotShown(couldNotShow(project, thread, "${thread.file} is no longer in the project"))
        val lastStep = LastStep.getInstance(project)
        val stepBefore = lastStep.mark
        // A file whose preview editor fails to build (Swagger UI on an OpenAPI spec) opens and is closed right after.
        showStep(project, place, thread, revealing, onShown) {
            FileEditorManager.getInstance(project).closeFile(place.file)
            TextOnlyEditorProvider.showAsText(place.file)
            showStep(project, place, thread, revealing, onShown) { problem ->
                lastStep.returnTo(stepBefore)
                onNotShown(problem)
            }
        }
    }

    private fun showStep(
        project: Project,
        place: OpenFileDescriptor,
        thread: CommentThread,
        revealing: Message?,
        shown: () -> Unit,
        notShown: (problem: String) -> Unit,
    ) {
        val editors = FileEditorManager.getInstance(project)
        fun closedAsItOpened() = couldNotShow(project, thread, "the IDE closed ${thread.file} as it opened")
        place.navigate(true)
        // The step file's own editor: when its tab closes while opening, the selected one is still the previous file's.
        val editor = (editors.getSelectedEditor(place.file) as? TextEditor)?.editor
            ?: return notShown(
                if (editors.isFileOpen(place.file)) couldNotShow(project, thread, "${thread.file} doesn't open in a text editor")
                else closedAsItOpened(),
            )
        ThreadInlayManager.open(project, editor, thread, revealing)
        ApplicationManager.getApplication().invokeLater({
            if (editor.isDisposed || !editors.isFileOpen(place.file)) notShown(closedAsItOpened()) else shown()
        }, project.disposed)
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
