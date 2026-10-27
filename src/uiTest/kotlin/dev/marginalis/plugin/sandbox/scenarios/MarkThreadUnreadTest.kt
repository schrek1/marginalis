package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * A thread is marked unread, and read again, from its context menu in the tool window (MARG-76).
 *
 * Three threads the user has read: A an agent question on a line, B an fyi on another line, P a project thread. The
 * robot reads them through the store, as an open thread panel does once on screen: the sandbox IDE is never the
 * active app, so its panels read nothing by themselves.
 */
class MarkThreadUnreadTest : SandboxScenario("mark_thread_unread") {

    private val ids = linkedMapOf<String, String>()

    @Test
    @Order(1)
    fun seedReadThreads() = case("seed_read_threads") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        ids["A"] = Agent.agentAddsLineThread(FILE, 1, "A · why is the retry capped at three?")
        ids["B"] = Agent.agentAddsLineThread(FILE, 5, "B · the CI job moved to the new runner", fields = mapOf("intent" to "fyi"))
        ids["P"] = Agent.agentStartsProjectThread("P · release notes for the next version")
        ids.values.forEach(SandboxUi::userReads)
        val signals = signalsUntil(mapOf("A" to "none", "B" to "none", "P" to "none"))
        check("seed: no thread shows ✉ once read", signals.values.all { it == "none" }, signals)
    }

    @Test
    @Order(2)
    fun aReadThreadOffersMarkAsUnread() = case("a_read_thread_offers_mark_as_unread") {
        val menu = ToolWindowUi.contextMenuOf(id("A"))
        check("menu: a read thread offers Mark as Unread", menu == listOf("Navigate", "Mark as Unread", "Resolve", "Delete…"), menu)
    }

    @Test
    @Order(3)
    fun markAsUnreadBringsTheEnvelopeBack() = case("mark_as_unread_brings_the_envelope_back") {
        ToolWindowUi.chooseFromContextMenu(id("A"), "Mark as Unread")
        val signal = signalsUntil(mapOf("A" to "you"))["A"]
        shot("1-a-marked-unread")
        check("tree: A shows ✉ again", signal == "you", signal)
        check("store: A's latest agent message is unread", unreadByUser(id("A")) == 1, unreadByUser(id("A")))
        val menu = ToolWindowUi.contextMenuOf(id("A"))
        check("menu: the entry now reads Mark as Read", menu == listOf("Navigate", "Mark as Read", "Resolve", "Delete…"), menu)
    }

    @Test
    @Order(4)
    fun markAsReadClearsItAgain() = case("mark_as_read_clears_it_again") {
        ToolWindowUi.chooseFromContextMenu(id("A"), "Mark as Read")
        val signal = signalsUntil(mapOf("A" to "none"))["A"]
        check("tree: A's ✉ is gone once marked read", signal == "none", signal)
        check("store: nothing in A is unread", unreadByUser(id("A")) == 0, unreadByUser(id("A")))
    }

    @Test
    @Order(5)
    fun severalSelectedThreadsAreMarkedTogether() = case("several_selected_threads_are_marked_together") {
        ToolWindowUi.selectThreads(id("A"), id("B"))
        val menu = ToolWindowUi.contextMenuOf(id("B"))
        check(
            "menu: two selected threads get one entry for both",
            menu == listOf("Mark as Unread", "Resolve 2 Open Thread(s)…", "Delete 2 Thread(s)…"),
            menu,
        )
        ToolWindowUi.selectThreads(id("A"), id("B"))
        ToolWindowUi.chooseFromContextMenu(id("B"), "Mark as Unread")
        val signals = signalsUntil(mapOf("A" to "you", "B" to "you"))
        shot("2-a-and-b-marked-unread")
        check("tree: A and B both show ✉", signals["A"] == "you" && signals["B"] == "you", signals)
        check("tree: P was not selected and stays read", signals["P"] == "none", signals)
    }

    @Test
    @Order(6)
    fun aProjectThreadMarkedUnreadShowsAsNewInTheMarginTab() = case("a_project_thread_marked_unread_shows_as_new_in_the_margin_tab") {
        ToolWindowUi.chooseFromContextMenu(id("P"), "Mark as Unread")
        SandboxUi.openMarginTab(id("P"))
        val card = readUntil("P's Margin tab card", read = { marginCard(id("P")) }) { it == "you dot" }
        shot("3-p-marked-unread-in-margin-tab")
        check("Margin tab: P's card shows ✉ and the unread dot", card == "you dot", card)
    }

    private fun id(name: String): String = ids[name] ?: error("thread $name was not seeded")

    private fun unreadByUser(threadId: String): Int =
        SandboxUi.js("String(store.getThreads().byId('$threadId').unreadByUserCount())").toInt()

    private fun signalsUntil(expected: Map<String, String>): Map<String, String> =
        readUntil("the tree's turn signals $expected", read = { treeSignals() }) { shown ->
            expected.all { (name, signal) -> shown[name] == signal }
        }

    /** Per seeded thread name, the turn signal after its mark on its first row in the tree: `you`, `agent` or `none`. */
    private fun treeSignals(): Map<String, String> {
        val raw = SandboxUi.obj(
            SandboxUi.js(
                ToolWindowUi.TREE + SIGNALS + """
                var tree = marginalisTree();
                var renderer = tree.getCellRenderer();
                var nodes = tree.getModel().getRoot().preorderEnumeration();
                var result = {};
                while (nodes.hasMoreElements()) {
                    var node = nodes.nextElement();
                    var thread = threadOfNode(node);
                    if (thread == null || result[String(thread.getId())] != null) continue;
                    var cell = renderer.getTreeCellRendererComponent(tree, node, false, true, true, 0, false);
                    var words = null;
                    var parts = cell.getComponents();
                    for (var i = 0; i < parts.length; i++) if (parts[i] instanceof com.intellij.ui.ColoredTreeCellRenderer) words = parts[i];
                    result[String(thread.getId())] = trailingSignal(words == null ? null : words.getIcon());
                }
                String(JSON.stringify(result))
                """,
            ),
        )
        return ids.mapValues { (_, threadId) -> raw.get(threadId)?.asString ?: "missing" }
    }

    /** The card's turn signal, followed by ` dot` while its unread dot shows. */
    private fun marginCard(threadId: String): String = SandboxUi.marginJs(
        SIGNALS + """
        var children = fieldOf(marginPanel(), 'stack').getComponents();
        var shown = 'no card';
        for (var i = 0; i < children.length; i++) {
            if (String(children[i].getClass().getSimpleName()) != 'ThreadCard') continue;
            if (String(children[i].getThread().getId()) != '$threadId') continue;
            shown = trailingSignal(fieldOf(children[i], 'markLabel').getIcon()) + (fieldOf(children[i], 'dot').isVisible() ? ' dot' : '');
        }
        String(shown)
        """,
    )

    private companion object {
        const val FILE = "src/review_target.py"

        const val SIGNALS = """
var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
var turns = pluginLoader.loadClass('dev.marginalis.core.Turn');
var yourMoveIcon = icons.turnSignal(turns.getField('USER_OWES').get(null));
var agentsMoveIcon = icons.turnSignal(turns.getField('AGENT_OWES').get(null));
function signalOf(icon) {
    if (icon === yourMoveIcon) return 'you';
    if (icon === agentsMoveIcon) return 'agent';
    if (icon instanceof com.intellij.util.ui.EmptyIcon) return 'none';
    return 'unknown:' + (icon == null ? 'null' : icon.getClass().getSimpleName());
}
function trailingSignal(rowIcon) {
    if (rowIcon == null) return 'unknown:no icon';
    if (!rowIcon.getIcon) return 'unknown:' + rowIcon.getClass().getSimpleName();
    return signalOf(rowIcon.getIcon(2));
}
"""
    }
}
