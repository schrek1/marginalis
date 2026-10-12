package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.core.GuidedLayout

// Per project and kept out of VCS: how to read a walkthrough is a habit of this checkout, not of the team.
@Service(Service.Level.PROJECT)
@State(name = "MarginalisGuidedLayout", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GuidedLayoutSetting : PersistentStateComponent<GuidedLayoutSetting.State> {

    class State {
        var layout: String? = null
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var layout: GuidedLayout
        get() = GuidedLayout.fromStored(state.layout)
        set(value) {
            state.layout = value.stored
        }

    companion object {
        fun getInstance(project: Project): GuidedLayoutSetting = project.service()
    }
}
