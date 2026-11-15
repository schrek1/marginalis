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
import java.util.WeakHashMap

/**
 * Tags every diff editor about a project file with the threads it shows, and paints gutter icons where the
 * diff holds a revision's copy of the text, which the markers on the file's own document never reach.
 *
 * A diff builds a new viewer for a file it shows again, and a review does so on every refresh of its data; the new
 * viewer keeps the caret and scroll but not our panels, so the threads open in the file's last viewer open again in it.
 */
class ReviewDiffMarkers : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val sides = ReviewSides.of(viewer) ?: return
        val path = sides.content.highlightFile?.let { ProjectPaths.of(project, it) } ?: return
        val openWhenShownAgain = openThreadsByFile.getOrPut(context) { mutableMapOf() }
        val reopening = openWhenShownAgain.remove(path).orEmpty()
        val arriving = ReviewDiffSteps.arrivingIn(context, path, canShowIt = sides.editor != null)
        DiffPainter(project, sides, path, reopening, arriving) { openThreadIds ->
            if (openThreadIds.isEmpty()) openWhenShownAgain.remove(path) else openWhenShownAgain[path] = openThreadIds
        }.install()
    }

    companion object {
        private val live = mutableSetOf<DiffPainter>()

        private val openThreadsByFile = WeakHashMap<DiffContext, MutableMap<String, List<String>>>()

        /** Strips our highlighters and listeners from open diffs before a dynamic unload. */
        fun disposeAll() {
            live.toList().forEach { it.uninstall() }
            openThreadsByFile.clear()
            ReviewDiffSteps.disposeAll()
        }

        fun forgetOpen(context: DiffContext, path: String, threadId: String) {
            openThreadsByFile[context]?.computeIfPresent(path) { _, threadIds -> (threadIds - threadId).ifEmpty { null } }
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

    private class DiffPainter(
        private val project: Project,
        private val sides: ReviewSides,
        private val path: String,
        private var reopening: List<String>,
        private var arriving: ReviewDiffSteps.ArrivingStep?,
        private val onClosing: (openThreadIds: List<String>) -> Unit,
    ) : DiffViewerListener() {

        private val store = MarginalisStore.getInstance(project)
        private val highlighters = mutableListOf<RangeHighlighter>()
        private val repaint = CoalescedEdtRunner(project) { paint() }
        private val marked = mutableListOf<EditorEx>()
        private var observer: AutoCloseable? = null
        private var liveWatch: Disposable? = null
        private var released = false

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
            released = true
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
        override fun onAfterRediff() {
            paint()
            if (reopening.isNotEmpty() || arriving != null) reopenLater()
        }

        // The viewer is iterating its listeners right now: removing ourselves here throws
        // ConcurrentModificationException and skips the listeners after us. The list dies with the viewer anyway.
        override fun onDispose() {
            val open = sides.editor?.takeIf { !it.isDisposed }?.let(ThreadInlayManager::openThreadIds).orEmpty()
            onClosing((reopening + open).distinct())
            arriving?.stillArriving()
            arriving = null
            release()
        }

        private fun reopenLater() {
            val editor = sides.editor ?: return
            ApplicationManager.getApplication().invokeLater({
                val threadIds = reopening - arriving?.threadsLeftBehind().orEmpty().toSet()
                reopening = emptyList()
                threadIds.mapNotNull(store.threads::byId)
                    .filter { it.status !is ThreadStatus.Resolved }
                    .forEach { ThreadInlayManager.restore(project, editor, it) }
                arriving?.let { step ->
                    arriving = null
                    step.landIn(editor)
                }
            }, { released || editor.isDisposed })
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
