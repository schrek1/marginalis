package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.marginalis.core.CommentThread
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ProjectTabState {

    private val pending = AtomicReference<TabRequest?>()

    fun request(request: TabRequest) = pending.set(request)

    fun takeRequest(): TabRequest? = pending.getAndSet(null)

    var draft: CommentThread? = null
        private set

    fun ensureDraft() {
        if (draft == null) draft = CommentThread(file = null, line = null, anchorText = null)
    }

    fun clearDraft(thread: CommentThread) {
        if (draft?.id == thread.id) draft = null
    }

    companion object {
        fun getInstance(project: Project): ProjectTabState = project.service()
    }
}
