package dev.marginalis.plugin.ui.tab

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.CommentThread
import dev.marginalis.core.MarginTabShows
import dev.marginalis.core.Message
import dev.marginalis.core.ProjectTabClosing
import dev.marginalis.core.ProjectTabStartup
import dev.marginalis.core.ProjectTabStartup.Outcome
import dev.marginalis.core.ProjectTabStartup.TabState
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletableFuture

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

    fun sortChanged(project: Project) {
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<MarginEditor>()
            .forEach(MarginEditor::sortChanged)
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
        val beforeReload = when {
            !properties.getBoolean(REOPEN_AFTER_RELOAD) -> TabState.CLOSED
            properties.getBoolean(PINNED_BEFORE_RELOAD) -> TabState.PINNED
            else -> TabState.OPEN
        }
        val outcome = ProjectTabStartup.outcome(
            shows = MarginalisSettings.getInstance().marginTabShows,
            threads = MarginalisStore.getInstance(project).threads.all(),
            firstOpen = !properties.getBoolean(INTRODUCED),
            beforeReload = beforeReload,
            restored = shownState(project),
        )
        properties.unsetValue(REOPEN_AFTER_RELOAD)
        properties.unsetValue(PINNED_BEFORE_RELOAD)
        properties.setValue(INTRODUCED, true)
        when (outcome) {
            Outcome.OPEN -> openBehindSelection(project, pin = false)
            Outcome.OPEN_PINNED -> openBehindSelection(project, pin = true)
            Outcome.CLOSE -> openTabs(project).forEach(FileEditorManager.getInstance(project)::closeFile)
            Outcome.LEAVE -> {}
        }
    }

    fun closeIfEmptyWhenTheProjectCloses(project: Project) {
        val closes = ProjectTabClosing.closesTheTab(
            shows = MarginalisSettings.getInstance().marginTabShows,
            threads = MarginalisStore.getInstance(project).threads.all(),
            shown = shownState(project),
        )
        if (closes) openTabs(project).forEach(FileEditorManager.getInstance(project)::closeFile)
    }

    class ClosedWhenEmpty : ProjectCloseListener {
        override fun projectClosingBeforeSave(project: Project) = closeIfEmptyWhenTheProjectCloses(project)
    }

    fun showNewProjectThreadLater(project: Project) {
        if (MarginalisSettings.getInstance().marginTabShows != MarginTabShows.WITH_SOMETHING_IN_IT) return
        val state = ProjectTabState.getInstance(project)
        val revealsBefore = state.reveals
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || state.reveals != revealsBefore || openTabs(project).isNotEmpty()) return@invokeLater
            val threads = MarginalisStore.getInstance(project).threads.all()
            if (ProjectTabStartup.hasOpenProjectThread(threads)) openBehindSelection(project, pin = false)
        }
    }

    private fun openBehindSelection(project: Project, pin: Boolean) {
        val editors = FileEditorManager.getInstance(project)
        val state = ProjectTabState.getInstance(project)
        val selected = editors.selectedFiles.firstOrNull()
        val revealsBefore = state.reveals
        val tab = MarginFile.of(project)
        // openFile waits on the EDT until every editor provider has checked the file; at startup a pending write holds
        // their checks back for 30 s. requestOpenFile adds the tab at once and lets the checks finish in the background.
        val opening = requestOpen(editors, tab)
        if (pin) {
            // Up to 2026.2 the tab is in its window once requestOpenFile returns; 2026.3 adds it later, on the EDT.
            if (opening == null) pin(project, tab) else opening.thenRun { pin(project, tab) }
        }
        if (state.reveals == revealsBefore) selected?.let { requestOpen(editors, it) }
    }

    // requestOpenFile returns void up to 2026.2 and a CompletableFuture from 2026.3, so a call compiled against the
    // 2025.2 floor names a method 2026.3 no longer has. Looked up by name, it resolves on both. ReflectionGuard's
    // production exceptions name this function and when it goes.
    private fun requestOpen(editors: FileEditorManager, file: VirtualFile): CompletableFuture<*>? =
        try {
            FileEditorManager::class.java.getMethod("requestOpenFile", VirtualFile::class.java).invoke(editors, file) as CompletableFuture<*>?
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private fun openTabs(project: Project): List<MarginFile> =
        FileEditorManager.getInstance(project).openFiles.filterIsInstance<MarginFile>()

    private fun isPinned(project: Project): Boolean {
        val tabs = openTabs(project)
        return FileEditorManagerEx.getInstanceEx(project).windows.any { window -> tabs.any { window.getComposite(it)?.isPinned == true } }
    }

    private fun shownState(project: Project): TabState = when {
        openTabs(project).isEmpty() -> TabState.CLOSED
        isPinned(project) -> TabState.PINNED
        else -> TabState.OPEN
    }

    private fun pin(project: Project, tab: MarginFile) =
        FileEditorManagerEx.getInstanceEx(project).windows
            .filter { it.isFileOpen(tab) }
            .forEach { it.setFilePinned(tab, true) }

    private fun deliver(project: Project, request: TabRequest) {
        val state = ProjectTabState.getInstance(project)
        state.countReveal()
        state.request(request)
        val tab = MarginFile.of(project)
        OpenFileDescriptor(project, tab).navigate(true)
        val pending = state.takeRequest() ?: return
        FileEditorManager.getInstance(project).getEditors(tab).filterIsInstance<MarginEditor>().forEach { it.handle(pending) }
    }
}
