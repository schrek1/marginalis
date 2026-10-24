package dev.marginalis.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import dev.marginalis.plugin.store.MarginalisStore

internal class ProjectLiveAction :
    ToggleAction("Make every open thread live", "Each Submit in any open thread wakes the listening agent", AllIcons.Actions.Lightning),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return MarginalisStore.getInstance(project).isProjectLive
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        MarginalisStore.getInstance(project).setProjectLive(state)
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        e.presentation.isEnabled = isSelected(e) || MarginalisStore.getInstance(project).handBack.waitingAgents.isNotEmpty()
        e.presentation.text = when {
            isSelected(e) -> "Live: each Submit in any open thread wakes the agent"
            e.presentation.isEnabled -> "Make every open thread live"
            else -> "No agent is listening — live needs an agent waiting"
        }
        e.presentation.description = if (e.presentation.isEnabled) templatePresentation.description else null
    }
}
