package dev.marginalis.plugin.ui.toolwindow

import com.intellij.ide.SelectInContext
import com.intellij.ide.SelectInTarget
import com.intellij.openapi.project.DumbAware
import dev.marginalis.core.Opened
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.ThreadSelectInContext
import dev.marginalis.plugin.ui.tab.MarginFile

class MarginalisSelectInTarget : SelectInTarget, DumbAware {

    override fun toString(): String = MarginalisToolWindow.ID

    override fun getToolWindowId(): String = MarginalisToolWindow.ID

    override fun getWeight(): Float = BELOW_THE_PLATFORMS_TARGETS

    override fun canSelect(context: SelectInContext): Boolean {
        if (context is ThreadSelectInContext) return true
        val threads = MarginalisStore.getInstance(context.project).threads.all()
        if (context.virtualFile is MarginFile) return Opened.ProjectThreads.isListedIn(threads)
        val path = ThreadInFront.pathShowing(context.project, context.virtualFile) ?: return false
        return Opened.File(path).isListedIn(threads)
    }

    override fun selectIn(context: SelectInContext, requestFocus: Boolean) {
        val project = context.project
        val inFront = (context as? ThreadSelectInContext)?.let { ThreadInFront.of(project, it.thread) }
            ?: ThreadInFront.of(project, context.virtualFile)
        MarginalisToolWindow.reveal(project, inFront, focus = requestFocus)
    }

    private companion object {
        const val BELOW_THE_PLATFORMS_TARGETS = 100f
    }
}
