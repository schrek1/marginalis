package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.store.MarginalisPersistence
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.tab.MarginFile
import java.nio.file.Files
import java.nio.file.Path

class SecondProject private constructor(
    val root: Path,
    val project: Project,
    private val base: Path,
    private val lifetime: Disposable,
) : AutoCloseable {

    private var projectClosed = false

    private var folderHandedOn = false

    val name: String get() = project.name

    val path: String get() = project.guessProjectDir()!!.path

    val savedThreads: Path get() = root.resolve(".idea/marginalis.json")

    fun reopen(whileClosed: (Path) -> Unit = {}): SecondProject {
        closeProject()
        whileClosed(root)
        return openAt(base, root).also { folderHandedOn = true }
    }

    fun closeProject() {
        if (projectClosed) return
        projectClosed = true
        try {
            if (!project.isDisposed) {
                FileDocumentManager.getInstance().saveAllDocuments()
                PlatformTestUtil.forceCloseProjectWithoutSaving(project)
            }
        } finally {
            Disposer.dispose(lifetime)
        }
    }

    override fun close() {
        try {
            closeProject()
        } finally {
            if (!folderHandedOn) NioFiles.deleteRecursively(base)
        }
    }

    companion object {
        fun open(projectPath: String, files: Map<String, String> = emptyMap()): SecondProject {
            val base = Files.createTempDirectory("marginalis-second").toRealPath()
            val root = base.resolve(projectPath)
            Files.createDirectories(root)
            files.forEach { (path, text) ->
                val file = base.resolve(path)
                Files.createDirectories(file.parent)
                Files.writeString(file, text)
            }
            return try {
                openAt(base, root)
            } catch (e: Throwable) {
                NioFiles.deleteRecursively(base)
                throw e
            }
        }

        private fun openAt(base: Path, root: Path): SecondProject {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
            val lifetime = Disposer.newDisposable("second project")
            val project = try {
                PlatformTestUtil.loadAndOpenProject(root, lifetime)
            } catch (e: Throwable) {
                Disposer.dispose(lifetime)
                throw e
            }
            val persisted = MarginalisPersistence.load(project).threads.size
            PlatformTestUtil.waitWithEventsDispatching(
                "the margin never loaded",
                { FileEditorManager.getInstance(project).openFiles.any { it is MarginFile } && MarginalisStore.getInstance(project).threads.all().size == persisted },
                30,
            )
            return SecondProject(root, project, base, lifetime)
        }
    }
}
