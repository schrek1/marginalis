package dev.marginalis.plugin.testing

import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.ui.ThreadPanel
import java.awt.Component
import java.awt.Font
import java.net.URI
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent
import javax.swing.event.PopupMenuEvent
import javax.swing.text.AttributeSet
import javax.swing.text.Element
import javax.swing.text.Position
import javax.swing.text.View
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

class RenderedBody private constructor(private val parts: List<JComponent>) {

    val prose: List<JEditorPane> get() = parts.map(::unwrapped).filterIsInstance<JEditorPane>()

    val codeBlocks: List<EditorTextField> get() = parts.filterIsInstance<EditorTextField>()

    val tables: List<JScrollPane> get() = parts.filterIsInstance<JScrollPane>()

    fun kinds(): List<String> = parts.map {
        when (it) {
            is JScrollPane -> "table"
            is JEditorPane -> "prose"
            else -> "code"
        }
    }

    fun lines(): List<String> = prose.flatMap { textOf(it).lines() }.map { it.trim() }.filter { it.isNotEmpty() }

    fun text(): String = prose.joinToString("\n") { textOf(it) }

    fun html(): String = prose.joinToString("\n") { it.text }

    /** The text of each block of the prose in order, an empty line as an empty block. */
    fun blocks(): List<String> = prose.flatMap { pane ->
        val document = pane.document as HTMLDocument
        val body = generateSequence<Element>(document.defaultRootElement) { element ->
            (0 until element.elementCount).map(element::getElement).firstOrNull { it.name == "body" }
        }.last()
        (0 until body.elementCount).map(body::getElement).map { block ->
            document.getText(block.startOffset, block.endOffset - block.startOffset).trim()
        }
    }

    private fun textOf(pane: JEditorPane): String = pane.document.getText(0, pane.document.length).trim()

    fun fontOf(words: String): Font {
        val (document, offset) = locate(words)
        val pane = paneWith(words)
        var view: View = pane.ui.getRootView(pane)
        view.setSize(maxOf(pane.width, 400).toFloat(), Short.MAX_VALUE.toFloat())
        while (view.viewCount > 0) {
            val index = view.getViewIndex(offset, Position.Bias.Forward)
            if (index < 0) break
            view = view.getView(index)
        }
        return document.styleSheet.getFont(view.attributes)
    }

    fun markedAs(words: String): Set<String> {
        val (document, offset) = locate(words)
        val attributes = document.getCharacterElement(offset).attributes
        return attributes.attributeNames.toList().filterIsInstance<HTML.Tag>().map { it.toString() }.toSet()
    }

    fun linkOn(words: String): String? {
        val (document, offset) = locate(words)
        val anchor = document.getCharacterElement(offset).attributes.getAttribute(HTML.Tag.A) as? AttributeSet
        return anchor?.getAttribute(HTML.Attribute.HREF) as? String
    }

    fun enclosingBlocks(words: String): List<String> {
        val (document, offset) = locate(words)
        return generateSequence<Element>(document.getCharacterElement(offset)) { it.parentElement }.map { it.name }.toList()
    }

    fun attributeOfBlock(words: String, block: String, attribute: HTML.Attribute): String? {
        val (document, offset) = locate(words)
        return generateSequence<Element>(document.getCharacterElement(offset)) { it.parentElement }
            .firstOrNull { it.name == block }
            ?.attributes?.getAttribute(attribute) as? String
    }

    fun pointAt(href: String) {
        val pane = prose.first { it.text.contains("href=\"$href\"") }
        pane.fireHyperlinkUpdate(HyperlinkEvent(pane, HyperlinkEvent.EventType.ENTERED, URI(href).toURL(), href))
        pane.fireHyperlinkUpdate(HyperlinkEvent(pane, HyperlinkEvent.EventType.EXITED, URI(href).toURL(), href))
    }

    fun contextMenu(words: String): Map<String, Boolean> {
        val pane = paneWith(words)
        val menu = pane.componentPopupMenu
        menu.popupMenuListeners.forEach { it.popupMenuWillBecomeVisible(PopupMenuEvent(menu)) }
        return menu.components.filterIsInstance<JMenuItem>().associate { it.text to it.isEnabled }
    }

    fun clickMenu(words: String, item: String) {
        val pane = paneWith(words)
        val menu = pane.componentPopupMenu
        menu.popupMenuListeners.forEach { it.popupMenuWillBecomeVisible(PopupMenuEvent(menu)) }
        menu.components.filterIsInstance<JMenuItem>().single { it.text == item }.doClick()
    }

    fun selection(words: String): String? = paneWith(words).selectedText

    fun select(words: String) {
        val pane = paneWith(words)
        val start = pane.document.getText(0, pane.document.length).indexOf(words)
        pane.select(start, start + words.length)
    }

    private fun paneWith(words: String): JEditorPane = prose.first { textOf(it).contains(words) }

    private fun locate(words: String): Pair<HTMLDocument, Int> {
        val pane = paneWith(words)
        val document = pane.document as HTMLDocument
        return document to document.getText(0, document.length).indexOf(words)
    }

    companion object {
        fun of(panel: ThreadPanel): RenderedBody {
            val bodies = UIUtil.uiTraverser(panel).filter(Box::class.java)
                .filter { box -> box.components.isNotEmpty() && box.components.map(::unwrapped).all { it is JEditorPane || it is EditorTextField || it is Box.Filler } }
                .toList()
            return RenderedBody(bodies.single().components.filter { unwrapped(it) is JEditorPane || it is EditorTextField }.map { it as JComponent })
        }

        private fun unwrapped(part: Component): Component = (part as? JScrollPane)?.viewport?.view ?: part
    }
}
