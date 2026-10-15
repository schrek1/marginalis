package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener

class ParkMarginTabOnClose : ProjectCloseListener {

    override fun projectClosingBeforeSave(project: Project) = ProjectTab.park(project)
}
