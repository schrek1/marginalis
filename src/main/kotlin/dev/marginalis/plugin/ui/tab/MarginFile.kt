package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

// VirtualFileWithoutContent: editor restore skips preloading content, and no
// Document is ever created for it.
class MarginFile private constructor(@Volatile private var projectKey: String?) : LightVirtualFile("Margin"), VirtualFileWithoutContent {

    init {
        isWritable = false
    }

    override fun getFileSystem(): VirtualFileSystem = detachedFileSystem

    override fun getPath(): String = projectKey?.let { "$PATH/$it" } ?: PATH

    class ProjectClosed : ProjectCloseListener {
        override fun projectClosed(project: Project) {
            tabsByProject.remove(project.locationHash)?.isValid = false
        }
    }

    class ListedOnceInRecentFiles : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            if (file !is MarginFile) return
            val history = EditorHistoryManager.getInstance(source.project)
            history.fileList.filterIsInstance<MarginFile>().dropLast(1).forEach(history::removeFile)
        }
    }

    companion object {
        private const val PATH = "margin"

        private val tabsByProject = ConcurrentHashMap<String, MarginFile>()

        @Volatile
        private var withdrawn = false

        fun of(project: Project): MarginFile {
            val own = tabsByProject[project.locationHash]
            if (own != null && isShownIn(project, own)) return own
            val restored = FileEditorManager.getInstance(project).openFiles.filterIsInstance<MarginFile>().firstOrNull()
            if (restored != null) return adopt(project, restored)
            return own ?: tabsByProject.computeIfAbsent(project.locationHash) { MarginFile(it) }
        }

        fun adopt(project: Project, tab: MarginFile): MarginFile {
            val key = project.locationHash
            val own = tabsByProject[key]
            if (own === tab || own != null && isShownIn(project, own)) return tab
            tab.projectKey?.let { tabsByProject.remove(it, tab) }
            tab.projectKey = key
            tabsByProject[key] = tab
            return tab
        }

        internal fun atPath(path: String): MarginFile? {
            if (withdrawn) return null
            if (path == PATH) return MarginFile(null)
            val key = path.removePrefix("$PATH/").takeIf { it != path && it.isNotEmpty() && '/' !in it } ?: return null
            val registered = tabsByProject.computeIfAbsent(key) { MarginFile(it) }
            return if (isShownInAnyProject(registered)) MarginFile(key) else registered
        }

        fun withdraw() {
            withdrawn = true
            tabsByProject.values.forEach { it.isValid = false }
            tabsByProject.clear()
        }

        @TestOnly
        internal fun reinstate() {
            withdrawn = false
        }

        private fun isShownIn(project: Project, tab: MarginFile): Boolean = FileEditorManager.getInstance(project).isFileOpen(tab)

        private fun isShownInAnyProject(tab: MarginFile): Boolean =
            ProjectManager.getInstance().openProjects.any { !it.isDisposed && isShownIn(it, tab) }
    }
}

private val detachedFileSystem = MarginFileSystem()
