package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Messages wider than the thread panel stay readable to their end: a code block with long lines scrolls sideways in a
 * pane of its own, and long prose (a quote, a nested list item) wraps inside the panel.
 */
class LongMessagesFitTest : SandboxScenario("long_messages_fit") {

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun longCodeBlock() = case("long_code_block") {
        val thread = Agent.agentStartsProjectThread("Before.\n\n```sql\n$LONG_CODE_LINE\n```\n\nAfter.")
        val code = laidOut(thread) { fit -> fit.code != null }.code
        check(
            "a code block wider than the panel scrolls sideways in its own pane",
            code != null && code.inOwnPane && code.reachableWidth >= code.naturalWidth && code.shownWidth < code.naturalWidth && code.barVisible,
            code,
        )
        check("the code block ends inside the panel", code != null && code.right <= code.panelWidth, code)
        SandboxUi.screenshotThread(thread, shots.resolve("long-code-block.png"))
    }

    @Test
    @Order(2)
    fun longProse() = case("long_prose") {
        val thread = Agent.agentStartsProjectThread("> $LONG_SENTENCE\n\n- top item\n  - nested $LONG_SENTENCE")
        val prose = laidOut(thread) { fit -> fit.prose.isNotEmpty() }.prose
        check(
            "a long quote and a nested list item wrap inside the panel",
            prose.isNotEmpty() && prose.all { pane -> pane.textEnd <= pane.width && pane.right <= pane.panelWidth },
            prose,
        )
        SandboxUi.screenshotThread(thread, shots.resolve("long-prose.png"))
    }

    private data class CodeFit(
        val inOwnPane: Boolean,
        val naturalWidth: Int,
        val reachableWidth: Int,
        val shownWidth: Int,
        val barVisible: Boolean,
        val right: Int,
        val panelWidth: Int,
    )

    private data class ProseFit(val textEnd: Double, val width: Int, val right: Int, val panelWidth: Int)

    private data class Fit(val code: CodeFit?, val prose: List<ProseFit>)

    /** How the thread's code block and prose sit in its panel, once the panel has laid itself out. */
    private fun laidOut(thread: String, has: (Fit) -> Boolean): Fit =
        readUntil("the thread laid out in its panel", read = { SandboxUi.panelView(thread) to fitOf(thread) }) { (view, fit) ->
            view.valid && view.showing && view.height >= view.preferredHeight && has(fit)
        }.second

    private fun fitOf(thread: String): Fit {
        val raw = SandboxUi.obj(
            SandboxUi.js(
                """
                var panel = component;
                function rightOf(c) { return javax.swing.SwingUtilities.convertPoint(c, c.getWidth(), 0, panel).x; }
                var code = null;
                var prose = [];
                function collect(c) {
                    if (c instanceof com.intellij.ui.EditorTextField && code == null) {
                        var viewport = c.getParent() instanceof javax.swing.JViewport ? c.getParent() : null;
                        var scroll = viewport != null && viewport.getParent() instanceof javax.swing.JScrollPane ? viewport.getParent() : null;
                        code = {
                            inOwnPane: scroll != null,
                            naturalWidth: c.getPreferredSize().width,
                            reachableWidth: scroll != null ? viewport.getViewSize().width : c.getWidth(),
                            shownWidth: scroll != null ? viewport.getExtentSize().width : c.getWidth(),
                            barVisible: scroll != null && scroll.getHorizontalScrollBar().isVisible(),
                            right: rightOf(scroll != null ? scroll : c),
                            panelWidth: panel.getWidth()
                        };
                        return;
                    }
                    if (c instanceof javax.swing.JEditorPane && !c.isEditable()) {
                        var end = 0;
                        for (var i = 0; i < c.getDocument().getLength(); i++) {
                            var box = c.modelToView2D(i);
                            if (box != null && box.getMaxX() > end) end = box.getMaxX();
                        }
                        prose.push({ textEnd: end, width: c.getWidth(), right: rightOf(c), panelWidth: panel.getWidth() });
                    }
                    var children = c.getComponents ? c.getComponents() : [];
                    for (var j = 0; j < children.length; j++) collect(children[j]);
                }
                collect(panel);
                String(JSON.stringify({ code: code, prose: prose }))
                """,
                SandboxUi.threadPanel(thread),
            ),
        )
        val code = raw.get("code").takeUnless { it.isJsonNull }?.asJsonObject?.let { json ->
            CodeFit(
                inOwnPane = json.get("inOwnPane").asBoolean,
                naturalWidth = json.get("naturalWidth").asInt,
                reachableWidth = json.get("reachableWidth").asInt,
                shownWidth = json.get("shownWidth").asInt,
                barVisible = json.get("barVisible").asBoolean,
                right = json.get("right").asInt,
                panelWidth = json.get("panelWidth").asInt,
            )
        }
        val prose = raw.getAsJsonArray("prose").map { element ->
            val json = element.asJsonObject
            ProseFit(json.get("textEnd").asDouble, json.get("width").asInt, json.get("right").asInt, json.get("panelWidth").asInt)
        }
        return Fit(code, prose)
    }

    private companion object {
        // Both run well past the widest panel a sandbox lays out (the Margin tab spans the editor area).
        val LONG_SENTENCE = "Suggestion to consider: DEFAULT now() gives every existing order a link expiry nobody chose, " +
            "so the report reads wrong for every order created before this migration ran. ".repeat(3)
        val LONG_CODE_LINE = "WHEN EXISTS (SELECT 1 FROM shop_order_entry e WHERE e.payment_check_id = c.id) " +
            "AND e.created_at < now() ".repeat(20) + "THEN 'expired'"
    }
}
