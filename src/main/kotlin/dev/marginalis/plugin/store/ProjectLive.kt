package dev.marginalis.plugin.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.plugin.settings.MarginalisSettings

@Service(Service.Level.PROJECT)
@State(name = "MarginalisProjectLive", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectLive : PersistentStateComponent<ProjectLive.State> {

    class State {
        var projectLive: Boolean? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val followsSettings: Boolean
        get() = state.projectLive == null

    val isOn: Boolean
        get() = state.projectLive ?: MarginalisSettings.getInstance().state.liveByDefault

    fun turn(on: Boolean) {
        state.projectLive = on
    }

    companion object {
        fun getInstance(project: Project): ProjectLive = project.service()
    }
}
