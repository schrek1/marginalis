package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ProjectTabStartup
import dev.marginalis.core.ProjectTabStartup.Outcome
import dev.marginalis.plugin.store.MarginalisStore

object ProjectTab {

    private const val WAS_OPEN = "marginalis.projectTab.reopen"
    private const val WAS_PINNED = "marginalis.projectTab.pinned"
    private const val INTRODUCED = "marginalis.projectTab.introduced"

    fun reveal(project: Project, thread: CommentThread, revealing: Message? = null) =
        deliver(project, TabRequest.Reveal(thread.id, revealing))

    fun draftNew(project: Project) {
        ProjectTabState.getInstance(project).ensureDraft()
        deliver(project, TabRequest.DraftNew)
    }

    fun isInFront(project: Project): Boolean =
        FileEditorManager.getInstance(project).selectedEditor?.file == MarginFile

    fun refreshPresentation(project: Project) {
        val editors = FileEditorManager.getInstance(project)
        if (editors.isFileOpen(MarginFile)) editors.updateFilePresentation(MarginFile)
    }

    fun applyPrefs() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            FileEditorManager.getInstance(project).getAllEditors(MarginFile)
                .filterIsInstance<MarginEditor>()
                .forEach(MarginEditor::applyPrefs)
        }
    }

    fun closeEverywhere() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            park(project)
            EditorHistoryManager.getInstance(project).removeFile(MarginFile)
        }
        MarginFile.isValid = false
    }

    // The platform restores editor tabs before the project's threads load, so it can't tell an empty tab from
    // a busy one. The tab never reaches the saved editor state; openOnStartup decides once the threads are in.
    fun park(project: Project) {
        val editors = FileEditorManager.getInstance(project)
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(WAS_OPEN, editors.isFileOpen(MarginFile))
        properties.setValue(WAS_PINNED, isPinned(project))
        editors.closeFile(MarginFile)
    }

    fun openOnStartup(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        val outcome = ProjectTabStartup.outcome(
            threads = MarginalisStore.getInstance(project).threads.all(),
            wasOpen = properties.getBoolean(WAS_OPEN),
            wasPinned = properties.getBoolean(WAS_PINNED),
            firstOpen = !properties.getBoolean(INTRODUCED),
        )
        properties.unsetValue(WAS_OPEN)
        properties.unsetValue(WAS_PINNED)
        properties.setValue(INTRODUCED, true)
        if (outcome != Outcome.STAY_CLOSED) openBehindSelection(project, pin = outcome == Outcome.OPEN_PINNED)
    }

    fun showBehindSelection(project: Project) {
        if (!FileEditorManager.getInstance(project).isFileOpen(MarginFile)) openBehindSelection(project, pin = false)
    }

    private fun openBehindSelection(project: Project, pin: Boolean) {
        val editors = FileEditorManager.getInstance(project)
        val selected = editors.selectedFiles.firstOrNull()
        editors.openFile(MarginFile, false, true)
        if (pin) pin(project)
        selected?.let { editors.openFile(it, false, true) }
    }

    private fun isPinned(project: Project): Boolean =
        FileEditorManagerEx.getInstanceEx(project).windows.any { it.isFilePinned(MarginFile) }

    private fun pin(project: Project) =
        FileEditorManagerEx.getInstanceEx(project).windows
            .filter { it.isFileOpen(MarginFile) }
            .forEach { it.setFilePinned(MarginFile, true) }

    private fun deliver(project: Project, request: TabRequest) {
        val state = ProjectTabState.getInstance(project)
        state.request(request)
        val shown = FileEditorManager.getInstance(project).openFile(MarginFile, true, true).filterIsInstance<MarginEditor>()
        val pending = state.takeRequest() ?: return
        shown.forEach { it.handle(pending) }
    }
}
