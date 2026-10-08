package dev.marginalis.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import dev.marginalis.plugin.store.MarginalisStore

internal class LiveByDefaultAction :
    ToggleAction("Live by default", "Each Submit in any thread wakes the listening agent", AllIcons.Actions.Lightning),
    DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        return MarginalisStore.getInstance(project).liveByDefault
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        MarginalisStore.getInstance(project).setLiveByDefault(state)
    }
}
