package dev.marginalis.plugin.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.openapi.ListSelection
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ReviewDiffFiles
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Walkthrough steps in the other files of a pull/merge request review diff: the diff switches to the step's file
 * and the step opens there once the new file has loaded. EDT only.
 *
 * Only the platform's review diff processor (behind both the GitHub and GitLab reviews) can list and switch its
 * files. It is Kotlin-`internal` to the platform, so it is reached by reflection; when it is absent or changed,
 * every diff reads as one without a file list and steps open in a file tab.
 */
object ReviewDiffSteps {

    class PendingStep(val file: String, val thread: CommentThread, val revealing: Message?)

    private const val PROCESSOR = "com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor"
    private const val NAVIGATOR = "$PROCESSOR\$Navigator"
    private const val PRESENTABLE_CHANGE = "com.intellij.openapi.vcs.changes.ui.PresentableChange"
    private val LOG = logger<ReviewDiffSteps>()

    // Weak, and ours rather than the context's user data: a pending step must not outlive the diff or pin the plugin.
    private val pending = WeakHashMap<DiffContext, PendingStep>()

    /**
     * @param from the editor the step is moving from
     * @param file the step's project-relative file
     * @return false when [from] is not in a review diff or the diff does not list [file]; nothing was changed then
     */
    fun switchTo(project: Project, from: Editor, file: String, thread: CommentThread, revealing: Message?): Boolean {
        val viewer = FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<DiffEditorViewerFileEditor>()
            .firstOrNull { from in it.embeddedEditors }
            ?.editorViewer ?: return false
        val root = project.guessProjectDir()?.path ?: return false
        val files = FileList.of(viewer) ?: return false
        val index = ReviewDiffFiles.switchTarget(file, root, files.paths, files.shownIndex) ?: return false
        // The diff may build the new viewer before select() returns.
        pending[viewer.context] = PendingStep(file, thread, revealing)
        if (files.select(index)) return true
        pending.remove(viewer.context)
        return false
    }

    /** The step waiting for [file] in this diff, removed from the wait; a text diff of another file drops it. */
    fun takePending(context: DiffContext, file: String): PendingStep? =
        pending.remove(context)?.takeIf { it.file == file }

    /** A review diff's `Navigator`: `currentList`, `getChangePresentation(c).filePath`, `select(c)`. */
    private class FileList(
        private val navigator: Any,
        private val selectMethod: Method,
        private val changes: List<Any?>,
        val paths: List<String>,
        val shownIndex: Int,
    ) {
        fun select(index: Int): Boolean = reflectively {
            selectMethod.invoke(navigator, changes[index])
            true
        } ?: false

        companion object {
            fun of(viewer: DiffEditorViewer): FileList? {
                if (viewer.javaClass.name != PROCESSOR) return null
                return reflectively {
                    val loader = viewer.javaClass.classLoader
                    val navigatorType = Class.forName(NAVIGATOR, false, loader)
                    val presentation = navigatorType.getMethod("getChangePresentation", Any::class.java)
                    val filePath = Class.forName(PRESENTABLE_CHANGE, false, loader).getMethod("getFilePath")
                    val navigator = viewer.javaClass.getMethod("getNavigator").invoke(viewer)
                    val selection = navigatorType.getMethod("getCurrentList").invoke(navigator) as ListSelection<*>
                    val changes: List<Any?> = selection.list
                    FileList(
                        navigator = navigator,
                        selectMethod = navigatorType.getMethod("select", Any::class.java),
                        changes = changes,
                        paths = changes.map { change ->
                            (filePath.invoke(presentation.invoke(navigator, change)) as FilePath).path
                        },
                        shownIndex = selection.selectedIndex,
                    )
                }
            }
        }
    }

    /** @return null when the processor's API is not what this code expects (or the call itself failed) */
    private fun <T : Any> reflectively(call: () -> T): T? = try {
        call()
    } catch (e: ReflectiveOperationException) {
        LOG.warn("Review diff file list unavailable; walkthrough steps open in file tabs", e)
        null
    } catch (e: ClassCastException) {
        LOG.warn("Review diff file list unavailable; walkthrough steps open in file tabs", e)
        null
    }
}
