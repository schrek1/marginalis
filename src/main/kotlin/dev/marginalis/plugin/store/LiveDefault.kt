package dev.marginalis.plugin.store

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.plugin.settings.MarginalisSettings

// Per project and kept out of VCS: whether to work live is a habit of this checkout, not of the team.
@Service(Service.Level.PROJECT)
@State(name = "MarginalisLiveDefault", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LiveDefault : PersistentStateComponent<LiveDefault.State> {

    class State {
        // Nullable so that `false` differs from the default and is written out too.
        var liveByDefault: Boolean? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    // The app-wide setting is copied once, so changing it later leaves existing projects alone.
    override fun noStateLoaded() {
        state.liveByDefault = MarginalisSettings.getInstance().state.liveByDefault
    }

    var enabled: Boolean
        get() = state.liveByDefault ?: MarginalisSettings.getInstance().state.liveByDefault
        set(value) {
            state.liveByDefault = value
        }

    companion object {
        fun getInstance(project: Project): LiveDefault = project.service()
    }
}
