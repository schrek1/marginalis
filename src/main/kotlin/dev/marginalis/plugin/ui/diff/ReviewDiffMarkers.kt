package dev.marginalis.plugin.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import dev.marginalis.core.AnchorPolicy
import dev.marginalis.core.CommentThread
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.CoalescedEdtRunner
import dev.marginalis.plugin.ui.ThreadGutterIconRenderer
import dev.marginalis.plugin.ui.ShownThreads
import dev.marginalis.plugin.ui.ThreadInlayManager
import dev.marginalis.plugin.ui.WalkthroughNavigator
import java.util.WeakHashMap

/**
 * Tags every diff editor about a project file with the threads it shows, and paints gutter icons where the
 * diff holds a revision's copy of the text, which the markers on the file's own document never reach. A walkthrough
 * step that switched a review diff to this file ([ReviewDiffSteps]) opens here once the file's diff has its text.
 *
 * A review builds a new viewer for a file it shows again, on every refresh of its data as well; the threads open in
 * the file's last viewer open again in the new one.
 */
class ReviewDiffMarkers : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val sides = ReviewSides.of(viewer) ?: return
        val path = sides.content.highlightFile?.let { ProjectPaths.of(project, it) } ?: return
        DiffPainter(project, sides, path) { openThreadIds -> openWhenShownAgain(context)[path] = openThreadIds }.install()
        val editor = sides.editor
        val reopening = openWhenShownAgain(context).remove(path).orEmpty()
        if (editor != null && reopening.isNotEmpty()) {
            OnceTheTextLands(sides.viewer, editor) { reopen(project, editor, reopening) }.install()
        }
        ReviewDiffSteps.takePending(context, path)?.let { step ->
            if (editor == null) return WalkthroughNavigator.navigateTo(project, step.thread)
            OnceTheTextLands(sides.viewer, editor) {
                if (!WalkthroughNavigator.navigateWithin(editor, project, step.thread)) WalkthroughNavigator.navigateTo(project, step.thread)
            }.install()
        }
    }

    private fun reopen(project: Project, editor: EditorEx, threadIds: List<String>) {
        val store = MarginalisStore.getInstance(project)
        threadIds.mapNotNull(store.threads::byId)
            .filter { it.status !is ThreadStatus.Resolved }
            .forEach { ThreadInlayManager.open(project, editor, it) }
    }

    companion object {
        private val live = mutableSetOf<DiffPainter>()

        // Weak, as ReviewDiffSteps' pending steps: what a review remembers must not outlive it.
        private val openThreadsByFile = WeakHashMap<DiffContext, MutableMap<String, List<String>>>()

        private fun openWhenShownAgain(context: DiffContext): MutableMap<String, List<String>> =
            openThreadsByFile.getOrPut(context) { mutableMapOf() }

        /** Strips our highlighters and listeners from open diffs before a dynamic unload. */
        fun disposeAll() {
            live.toList().forEach { it.uninstall() }
            openThreadsByFile.clear()
        }
    }

    private class ReviewSides(
        val viewer: DiffViewerBase,
        val content: DocumentContent,
        val editor: EditorEx?,
        val baseEditor: EditorEx?,
        val toEditorLine: (Int) -> Int,
    ) {
        companion object {
            fun of(viewer: FrameDiffTool.DiffViewer): ReviewSides? = when (viewer) {
                is TwosideTextDiffViewer ->
                    ReviewSides(viewer, viewer.getContent(Side.RIGHT), viewer.getEditor(Side.RIGHT), viewer.getEditor(Side.LEFT)) { it }

                is UnifiedDiffViewer ->
                    ReviewSides(viewer, viewer.getContent(Side.RIGHT), viewer.editor, null) {
                        viewer.transferLineToOnesideStrict(Side.RIGHT, it)
                    }

                is OnesideTextDiffViewer ->
                    if (viewer.side == Side.RIGHT) ReviewSides(viewer, viewer.content, viewer.editor, null) { it }
                    else ReviewSides(viewer, viewer.content, null, viewer.editor) { it }

                else -> null
            }
        }
    }

    /** Runs [open] once, when the viewer first has its text: a panel needs the rows it anchors to. */
    private class OnceTheTextLands(
        private val viewer: DiffViewerBase,
        private val editor: EditorEx,
        private val open: () -> Unit,
    ) : DiffViewerListener() {

        private var opened = false

        fun install() = viewer.addListener(this)

        // The viewer is iterating its listeners right now: removing ourselves, or leaving the diff for a file tab,
        // changes that list mid-iteration and throws ConcurrentModificationException.
        override fun onAfterRediff() {
            if (opened) return
            opened = true
            ApplicationManager.getApplication().invokeLater {
                if (viewer.isDisposed || editor.isDisposed) return@invokeLater
                viewer.removeListener(this)
                open()
            }
        }
    }

    private class DiffPainter(
        private val project: Project,
        private val sides: ReviewSides,
        private val path: String,
        private val onClosing: (openThreadIds: List<String>) -> Unit,
    ) : DiffViewerListener() {

        private val store = MarginalisStore.getInstance(project)
        private val highlighters = mutableListOf<RangeHighlighter>()
        private val repaint = CoalescedEdtRunner(project) { paint() }
        private val marked = mutableListOf<EditorEx>()
        private var observer: AutoCloseable? = null
        private var liveWatch: Disposable? = null

        fun install() {
            sides.viewer.addListener(this)
            live += this
            val editor = sides.editor
            when {
                editor == null -> Unit
                !showsTheLocalFile() -> {
                    observer = store.threads.observe { thread -> if (thread.file == path) repaint.request() }
                    liveWatch = Disposer.newDisposable("Marginalis review diff live badges").also {
                        store.onLiveChange(it) { repaint.request() }
                    }
                    mark(editor, ShownThreads.at(path, ::editorLineOf))
                }
                !showsAProjectFile(editor) -> mark(editor, ShownThreads.at(path, ::localEditorLineOf))
            }
            sides.baseEditor?.takeIf { !showsAProjectFile(it) }?.let { mark(it, ShownThreads.none(path)) }
        }

        fun uninstall() {
            sides.viewer.removeListener(this)
            release()
        }

        private fun release() {
            live -= this
            observer?.close()
            observer = null
            liveWatch?.let(Disposer::dispose)
            liveWatch = null
            clear()
            marked.forEach { it.putUserData(ThreadInlayManager.SHOWN_THREADS, null) }
            marked.clear()
        }

        private fun mark(editor: EditorEx, shown: ShownThreads) {
            editor.putUserData(ThreadInlayManager.SHOWN_THREADS, shown)
            marked += editor
        }

        private fun showsTheLocalFile(): Boolean =
            sides.content.highlightFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) } === sides.content.document

        private fun showsAProjectFile(editor: EditorEx): Boolean =
            FileDocumentManager.getInstance().getFile(editor.document)?.let { ProjectPaths.of(project, it) } != null

        // A unified viewer rebuilds its document on every rediff, a side-by-side one has it only once the first diff lands.
        override fun onAfterRediff() = paint()

        // The viewer is iterating its listeners right now: removing ourselves here throws
        // ConcurrentModificationException and skips the listeners after us. The list dies with the viewer anyway.
        override fun onDispose() {
            sides.editor?.takeIf { !it.isDisposed }?.let { onClosing(ThreadInlayManager.openThreadIds(it)) }
            release()
        }

        private fun paint() {
            val editor = sides.editor ?: return
            if (observer == null || editor.isDisposed) return
            clear()
            store.threads.all()
                .filter { it.file == path && it.status !is ThreadStatus.Resolved }
                .mapNotNull { thread -> editorLineOf(thread)?.let { line -> line to thread } }
                .groupBy({ it.first }, { it.second })
                .forEach { (line, threads) ->
                    val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
                    highlighter.gutterIconRenderer = ThreadGutterIconRenderer(project, threads.sortedBy { it.createdAt })
                    highlighters += highlighter
                }
        }

        private fun clear() {
            val editor = sides.editor
            if (editor != null && !editor.isDisposed) {
                highlighters.filter { it.isValid }.forEach { editor.markupModel.removeHighlighter(it) }
            }
            highlighters.clear()
        }

        private fun localEditorLineOf(thread: CommentThread): Int? =
            store.syncLine(thread)?.let(sides.toEditorLine)?.takeIf { it >= 0 }

        private fun editorLineOf(thread: CommentThread): Int? {
            val document = sides.content.document
            if (document.lineCount == 0) return null
            val line = if (thread.isFileLevel) 0 else AnchorPolicy.findAnchor(
                lineCount = document.lineCount,
                lineTextAt = { candidate -> lineText(document, candidate) },
                nearLine = thread.line ?: 0,
                anchorText = thread.anchorText ?: "",
                segment = thread.segment,
            )?.line ?: return null
            return sides.toEditorLine(line).takeIf { it >= 0 }
        }

        private fun lineText(document: Document, line: Int): String =
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
}
