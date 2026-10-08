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
import com.intellij.ui.ColorUtil
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
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.html.EqualDelimiterTrimmingInlineTagProvider
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.text.DefaultCaret
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
        MarkdownParser(Flavour()).buildMarkdownTreeFromString(markdown).children
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

    // GFM writes strikethrough as <span class="user-del">, which Swing's
    // HTML 3.2 kit cannot draw; <s> it can.
    private class Flavour : GFMFlavourDescriptor() {
        override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> =
            super.createHtmlGeneratingProviders(linkMap, baseURI) +
                (GFMElementTypes.STRIKETHROUGH to EqualDelimiterTrimmingInlineTagProvider("s", GFMTokenTypes.TILDE)) +
                (MarkdownTokenTypes.EOL to TypedLineBreaks)
    }

    // Line breaks render as typed: a single Enter inside a paragraph is a new line (as in GitHub comments), and each
    // blank line beyond the first between two blocks is an empty line. CommonMark makes the first a space and drops
    // the rest.
    private object TypedLineBreaks : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
            visitor.consumeHtml(
                when {
                    node.isSoftBreak() -> "<br />\n"
                    node.isExtraBlankLine() -> "<p> </p>\n"
                    else -> HtmlGenerator.leafText(text, node)
                },
            )
        }

        private fun ASTNode.isSoftBreak(): Boolean {
            val inParagraph = generateSequence(parent) { it.parent }.any { it.type == MarkdownElementTypes.PARAGRAPH }
            // Two trailing spaces or a backslash already emitted their own <br>.
            val afterHardBreak = parent?.children?.let { it.getOrNull(it.indexOf(this) - 1) }?.type ==
                MarkdownTokenTypes.HARD_LINE_BREAK
            return inParagraph && !afterHardBreak
        }

        // Between top-level blocks one EOL ends a block and the next makes the first blank line.
        private fun ASTNode.isExtraBlankLine(): Boolean {
            val siblings = parent?.takeIf { it.type == MarkdownElementTypes.MARKDOWN_FILE }?.children ?: return false
            val at = siblings.indexOf(this)
            val endOfLinesBefore = siblings.subList(0, at).takeLastWhile { it.type == MarkdownTokenTypes.EOL }.size
            val betweenBlocks = siblings.subList(0, at).any { it.type != MarkdownTokenTypes.EOL } &&
                siblings.subList(at + 1, siblings.size).any { it.type != MarkdownTokenTypes.EOL }
            return endOfLinesBefore >= 2 && betweenBlocks
        }
    }

    // Swing CSS has no :first-child: the pane's first block gets a class so it sits flush with the message header.
    private fun markFirstBlock(html: String): String =
        Regex("""^\s*<(p|ul|ol)>""").replace(html) { "<${it.groupValues[1]} class=\"first\">" }

    private fun toHtml(markdown: String): String {
        val flavour = Flavour()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
            .removePrefix("<body>").removeSuffix("</body>")
            .let(HtmlSanitizer::sanitize)
            // Added after sanitizing, so a body still cannot set any attribute.
            .replace("<table>", "<table cellspacing=\"0\">")
            .let(Reference::linkify)
            .let(::markFirstBlock)
    }

    private fun htmlPane(project: Project, markdown: String, wrapWidth: Int): JComponent {
        val pane = editorPane(project, toHtml(markdown), HTMLEditorKitBuilder().withWordWrapViewFactory().build())
        // Sized to the target width first so preferred height reflects wrapping.
        pane.setSize(wrapWidth, Int.MAX_VALUE)
        pane.alignmentX = Component.LEFT_ALIGNMENT
        return pane
    }

    private fun tablePane(project: Project, markdown: String, wrapWidth: Int): JComponent {
        // Left to itself a JEditorPane in a viewport shrinks to the viewport's
        // width whenever the table can wrap, so it would never scroll.
        val pane = object : JEditorPane() {
            override fun getScrollableTracksViewportWidth() = false
        }
        val kit = HTMLEditorKitBuilder().build()
        // Swing sizes a table a little narrower than its cells' text and wraps
        // them anyway; scrolling sideways is what this pane is for.
        kit.styleSheet.addRule("th, td { white-space: nowrap; }")
        editorPane(project, toHtml(markdown), kit, pane)
        // An unsized pane reports the height of a narrower layout; measure at its own width.
        pane.setSize(pane.preferredSize.width, Int.MAX_VALUE)
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

    private fun editorPane(project: Project, html: String, kit: HTMLEditorKit, pane: JEditorPane = JEditorPane()): JEditorPane {
        // Default HTML heading sizes are document scale; a 2x h1 in a margin
        // panel towers over the code it annotates.
        val base = JBUI.Fonts.label().size
        kit.styleSheet.addRule("h1 { font-size: ${(base * 1.2f).toInt()}pt; margin: 6px 0 2px 0; }")
        kit.styleSheet.addRule("h2 { font-size: ${(base * 1.1f).toInt()}pt; margin: 5px 0 2px 0; }")
        kit.styleSheet.addRule("h3, h4, h5, h6 { font-size: ${base}pt; margin: 4px 0 2px 0; }")
        // Swing ignores a table's border attribute here; it draws per-cell CSS borders.
        val grid = ColorUtil.toHtmlColor(JBColor.border())
        kit.styleSheet.addRule("th, td { border-width: 1px; border-style: solid; border-color: $grid; padding: 3px 6px; }")
        kit.styleSheet.addRule("th { text-align: left; }")
        // The IDE's kit gives paragraphs and lists no vertical gap, so consecutive ones read as one block.
        kit.styleSheet.addRule("p { margin-top: 6px; }")
        // Swing does not collapse margins: the list's own bottom margin would add to the next paragraph's.
        kit.styleSheet.addRule("ul, ol { margin-top: 4px; margin-bottom: 0; }")
        kit.styleSheet.addRule("li { margin-top: 2px; }")
        kit.styleSheet.addRule("p.first, ul.first, ol.first { margin-top: 0; }")
        pane.editorKit = kit
        pane.isEditable = false
        pane.isOpaque = false
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.font = JBUI.Fonts.label()
        // A caret that follows the text scrolls it into view later, and that
        // scroll reaches the host editor: rebuilding a panel (e.g. on zoom)
        // would yank the code view to the end of the thread.
        (pane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
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
