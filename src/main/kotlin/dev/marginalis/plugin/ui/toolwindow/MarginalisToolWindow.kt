package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowManager
import dev.marginalis.core.Opened

object MarginalisToolWindow {

    const val ID = "Marginalis"

    fun reveal(project: Project, target: Opened, focus: Boolean) = reveal(project, ThreadInFront.Found(target), focus)

    fun reveal(project: Project, inFront: ThreadInFront, focus: Boolean) {
        val listed = when (inFront) {
            is ThreadInFront.NotFound -> return say(project, inFront.why)
            is ThreadInFront.Found -> inFront
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        val reveal = Runnable {
            val panel = toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? MarginalisToolWindowPanel }
                ?: return@Runnable
            val revealed = panel.reveal(listed.target)
            val why = revealed.why.takeUnless { revealed == Revealed.FilterReset && listed.note != null }
            listOfNotNull(why, listed.note).joinToString(" ").takeIf { it.isNotEmpty() }?.let { say(project, it) }
        }
        when {
            focus -> toolWindow.activate(reveal)
            toolWindow.isVisible -> reveal.run()
            else -> toolWindow.show(reveal)
        }
    }

    private fun say(project: Project, text: String) =
        ToolWindowManager.getInstance(project).notifyByBalloon(ID, MessageType.INFO, StringUtil.escapeXmlEntities(text))
}
