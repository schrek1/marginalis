package dev.marginalis.plugin.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import javax.swing.Icon

class ThreadGutterIconRenderer(
    private val project: Project,
    /** Never empty. */
    private val threads: List<CommentThread>,
) : GutterIconRenderer() {

    private val live = threads.any(MarginalisStore.getInstance(project)::isLive)

    override fun getIcon(): Icon = MarginalisIcons.markOf(threads, live)

    override fun getTooltipText(): String {
        if (threads.size == 1) {
            val thread = threads.single()
            val first = thread.messages.firstOrNull() ?: return "Marginalis thread"
            val status = thread.status.kind.name.lowercase()
            return "<html><b>${StringUtil.escapeXmlEntities(first.speaker(MarginalisSettings.getInstance().people))}</b> · $status · ${thread.messages.size} message(s)<br/>" +
                "${preview(thread)}<br/><i>Click to open thread</i></html>"
        }
        val lines = threads.joinToString("<br/>") { thread ->
            "<b>${StringUtil.escapeXmlEntities(thread.messages.firstOrNull()?.speaker(MarginalisSettings.getInstance().people) ?: "?")}</b> · ${preview(thread)}"
        }
        return "<html><b>${threads.size} Marginalis threads</b><br/>$lines<br/><i>Click to choose</i></html>"
    }

    private fun preview(thread: CommentThread): String {
        val first = thread.messages.firstOrNull() ?: return ""
        return StringUtil.escapeXmlEntities(
            StringUtil.shortenTextWithEllipsis(MarkdownRenderer.previewText(first.body), 120, 0),
        )
    }

    override fun isNavigateAction(): Boolean = true

    override fun getClickAction(): AnAction = object : AnAction("Open Marginalis Thread") {
        override fun actionPerformed(e: AnActionEvent) {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            threads.singleOrNull()?.let {
                ThreadInlayManager.toggle(project, editor, it)
                return
            }
            ThreadChooserPopup.show(project, editor, threads)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ThreadGutterIconRenderer &&
            other.threads.map { it.id } == threads.map { it.id } &&
            other.threads.map { it.status.kind } == threads.map { it.status.kind } &&
            other.threads.map { it.unreadCount() } == threads.map { it.unreadCount() } &&
            other.live == live

    override fun hashCode(): Int = threads.first().id.hashCode()
}
