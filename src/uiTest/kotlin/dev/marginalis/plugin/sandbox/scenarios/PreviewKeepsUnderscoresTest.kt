package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * A thread's one-line preview keeps the underscores of names and drops only Markdown's markers (MARG-217), in the
 * three places it shows: the tool window tree, the agent's notification balloon and the Margin tab card's
 * last-message line.
 *
 * A on a line names a function in a code span, B on another line a plain snake_case name next to real emphasis, P a
 * project thread whose reply arrives while the Margin tab is closed, so the IDE announces it in a balloon.
 */
class PreviewKeepsUnderscoresTest : SandboxScenario("preview_keeps_underscores") {

    private val ids = linkedMapOf<String, String>()

    @Test
    @Order(1)
    fun treeRowsKeepTheUnderscores() = case("tree_rows_keep_the_underscores") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        ids["A"] = Agent.agentAddsLineThread(FILE, 1, A_BODY)
        ids["B"] = Agent.agentAddsLineThread(FILE, 5, B_BODY)
        val rows = readUntil("the tree rows of A and B", read = { treePreviews() }) { shown ->
            shown["A"] != "missing" && shown["B"] != "missing"
        }
        shot("1-tree")
        check("tree: A keeps the underscores of its code span", rows["A"] == "legacy_helper has no callers left.", rows["A"])
        check(
            "tree: B keeps a plain snake_case name and drops the emphasis markers",
            rows["B"] == "Rename old_total_price, then drop this and that.",
            rows["B"],
        )
    }

    @Test
    @Order(2)
    fun theBalloonKeepsTheUnderscores() = case("the_balloon_keeps_the_underscores") {
        ids["P"] = Agent.agentStartsProjectThread("Release checklist")
        SandboxUi.userReads(id("P"))
        clearBalloons()
        Agent.agentReplies(id("P"), P_REPLY)
        val balloon = readUntil("a balloon for P's reply", read = { balloonTexts() }) { shown -> shown.isNotEmpty() }
        shot("2-balloon")
        check("balloon: the reply keeps its underscores", balloon.contains(P_REPLY_PREVIEW), balloon)
    }

    @Test
    @Order(3)
    fun theMarginTabCardKeepsTheUnderscores() = case("the_margin_tab_card_keeps_the_underscores") {
        SandboxUi.openMarginTab(id("P"))
        val line = readUntil("P's Margin tab card", read = { marginCardLastLine(id("P")) }) { it.endsWith(P_REPLY_PREVIEW) }
        shot("3-margin-tab-card")
        check("Margin tab: the card's last-message line keeps its underscores", line.endsWith(": $P_REPLY_PREVIEW"), line)
    }

    private fun id(name: String): String = ids[name] ?: error("thread $name was not seeded")

    /** Per seeded thread name, the preview text on its first row in the tree, after its `L<n>` / `project` label. */
    private fun treePreviews(): Map<String, String> {
        val raw = SandboxUi.obj(
            SandboxUi.js(
                ToolWindowUi.TREE + """
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
                    if (words == null) continue;
                    var fragments = words.iterator();
                    var last = '';
                    while (fragments.hasNext()) { fragments.next(); last = String(fragments.getFragment()); }
                    result[String(thread.getId())] = last;
                }
                String(JSON.stringify(result))
                """,
            ),
        )
        return ids.mapValues { (_, threadId) -> raw.get(threadId)?.asString ?: "missing" }
    }

    private fun clearBalloons() {
        SandboxUi.js(
            """
            var shown = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification, project);
            for (var i = 0; i < shown.length; i++) shown[i].expire();
            'ok'
            """,
        )
    }

    /** The content of every Marginalis notification still shown in the project. */
    private fun balloonTexts(): List<String> = SandboxUi.stringList(
        SandboxUi.js(
            """
            var shown = com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification, project);
            var texts = [];
            for (var i = 0; i < shown.length; i++) {
                if (String(shown[i].getGroupId()) == 'Marginalis') texts.push(String(shown[i].getContent()));
            }
            String(JSON.stringify(texts))
            """,
        ),
    )

    private fun marginCardLastLine(threadId: String): String = SandboxUi.marginJs(
        """
        var children = fieldOf(marginPanel(), 'stack').getComponents();
        var shown = 'no card';
        for (var i = 0; i < children.length; i++) {
            if (String(children[i].getClass().getSimpleName()) != 'ThreadCard') continue;
            if (String(children[i].getThread().getId()) != '$threadId') continue;
            shown = String(fieldOf(children[i], 'preview').getText());
        }
        String(shown)
        """,
    )

    private companion object {
        const val FILE = "src/review_target.py"
        const val A_BODY = "`legacy_helper` has no callers left."
        const val B_BODY = "Rename old_total_price, then drop _this_ and **that**."
        const val P_REPLY = "Ship `cache_ttl` and max_retry_count as they are; _then_ tag it."
        const val P_REPLY_PREVIEW = "Ship cache_ttl and max_retry_count as they are; then tag it."
    }
}
