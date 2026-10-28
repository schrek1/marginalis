package dev.marginalis.plugin.ui.toolwindow

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Opened
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.MarginalisDataKeys
import dev.marginalis.plugin.ui.ShownThreads
import dev.marginalis.plugin.ui.ThreadInlayManager
import dev.marginalis.plugin.ui.tab.MarginEditor
import javax.swing.SwingUtilities

sealed interface ThreadInFront {

    data class Found(val target: Opened, val note: String? = null) : ThreadInFront

    data class NotFound(val why: String) : ThreadInFront

    companion object {

        fun anyIn(project: Project, context: DataContext): Boolean =
            context.getData(MarginalisDataKeys.THREAD) != null ||
                context.getData(CommonDataKeys.EDITOR) != null ||
                FileEditorManager.getInstance(project).selectedEditor != null

        fun of(project: Project, context: DataContext): ThreadInFront? {
            if (!anyIn(project, context)) return null
            context.getData(MarginalisDataKeys.THREAD)?.let { return of(project, it) }
            context.getData(CommonDataKeys.EDITOR)?.let { editor -> inEditor(project, editor, outside = { null })?.let { return it } }
            return FileEditorManager.getInstance(project).selectedEditor?.let { inFileEditor(project, it) }
                ?: NotFound("Open a file to find its thread.")
        }

        fun of(project: Project, thread: CommentThread): ThreadInFront {
            if (!MarginalisStore.getInstance(project).isDraft(thread)) return Found(Opened.MarginThread(thread.id))
            return Found(thread.file?.let(Opened::File) ?: Opened.ProjectThreads, note = "Submit the draft to list it.")
        }

        fun of(project: Project, file: VirtualFile): ThreadInFront {
            val path = pathShowing(project, file)
            focusedEditorOn(project, file)?.let { editor -> inEditor(project, editor)?.let { return it } }
            val selected = FileEditorManager.getInstance(project).selectedEditor
            val inSelected = selected?.let { inFileEditor(project, it) }
            return when {
                inSelected != null && selected.file == file -> inSelected
                path != null && inSelected is Found && inSelected.isAbout(project, path) -> inSelected
                path != null -> Found(Opened.File(path))
                else -> outside(file)
            }
        }

        fun pathShowing(project: Project, file: VirtualFile): String? =
            ProjectPaths.of(project, file) ?: FileDocumentManager.getInstance().getCachedDocument(file)?.let { document ->
                EditorFactory.getInstance().getEditors(document).firstNotNullOfOrNull { it.getUserData(ThreadInlayManager.SHOWN_THREADS)?.file }
            }

        private fun inFileEditor(project: Project, fileEditor: FileEditor): ThreadInFront? = when (fileEditor) {
            is TextEditor -> inEditor(project, fileEditor.editor)
            is MarginEditor -> Found(Opened.ProjectThreads)
            else -> editorFocusedIn(fileEditor)?.let { inEditor(project, it) } ?: fileEditor.file?.let { file ->
                ProjectPaths.of(project, file)?.let { Found(Opened.File(it)) } ?: outside(file)
            }
        }

        private fun focusedEditorOn(project: Project, file: VirtualFile): Editor? {
            val owner = IdeFocusManager.getInstance(project).focusOwner ?: return null
            val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return null
            return EditorFactory.getInstance().getEditors(document, project)
                .firstOrNull { SwingUtilities.isDescendingFrom(owner, it.contentComponent) }
        }

        private fun editorFocusedIn(fileEditor: FileEditor): Editor? {
            val focus = fileEditor.preferredFocusedComponent ?: return null
            return DataManager.getInstance().getDataContext(focus).getData(CommonDataKeys.EDITOR)
        }

        private fun inEditor(
            project: Project,
            editor: Editor,
            outside: (VirtualFile) -> ThreadInFront? = Companion::outside,
        ): ThreadInFront? {
            val store = MarginalisStore.getInstance(project)
            val shown = editor.getUserData(ThreadInlayManager.SHOWN_THREADS) ?: run {
                val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
                val path = ProjectPaths.of(project, file) ?: return outside(file)
                ShownThreads.at(path, store::syncLine)
            }
            return Found(Opened.onLine(shown.file, editor.caretModel.logicalPosition.line, store.threads.all(), shown.lineOf))
        }

        private fun Found.isAbout(project: Project, path: String): Boolean = when (val opened = target) {
            is Opened.File -> opened.path == path
            is Opened.MarginThread -> MarginalisStore.getInstance(project).threads.byId(opened.id)?.file == path
            Opened.ProjectThreads -> false
        }

        private fun outside(file: VirtualFile) = NotFound("${file.name} is outside the project.")
    }
}
