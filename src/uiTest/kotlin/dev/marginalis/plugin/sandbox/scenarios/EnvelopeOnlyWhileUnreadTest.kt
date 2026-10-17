package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.awaitValue
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * The ✉ envelope shows only while something in the thread is unread (MARG-36).
 *
 * Four line threads in one file cover every case: A an agent reply the user has not read, B one the user read but did
 * not answer, C one the user answered (the agent's move), D an FYI the user read. Two project threads repeat A and B
 * in the Margin tab. The user reads B, C, D and Project B through the store, as an open thread panel does once it is
 * on screen, so which threads are read does not depend on what the sandbox happens to show. Project A arrives while
 * the code editor is in front: a card on screen in the Margin tab would be read at once.
 */
class EnvelopeOnlyWhileUnreadTest : SandboxScenario("envelope_only_while_unread") {

    private val ids = linkedMapOf<String, String>()

    @Test
    @Order(1)
    fun seedTheFourCases() = case("seed_the_four_cases") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        ids["A"] = Agent.agentAddsLineThread(FILE, 1, "A · a reply you have not read yet")
        ids["D"] = Agent.agentAddsLineThread(FILE, 2, "D · an FYI you have already read", fields = mapOf("intent" to "fyi"))
        ids["B"] = Agent.agentAddsLineThread(FILE, 5, "B · a reply you read but did not answer")
        ids["C"] = Agent.agentAddsLineThread(FILE, 9, "C · you answered, the agent is on it")
        ids["Project B"] = Agent.agentStartsProjectThread("Project B · a project note you read, not answered")
        SandboxUi.openThreadInEditor(id("C"), FILE)
        settle()
        SandboxUi.userReplies(id("C"), "Go ahead.")
        SandboxUi.openMarginTab(id("Project B"))
        settle()
        listOf("B", "C", "D", "Project B").forEach { name -> SandboxUi.userReads(id(name)) }
        // Project A arrives while the user is back in the code: an expanded card on screen in the active IDE is read
        // at once, so posting it with the Margin tab in front would read it before any check.
        SandboxUi.openInEditor(FILE)
        settle()
        ids["Project A"] = Agent.agentStartsProjectThread("Project A · a new project note, unread")
        settle()
        SandboxUi.openInEditor(FILE)
        settle()
        shot("1-seeded")
        checkStillUnread("setup")
    }

    @Test
    @Order(2)
    fun treeShowsTheEnvelopeOnlyWhileUnread() = case("tree_shows_the_envelope_only_while_unread") {
        val expected = mapOf("A" to "you", "B" to "none", "C" to "agent", "D" to "none", "Project A" to "you", "Project B" to "none")
        val signals = readUntil("the tree's turn signals", read = { threadSignals() }) { shown ->
            expected.all { (name, signal) -> shown[name] == signal }
        }
        for ((name, signal) in expected) {
            check("tree: thread $name shows $signal", signals[name] == signal, signals[name])
        }
        val counts = fileCountsUntil(FileCounts(you = 1, agent = 1))
        check("tree: the file row counts ✉ 1 and ✈ 1", counts == FileCounts(you = 1, agent = 1), counts)
    }

    @Test
    @Order(3)
    fun stripeKeepsTheEnvelopeWhileSomethingIsUnread() = case("stripe_keeps_the_envelope_while_something_is_unread") {
        val badge = stripeBadgeUntil("AWAITING_YOU")
        check("stripe: the envelope badge stays while A is unread", badge == "AWAITING_YOU", badge)
    }

    @Test
    @Order(4)
    fun marginTabShowsTheEnvelopeOnlyWhileUnread() = case("margin_tab_shows_the_envelope_only_while_unread") {
        checkStillUnread("before the Margin tab checks")
        val title = marginTabTitleUntil("✉ 1")
        check("Margin tab: the title counts only the unread project thread (✉ 1)", title == "✉ 1", title)
        val cards = readUntil("the Margin tab cards' turn signals", read = { marginCardSignals() }) { cards ->
            cards[id("Project A")] == "you" && cards[id("Project B")] == "none"
        }
        check("Margin tab: Project A's card keeps the envelope", cards[id("Project A")] == "you", cards)
        check("Margin tab: Project B's card shows no envelope", cards[id("Project B")] == "none", cards)
        val counts = readUntil("the Margin tab header's counts", read = { marginCounts() }) { "2 your move" in it }
        check("Margin tab: the header still counts both as your move", "2 your move" in counts, counts)
    }

    @Test
    @Order(5)
    fun theRealTurnIsUnchanged() = case("the_real_turn_is_unchanged") {
        val awaiting = awaitingUserIds()
        for (name in listOf("B", "Project B")) {
            check("API: $name stays in awaiting=user", id(name) in awaiting, awaiting.sorted())
        }
        check("the file's editor-tab badge still follows your move", fileTurn(FILE) == "USER_OWES", fileTurn(FILE))
    }

    @Test
    @Order(6)
    fun everythingReadClearsEveryEnvelope() = case("everything_read_clears_every_envelope") {
        listOf("A", "Project A").forEach { name -> SandboxUi.userReads(id(name)) }
        // Time for reading to change the file's turn, which it must not.
        settle()
        shot("2-all-read")
        val badge = stripeBadgeUntil("NONE")
        check("stripe: once everything is read the button is plain", badge == "NONE", badge)
        val counts = fileCountsUntil(FileCounts(you = null, agent = 1))
        check("tree: the file row shows no ✉ once everything is read", counts == FileCounts(you = null, agent = 1), counts)
        val title = marginTabTitleUntil("Margin")
        check("Margin tab: the title is Margin once everything is read", title == "Margin", title)
        check("the file's editor-tab badge still follows your move", fileTurn(FILE) == "USER_OWES", fileTurn(FILE))
    }

    @Test
    @Order(7)
    fun aNewAgentMessageBringsTheEnvelopeBack() = case("a_new_agent_message_brings_the_envelope_back") {
        Agent.agentReplies(id("B"), "One more thing about B.")
        val signal = readUntil("B's turn signal in the tree", read = { threadSignals()["B"] }) { it == "you" }
        shot("3-new-message-on-b")
        check("tree: a new agent message on B brings its envelope back", signal == "you", signal)
        val badge = stripeBadgeUntil("AWAITING_YOU")
        check("stripe: the envelope badge returns with it", badge == "AWAITING_YOU", badge)
    }

    @Test
    @Order(8)
    fun aBlockerStillWins() = case("a_blocker_still_wins") {
        Agent.agentAddsLineThread(FILE, 3, "A blocker: this must change before merge.", fields = mapOf("severity" to "blocker"))
        val badge = stripeBadgeUntil("BLOCKER")
        check("stripe: an open blocker's red badge wins over the envelope", badge == "BLOCKER", badge)
    }

    private fun stripeBadgeUntil(expected: String): String =
        readUntil("the stripe badge $expected", read = { stripeBadge() }) { it == expected }

    private fun fileCountsUntil(expected: FileCounts): FileCounts? =
        readUntil("the file row's counts $expected", read = { treeSignals().files[FILE_NAME] }) { it == expected }

    private fun marginTabTitleUntil(expected: String): String =
        readUntil("the Margin tab titled $expected", read = { marginTabTitle() }) { it == expected }

    private fun id(name: String): String = ids[name] ?: error("thread $name was not seeded")

    private fun checkStillUnread(time: String) {
        val unread = listOf("A", "Project A").associateWith { name -> unreadByUser(id(name)) }
        check("$time: A and Project A are still unread", unread.values.all { it > 0 }, unread)
    }

    private fun threadSignals(): Map<String, String> {
        val shown = treeSignals().threads
        return ids.mapValues { (_, threadId) -> shown[threadId] ?: "missing" }
    }

    private fun unreadByUser(threadId: String): Int = SandboxUi.js("String(store.getThreads().byId('$threadId').unreadByUserCount())").toInt()

    private data class FileCounts(val you: Int?, val agent: Int?)

    private data class TreeSignals(val threads: Map<String, String>, val files: Map<String, FileCounts>)

    /**
     * What the Open section of the tool window tree shows: per thread id the turn signal after its mark (`you`,
     * `agent` or `none`), and per file name the ✉ and ✈ counts on its row (null when hidden).
     */
    private fun treeSignals(): TreeSignals {
        val raw = SandboxUi.obj(
            SandboxUi.js(
                ToolWindowUi.TREE + SIGNALS + """
                var tree = marginalisTree();
                var renderer = tree.getCellRenderer();
                var root = tree.getModel().getRoot();
                var result = { threads: {}, files: {} };
                for (var s = 0; s < root.getChildCount(); s++) {
                    var section = root.getChildAt(s);
                    if (sectionTitle(section).indexOf('Open') != 0) continue;
                    var nodes = section.preorderEnumeration();
                    while (nodes.hasMoreElements()) {
                        var node = nodes.nextElement();
                        var data = node.getUserObject();
                        var kind = data == null ? '' : String(data.getClass().getSimpleName());
                        if (kind != 'ThreadNode' && kind != 'FileNode') continue;
                        var cell = renderer.getTreeCellRendererComponent(tree, node, false, true, node.isLeaf(), 0, false);
                        if (kind == 'ThreadNode') {
                            var words = null;
                            var parts = cell.getComponents();
                            for (var i = 0; i < parts.length; i++) {
                                if (parts[i] instanceof com.intellij.ui.ColoredTreeCellRenderer) words = parts[i];
                            }
                            result.threads[String(data.getThread().getId())] = trailingSignal(words == null ? null : words.getIcon());
                        } else {
                            var counts = { you: null, agent: null };
                            var labels = labelsIn(cell, []);
                            for (var j = 0; j < labels.length; j++) {
                                var label = labels[j];
                                var side = signalOf(label.getIcon());
                                if ((side == 'you' || side == 'agent') && label.isVisible()) counts[side] = Number(String(label.getText()));
                            }
                            result.files[String(data.getName())] = counts;
                        }
                    }
                }
                String(JSON.stringify(result))
                """,
            ),
        )
        val threads = raw.getAsJsonObject("threads").let { shown -> shown.keySet().associateWith { shown.get(it).asString } }
        val files = raw.getAsJsonObject("files").let { shown ->
            shown.keySet().associateWith { file ->
                val counts = shown.getAsJsonObject(file)
                FileCounts(
                    you = counts.get("you")?.takeUnless { it.isJsonNull }?.asInt,
                    agent = counts.get("agent")?.takeUnless { it.isJsonNull }?.asInt,
                )
            }
        }
        return TreeSignals(threads, files)
    }

    /** The Marginalis stripe button's icon: `AWAITING_YOU`, `BLOCKER` or `NONE` (the plain brand icon). */
    private fun stripeBadge(): String = SandboxUi.js(
        """
        var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
        var badges = pluginLoader.loadClass('dev.marginalis.core.StripeBadge');
        var shown = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow('Marginalis').getIcon();
        for (var depth = 0; depth < 5 && shown != null && shown.retrieveIcon; depth++) {
            var inner = shown.retrieveIcon();
            if (inner === shown) break;
            shown = inner;
        }
        var verdict = 'unknown:' + (shown == null ? 'null' : shown.getClass().getName());
        if (shown === icons.toolWindow(null)) verdict = 'NONE';
        if (shown === icons.toolWindow(badges.getField('AWAITING_YOU').get(null))) verdict = 'AWAITING_YOU';
        if (shown === icons.toolWindow(badges.getField('BLOCKER').get(null))) verdict = 'BLOCKER';
        String(verdict)
        """,
    )

    /**
     * The Margin tab's title as its editor tab shows it. The title provider is no stand-in: it skips the refresh of
     * the tab, so a label that never shows fails the step.
     */
    private fun marginTabTitle(): String = awaitValue("the Margin tab's label") {
        val shown = SandboxUi.obj(
            SandboxUi.marginJs(
                """
                var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
                var title = null;
                var windows = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getWindows();
                for (var i = 0; i < windows.length && title == null; i++) {
                    var tabs = windows[i].getTabbedPane().getTabs().getTabs();
                    for (var j = 0; j < tabs.size(); j++) {
                        var object = tabs.get(j).getObject();
                        if (object === marginFile || (object != null && object.getFile && object.getFile() === marginFile)) {
                            title = String(tabs.get(j).getText());
                        }
                    }
                }
                String(JSON.stringify({ title: title }))
                """,
            ),
        )
        shown.get("title").takeUnless { it.isJsonNull }?.asString
    }

    /** Per thread id, the turn signal after the mark on its Margin tab card. */
    private fun marginCardSignals(): Map<String, String> {
        val raw = SandboxUi.obj(
            SandboxUi.marginJs(
                SIGNALS + """
                var children = fieldOf(marginPanel(), 'stack').getComponents();
                var result = {};
                for (var i = 0; i < children.length; i++) {
                    if (String(children[i].getClass().getSimpleName()) != 'ThreadCard') continue;
                    var icon = fieldOf(children[i], 'markLabel').getIcon();
                    result[String(children[i].getThread().getId())] = trailingSignal(icon);
                }
                String(JSON.stringify(result))
                """,
            ),
        )
        return raw.keySet().associateWith { raw.get(it).asString }
    }

    private fun marginCounts(): String = SandboxUi.marginJs("String(fieldOf(marginPanel(), 'counts').getText())")

    /** The turn behind the editor tab and Project view badges of a file. */
    private fun fileTurn(path: String): String = SandboxUi.js("var turn = store.getFileTurns().of('$path'); String(turn == null ? 'null' : turn.name())")

    private fun awaitingUserIds(): Set<String> =
        Agent.apiGet("comment_list", mapOf("awaiting" to "user", "summary" to "true"))
            .getAsJsonArray("threads").map { it.asJsonObject.get("thread_id").asString }.toSet()

    private companion object {
        const val FILE = "src/review_target.py"
        const val FILE_NAME = "review_target.py"

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
function labelsIn(c, found) {
    if (c instanceof javax.swing.JLabel) found.push(c);
    var children = c.getComponents ? c.getComponents() : [];
    for (var i = 0; i < children.length; i++) labelsIn(children[i], found);
    return found;
}
"""

        fun settle() = Thread.sleep(2_000)
    }
}
