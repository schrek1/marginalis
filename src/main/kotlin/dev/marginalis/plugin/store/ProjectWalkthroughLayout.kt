package dev.marginalis.plugin.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.core.WalkthroughLayout

@Service(Service.Level.PROJECT)
@State(name = "MarginalisWalkthroughLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectWalkthroughLayout : PersistentStateComponent<ProjectWalkthroughLayout.State> {

    class State {
        var layout: String? = null
        var groupConsecutiveSteps: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var layout: WalkthroughLayout
        get() = WalkthroughLayout.fromStored(state.layout)
        set(value) {
            state.layout = value.name
        }

    var groupsConsecutiveSteps: Boolean
        get() = state.groupConsecutiveSteps
        set(value) {
            state.groupConsecutiveSteps = value
        }

    companion object {
        fun getInstance(project: Project): ProjectWalkthroughLayout = project.service()
    }
}
