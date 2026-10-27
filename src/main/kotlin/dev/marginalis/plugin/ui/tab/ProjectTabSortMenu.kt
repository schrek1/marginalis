package dev.marginalis.plugin.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import dev.marginalis.core.ProjectTabOrder

internal class ProjectTabSortMenu(private val onChanged: () -> Unit) :
    DefaultActionGroup("Sort", "Choose how project threads are ordered", AllIcons.ObjectBrowser.Sorted), DumbAware {
    init {
        isPopup = true
        templatePresentation.isPerformGroup = false
        ProjectTabOrder.entries.forEach { order ->
            add(object : ToggleAction(order.label), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                override fun isSelected(e: AnActionEvent): Boolean =
                    e.project?.let { ProjectTabSort.getInstance(it).order == order } ?: false

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    val project = e.project ?: return
                    if (!state) return
                    ProjectTabSort.getInstance(project).order = order
                    onChanged()
                }
            })
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val order = e.project?.let { ProjectTabSort.getInstance(it).order } ?: return
        e.presentation.text = "Sort: ${order.label}"
    }
}
