package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.core.ProjectTabOrder

// Per project and kept out of VCS: how to read a project's threads is a habit of this checkout, not of the team.
@Service(Service.Level.PROJECT)
@State(name = "MarginalisProjectTabSort", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectTabSort : PersistentStateComponent<ProjectTabSort.State> {

    class State {
        var order: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var order: ProjectTabOrder
        get() = ProjectTabOrder.fromStored(state.order)
        set(value) {
            state.order = value.stored
        }

    companion object {
        fun getInstance(project: Project): ProjectTabSort = project.service()
    }
}
