package dev.marginalis.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import dev.marginalis.core.AnchorPolicy
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Segment
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.ThreadChooserPopup
import dev.marginalis.plugin.ui.ThreadInlayManager

class AddCommentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        e.presentation.isEnabledAndVisible = editor != null && project != null &&
            FileDocumentManager.getInstance().getFile(editor.document) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relPath = ProjectPaths.of(project, vFile) ?: return

        val document = editor.document
        val segment = captureSegment(editor)
        val line = when {
            segment != null -> document.getLineNumber(editor.selectionModel.selectionStart)
            else -> editor.caretModel.logicalPosition.line.coerceIn(0, document.lineCount - 1)
        }

        // A selection always drafts: a span thread beside an existing one is
        // how a second point on the same line gets raised.
        if (segment == null) {
            val store = MarginalisStore.getInstance(project)
            val existing = store.threads.all().filter { thread ->
                thread.file == relPath &&
                    thread.status !is ThreadStatus.Resolved &&
                    store.markerOf(thread)?.isValid == true &&
                    store.syncLine(thread) == line
            }
            existing.singleOrNull()?.let {
                ThreadInlayManager.open(project, editor, it)
                return
            }
            if (existing.size > 1) {
                ThreadChooserPopup.show(project, editor, existing)
                return
            }
        }

        val anchorText = document.getText(
            TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)),
        )
        ThreadInlayManager.openDraft(project, editor, CommentThread(relPath, line, anchorText, segment = segment))
    }

    companion object {
        // Segments are line-scoped (re-found via their line), so a multi-line
        // selection clamps to its first line rather than degrading to a
        // whole-line thread.
        fun captureSegment(editor: Editor): Segment? {
            val selection = editor.selectionModel
            if (!selection.hasSelection()) return null
            val document = editor.document
            val start = selection.selectionStart
            val line = document.getLineNumber(start)
            val end = minOf(selection.selectionEnd, document.getLineEndOffset(line))
            val exact = document.getText(TextRange(start, end))
            if (exact.isBlank()) return null
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val prefixFrom = maxOf(lineStart, start - AnchorPolicy.SEGMENT_CONTEXT)
            val suffixTo = minOf(lineEnd, end + AnchorPolicy.SEGMENT_CONTEXT)
            return Segment(
                exact = exact,
                prefix = document.getText(TextRange(prefixFrom, start)),
                suffix = document.getText(TextRange(end, suffixTo)),
            )
        }
    }
}
