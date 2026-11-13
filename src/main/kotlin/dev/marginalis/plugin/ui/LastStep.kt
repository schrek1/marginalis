package dev.marginalis.plugin.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.core.CommentThread
import dev.marginalis.core.StepMark

@Service(Service.Level.PROJECT)
class LastStep {
    var mark: StepMark? = null
        private set

    fun landedOn(thread: CommentThread) {
        StepMark.of(thread)?.let { mark = it }
    }

    fun returnTo(previous: StepMark?) {
        mark = previous
    }

    companion object {
        fun getInstance(project: Project): LastStep = project.service()
    }
}
