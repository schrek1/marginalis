package dev.marginalis.plugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.ui.FileLevelThreads

class CommentOnFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && e.project != null &&
            FileDocumentManager.getInstance().getFile(editor.document) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val relPath = ProjectPaths.of(project, vFile) ?: return
        FileLevelThreads.draftIn(project, editor, relPath, AddCommentAction.captureSegment(editor))
    }
}
