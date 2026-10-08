package dev.marginalis.plugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import dev.marginalis.core.CodeFence
import dev.marginalis.core.CodeLink
import dev.marginalis.core.CodeFences
import dev.marginalis.core.HtmlSanitizer
import dev.marginalis.core.Parsed
import dev.marginalis.core.Reference
import dev.marginalis.core.Resolution
import dev.marginalis.plugin.store.MarginalisStore
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.html.HTMLEditorKit

/**
 * GitHub-flavoured, minus images and raw HTML: each extra construct carries
 * its own Swing sizing tax. A top-level table pays it in its own pane at
 * natural width, scrolling sideways, so the prose around it keeps wrapping.
 */
object MarkdownRenderer {

    private const val FENCE_PREVIEW_CHARS = 40
    private val WEB_PROTOCOLS = setOf("http", "https")

    fun render(project: Project, body: String, wrapWidth: Int): JComponent {
        val box = Box.createVerticalBox()
        var consumedUpTo = 0
        // An unclosed fence is still prose to CommonMark.
        for (fence in closedFences(body)) {
            val textBefore = body.substring(consumedUpTo, fence.start)
            if (textBefore.isNotBlank()) addProse(box, project, textBefore, wrapWidth)
            box.add(Box.createVerticalStrut(JBUI.scale(4)))
            box.add(codeBlock(project, fence.language, body.substring(fence.codeStart, fence.codeEnd).trimEnd('\n')))
            box.add(Box.createVerticalStrut(JBUI.scale(4)))
            consumedUpTo = fence.end
        }
        val remainder = body.substring(consumedUpTo)
        if (remainder.isNotBlank()) addProse(box, project, remainder, wrapWidth)
        return box
    }

    private fun addProse(box: JComponent, project: Project, markdown: String, wrapWidth: Int) {
        var consumedUpTo = 0
        for (table in topLevelTables(markdown)) {
            val textBefore = markdown.substring(consumedUpTo, table.first)
            if (textBefore.isNotBlank()) box.add(htmlPane(project, textBefore, wrapWidth))
            box.add(Box.createVerticalStrut(JBUI.scale(4)))
            box.add(tablePane(project, markdown.substring(table), wrapWidth))
            box.add(Box.createVerticalStrut(JBUI.scale(4)))
            consumedUpTo = table.last + 1
        }
        val remainder = markdown.substring(consumedUpTo)
        if (remainder.isNotBlank()) box.add(htmlPane(project, remainder, wrapWidth))
    }

    // A table nested in a list or quote stays inline and wraps with its prose.
    private fun topLevelTables(markdown: String): List<IntRange> =
        MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown).children
            .filter { it.type == GFMElementTypes.TABLE }
            .map { it.startOffset until it.endOffset }

    fun previewText(body: String): String {
        val flattened = StringBuilder()
        var consumedUpTo = 0
        for (fence in closedFences(body)) {
            flattened.append(body, consumedUpTo, fence.start)
                .append(' ').append(body.substring(fence.codeStart, fence.codeEnd).take(FENCE_PREVIEW_CHARS)).append(' ')
            consumedUpTo = fence.end
        }
        flattened.append(body, consumedUpTo, body.length)
        return flattened.toString()
            .replace(Regex("[`*_#>]"), "")
            .replace('\n', ' ')
            .trim()
    }

    private fun closedFences(body: String): List<CodeFence> = CodeFences.find(body).filter { it.closed }

    private fun toHtml(markdown: String): String {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
            .removePrefix("<body>").removeSuffix("</body>")
            .let(HtmlSanitizer::sanitize)
            .let(Reference::linkify)
    }

    private fun htmlPane(project: Project, markdown: String, wrapWidth: Int): JComponent {
        val pane = editorPane(project, toHtml(markdown), HTMLEditorKitBuilder().withWordWrapViewFactory().build())
        // Sized to the target width first so preferred height reflects wrapping.
        pane.setSize(wrapWidth, Int.MAX_VALUE)
        pane.alignmentX = Component.LEFT_ALIGNMENT
        return pane
    }

    private fun tablePane(project: Project, markdown: String, wrapWidth: Int): JComponent {
        // Attributes are added after sanitizing, so the body still cannot set any.
        val html = toHtml(markdown).replace("<table>", "<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        val kit = HTMLEditorKitBuilder().build()
        kit.styleSheet.addRule("th { text-align: left; }")
        val pane = editorPane(project, html, kit)
        val natural = pane.preferredSize
        val scroll = JBScrollPane(pane, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        scroll.border = JBUI.Borders.empty()
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        val scrollbar = if (natural.width > wrapWidth) scroll.horizontalScrollBar.preferredSize.height else 0
        val size = Dimension(minOf(natural.width, wrapWidth), natural.height + scrollbar)
        scroll.preferredSize = size
        scroll.maximumSize = size
        scroll.alignmentX = Component.LEFT_ALIGNMENT
        return scroll
    }

    private fun editorPane(project: Project, html: String, kit: HTMLEditorKit): JEditorPane {
        val pane = JEditorPane()
        // Default HTML heading sizes are document scale; a 2x h1 in a margin
        // panel towers over the code it annotates.
        val base = JBUI.Fonts.label().size
        kit.styleSheet.addRule("h1 { font-size: ${(base * 1.2f).toInt()}pt; margin: 6px 0 2px 0; }")
        kit.styleSheet.addRule("h2 { font-size: ${(base * 1.1f).toInt()}pt; margin: 5px 0 2px 0; }")
        kit.styleSheet.addRule("h3, h4, h5, h6 { font-size: ${base}pt; margin: 4px 0 2px 0; }")
        pane.editorKit = kit
        pane.isEditable = false
        pane.isOpaque = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.font = JBUI.Fonts.label()
        pane.text = "<html><body>$html</body></html>"
        pane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) activate(project, e)
        }
        // Swing installs no context menu on a JEditorPane.
        pane.componentPopupMenu = JPopupMenu().also { menu ->
            val copy = JMenuItem("Copy").apply { addActionListener { pane.copy() } }
            val selectAll = JMenuItem("Select All").apply { addActionListener { pane.selectAll() } }
            menu.add(copy)
            menu.add(selectAll)
            menu.addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                    copy.isEnabled = !pane.selectedText.isNullOrEmpty()
                }
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
                override fun popupMenuCanceled(e: PopupMenuEvent) {}
            })
        }
        return pane
    }

    private fun activate(project: Project, click: HyperlinkEvent) {
        val target = click.description ?: return
        val reference = Reference.parse(target)
        val url = click.url
        when {
            reference is Parsed.Ok -> reference.value?.let { follow(project, it, click) }
            reference is Parsed.Invalid && Reference.looksLike(target) -> warn(reference.reason, click)
            url != null && url.protocol.lowercase() in WEB_PROTOCOLS -> BrowserUtil.browse(url)
            url != null -> warn("Only http and https links open from the margin.", click)
            target.startsWith('#') -> Unit
            else -> when (val link = CodeLink.parse(target)) {
                is Parsed.Ok -> CodeLinkNavigator.navigateTo(project, link.value, onUnresolved = { warn(it, click) })
                is Parsed.Invalid -> warn(link.reason, click)
            }
        }
    }

    private fun follow(project: Project, reference: Reference, click: HyperlinkEvent) {
        val problem = when (val resolution = reference.resolveIn(MarginalisStore.getInstance(project).threads.all())) {
            is Resolution.Found -> return WalkthroughNavigator.navigateTo(project, resolution.referent.thread, revealing = resolution.referent.message)
            is Resolution.Ambiguous -> "${resolution.summary} in this project"
            Resolution.Unknown -> "$reference names no thread or message in this project"
        }
        warn(problem, click)
    }

    private fun warn(problem: String, click: HyperlinkEvent) {
        val source = click.source as JComponent
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(StringUtil.escapeXmlEntities(problem), MessageType.WARNING, null)
            .createBalloon()
            .show((click.inputEvent as? MouseEvent)?.let(::RelativePoint) ?: RelativePoint.getCenterOf(source), Balloon.Position.above)
    }

    private fun codeBlock(project: Project, language: String?, code: String): JComponent {
        val document = EditorFactory.getInstance().createDocument(code)
        val field = EditorTextField(document, project, CodeFenceFileTypes.of(language), true, false)
        field.addSettingsProvider { editor ->
            editor.installPopupHandler(
                ContextMenuPopupHandler.Simple(
                    DefaultActionGroup(
                        ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_COPY),
                        ActionManager.getInstance().getAction(IdeActions.ACTION_SELECT_ALL),
                    ),
                ),
            )
        }
        field.border = JBUI.Borders.customLine(JBColor.border(), 1)
        field.alignmentX = Component.LEFT_ALIGNMENT
        return field
    }
}
