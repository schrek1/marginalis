package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class SelectOpenedThreadAction : DumbAwareAction() {

    companion object {
        const val ID = "marginalis.selectOpenedThread"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ThreadInFront.anyIn(project, e.dataContext)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val inFront = ThreadInFront.of(project, e.dataContext) ?: return
        MarginalisToolWindow.reveal(project, inFront, focus = false)
    }
}
