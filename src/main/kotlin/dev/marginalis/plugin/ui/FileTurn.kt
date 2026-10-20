package dev.marginalis.plugin.ui

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.ui.VirtualFileAppearanceListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.Turn
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.store.MarginalisStore

object FileTurn {

    fun of(project: Project, file: VirtualFile): Turn? {
        val turns = MarginalisStore.getInstance(project).fileTurns
        if (turns.isEmpty()) return null
        val path = ProjectPaths.of(project, file) ?: return null
        return turns.of(path)
    }

    fun track(project: Project, path: String) {
        val store = MarginalisStore.getInstance(project)
        if (!store.fileTurns.track(path, store.threads.all())) return
        project.guessProjectDir()?.findFileByRelativePath(path)?.let { redraw(project, it) }
    }

    fun redrawAll(project: Project) {
        val base = project.guessProjectDir() ?: return
        MarginalisStore.getInstance(project).fileTurns.paths()
            .mapNotNull { base.findFileByRelativePath(it) }
            .forEach { redraw(project, it) }
    }

    // Project-view file icons sit in the platform's deferred-icon cache, which
    // only this topic (or a PSI/VFS change) clears — a turn change has neither.
    // updateFilePresentation is deliberately the base-class API:
    // FileEditorManagerEx's variant is 2026.1+ and breaks the 2025.2 floor.
    private fun redraw(project: Project, file: VirtualFile) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(VirtualFileAppearanceListener.TOPIC).virtualFileAppearanceChanged(file)
        FileEditorManager.getInstance(project).updateFilePresentation(file)
        ProjectView.getInstance(project).currentProjectViewPane?.updateFrom(file, false, false)
    }
}
