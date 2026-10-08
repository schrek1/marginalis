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
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import dev.marginalis.core.AnchorPolicy
import dev.marginalis.core.CommentThread
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.CoalescedEdtRunner
import dev.marginalis.plugin.ui.ThreadGutterIconRenderer
import dev.marginalis.plugin.ui.ThreadInlayManager

/**
 * Gutter icons in diffs whose right side is a project file shown from a
 * revision (a pull/merge request review, a commit) — those editors hold a
 * copy of the text, so the markers on the file's own document never reach them.
 *
 * Icons are editor-local and re-anchored on the diff's text; a thread whose
 * anchor isn't there gets no icon. EDT only.
 */
class ReviewDiffMarkers : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val project = context.project ?: return
        val target = RightSide.of(viewer) ?: return
        val file = projectPath(project, target.content) ?: return
        val local = target.content.highlightFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        if (target.editor.document === local) return
        DiffPainter(project, target, file).install()
    }

    private fun projectPath(project: Project, content: DocumentContent): String? {
        val file = content.highlightFile ?: return null
        val base = project.guessProjectDir() ?: return null
        return VfsUtilCore.getRelativePath(file, base)
    }

    companion object {
        private val live = mutableSetOf<DiffPainter>()

        /** Strips our highlighters and listeners from open diffs before a dynamic unload. */
        fun disposeAll() {
            live.toList().forEach { it.uninstall() }
        }
    }

    /** The PR/after side: its text, the editor showing it, and how its lines map into that editor. */
    private class RightSide(
        val viewer: DiffViewerBase,
        val content: DocumentContent,
        val editor: EditorEx,
        val toEditorLine: (Int) -> Int,
    ) {
        companion object {
            fun of(viewer: FrameDiffTool.DiffViewer): RightSide? = when (viewer) {
                is TwosideTextDiffViewer ->
                    RightSide(viewer, viewer.getContent(Side.RIGHT), viewer.getEditor(Side.RIGHT)) { it }

                is UnifiedDiffViewer ->
                    RightSide(viewer, viewer.getContent(Side.RIGHT), viewer.editor) {
                        viewer.transferLineFromOnesideStrict(Side.RIGHT, it)
                    }

                // A file the change adds: the diff shows only its new text.
                is OnesideTextDiffViewer ->
                    if (viewer.side == Side.RIGHT) RightSide(viewer, viewer.content, viewer.editor) { it } else null

                else -> null
            }
        }
    }

    private class DiffPainter(
        private val project: Project,
        private val target: RightSide,
        private val file: String,
    ) : DiffViewerListener() {

        private val store = MarginalisStore.getInstance(project)
        private val highlighters = mutableListOf<RangeHighlighter>()
        private val repaint = CoalescedEdtRunner(project) { paint() }
        private var observer: AutoCloseable? = null

        fun install() {
            target.viewer.addListener(this)
            observer = store.threads.observe { thread -> if (thread.file == file) repaint.request() }
            target.editor.putUserData(ThreadInlayManager.THREAD_LINE, ::editorLineOf)
            live += this
        }

        fun uninstall() {
            live -= this
            target.viewer.removeListener(this)
            observer?.close()
            observer = null
            clear()
            target.editor.putUserData(ThreadInlayManager.THREAD_LINE, null)
        }

        // A unified viewer rebuilds its document on every rediff, a side-by-side one has it only once the first diff lands.
        override fun onAfterRediff() = paint()

        override fun onDispose() = uninstall()

        private fun paint() {
            if (observer == null || target.editor.isDisposed) return
            clear()
            store.threads.all()
                .filter { it.file == file && it.status is ThreadStatus.Open }
                .mapNotNull { thread -> editorLineOf(thread)?.let { line -> line to thread } }
                .groupBy({ it.first }, { it.second })
                .forEach { (line, threads) ->
                    val highlighter = target.editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
                    highlighter.gutterIconRenderer = ThreadGutterIconRenderer(project, threads.sortedBy { it.createdAt })
                    highlighters += highlighter
                }
        }

        private fun clear() {
            if (!target.editor.isDisposed) {
                highlighters.filter { it.isValid }.forEach { target.editor.markupModel.removeHighlighter(it) }
            }
            highlighters.clear()
        }

        private fun editorLineOf(thread: CommentThread): Int? {
            val document = target.content.document
            if (document.lineCount == 0) return null
            val line = if (thread.isFileLevel) 0 else AnchorPolicy.findAnchor(
                lineCount = document.lineCount,
                lineTextAt = { candidate -> lineText(document, candidate) },
                nearLine = thread.line ?: 0,
                anchorText = thread.anchorText ?: "",
                segment = thread.segment,
            )?.line ?: return null
            return target.toEditorLine(line).takeIf { it >= 0 }
        }

        private fun lineText(document: Document, line: Int): String =
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    }
}
