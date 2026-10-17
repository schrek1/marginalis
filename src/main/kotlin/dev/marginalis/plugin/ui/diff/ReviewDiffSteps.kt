package dev.marginalis.plugin.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ReviewDiffFiles
import java.util.WeakHashMap

/**
 * Walkthrough steps in the other files of a pull/merge request review diff: the diff switches to the step's file
 * and the step opens there once the new file has loaded. EDT only.
 *
 * Only the platform's review diff processor can list and switch its files ([ReviewDiffFileList]). When that API is
 * absent or changed in the running IDE, every diff reads as one without a file list and steps open in a file tab.
 */
object ReviewDiffSteps {

    class PendingStep(val file: String, val thread: CommentThread, val revealing: Message?)

    private const val PROCESSOR = "com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor"
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
        // The class name first: a diff that is no review never loads the processor's module.
        if (viewer.javaClass.name != PROCESSOR) return false
        val files = withReviewDiffApi { ReviewDiffFileList.of(viewer) } ?: return false
        val index = ReviewDiffFiles.switchTarget(file, root, files.paths, files.shownIndex) ?: return false
        // The diff may build the new viewer before select() returns.
        pending[viewer.context] = PendingStep(file, thread, revealing)
        if (withReviewDiffApi { files.select(index) } != null) return true
        pending.remove(viewer.context)
        return false
    }

    /** The step waiting for [file] in this diff, removed from the wait; a text diff of another file drops it. */
    fun takePending(context: DiffContext, file: String): PendingStep? =
        pending.remove(context)?.takeIf { it.file == file }

    /**
     * @return null when the running IDE's review diff API is not the one [ReviewDiffFileList] was compiled against
     *   (a class or method gone or changed); the step then opens in a file tab
     */
    private fun <T : Any> withReviewDiffApi(call: () -> T?): T? = try {
        call()
    } catch (e: LinkageError) {
        LOG.warn("Review diff file list unavailable; walkthrough steps open in file tabs", e)
        null
    }
}
