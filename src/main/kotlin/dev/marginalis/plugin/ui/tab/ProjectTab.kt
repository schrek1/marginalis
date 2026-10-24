package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message

object ProjectTab {

    private const val REOPEN_AFTER_RELOAD = "marginalis.projectTab.reopen"
    private const val PINNED_BEFORE_RELOAD = "marginalis.projectTab.pinned"
    private const val INTRODUCED = "marginalis.projectTab.introduced"

    fun reveal(project: Project, thread: CommentThread, revealing: Message? = null) =
        deliver(project, TabRequest.Reveal(thread.id, revealing))

    fun draftNew(project: Project) {
        ProjectTabState.getInstance(project).ensureDraft()
        deliver(project, TabRequest.DraftNew)
    }

    fun isInFront(project: Project): Boolean =
        FileEditorManager.getInstance(project).selectedEditor?.file is MarginFile

    fun refreshPresentation(project: Project) {
        val editors = FileEditorManager.getInstance(project)
        openTabs(project).forEach(editors::updateFilePresentation)
    }

    fun applyPrefs() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            FileEditorManager.getInstance(project).allEditors
                .filterIsInstance<MarginEditor>()
                .forEach(MarginEditor::applyPrefs)
        }
    }

    fun closeEverywhere() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val editors = FileEditorManager.getInstance(project)
            val properties = PropertiesComponent.getInstance(project)
            val tabs = openTabs(project)
            properties.setValue(REOPEN_AFTER_RELOAD, tabs.isNotEmpty())
            properties.setValue(PINNED_BEFORE_RELOAD, isPinned(project))
            tabs.forEach(editors::closeFile)
            val history = EditorHistoryManager.getInstance(project)
            history.fileList.filterIsInstance<MarginFile>().forEach(history::removeFile)
        }
        MarginFile.withdraw()
    }

    fun openOnStartup(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        val reopen = properties.getBoolean(REOPEN_AFTER_RELOAD)
        val pinned = properties.getBoolean(PINNED_BEFORE_RELOAD)
        properties.unsetValue(REOPEN_AFTER_RELOAD)
        properties.unsetValue(PINNED_BEFORE_RELOAD)
        val firstRun = !properties.getBoolean(INTRODUCED)
        properties.setValue(INTRODUCED, true)
        if (reopen || firstRun) openBehindSelection(project, pin = pinned || firstRun)
    }

    private fun openBehindSelection(project: Project, pin: Boolean) {
        val editors = FileEditorManager.getInstance(project)
        val selected = editors.selectedFiles.firstOrNull()
        val tab = MarginFile.of(project)
        editors.openFile(tab, false, true)
        if (pin) pin(project, tab)
        selected?.let { editors.openFile(it, false, true) }
    }

    private fun openTabs(project: Project): List<MarginFile> =
        FileEditorManager.getInstance(project).openFiles.filterIsInstance<MarginFile>()

    private fun isPinned(project: Project): Boolean {
        val tabs = openTabs(project)
        return FileEditorManagerEx.getInstanceEx(project).windows.any { window -> tabs.any { window.getComposite(it)?.isPinned == true } }
    }

    private fun pin(project: Project, tab: MarginFile) =
        FileEditorManagerEx.getInstanceEx(project).windows
            .filter { it.isFileOpen(tab) }
            .forEach { it.setFilePinned(tab, true) }

    private fun deliver(project: Project, request: TabRequest) {
        val state = ProjectTabState.getInstance(project)
        state.request(request)
        val shown = FileEditorManager.getInstance(project).openFile(MarginFile.of(project), true, true).filterIsInstance<MarginEditor>()
        val pending = state.takeRequest() ?: return
        shown.forEach { it.handle(pending) }
    }
}
