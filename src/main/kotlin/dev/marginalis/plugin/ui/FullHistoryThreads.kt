package dev.marginalis.plugin.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/** Threads the user opened to their full history, shared by the editor and the Margin tab; kept only while the IDE runs. */
@Service(Service.Level.PROJECT)
class FullHistoryThreads {
    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun showsFullHistory(threadId: String): Boolean = threadId in ids

    fun showFullHistory(threadId: String) {
        ids += threadId
    }

    fun shorten(threadId: String) {
        ids -= threadId
    }

    companion object {
        fun getInstance(project: Project): FullHistoryThreads = project.service()
    }
}
