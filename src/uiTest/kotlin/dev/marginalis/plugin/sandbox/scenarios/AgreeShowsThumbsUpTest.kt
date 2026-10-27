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
 * An Agree shows 👍 instead of the agent's move, and the agent's answer to it shows ⏳ or nothing (MARG-75).
 *
 * Four line threads in one file: the user agrees with A and leaves it, agrees with B and the agent takes it on, agrees
 * with C and the agent acknowledges, and answers D in words (the agent's move, as before). A project thread repeats
 * the Agree in the Margin tab.
 */
class AgreeShowsThumbsUpTest : SandboxScenario("agree_shows_thumbs_up") {

    private val ids = linkedMapOf<String, String>()

    @Test
    @Order(1)
    fun theUserAgreesAndTheAgentAnswers() = case("the_user_agrees_and_the_agent_answers") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        ids["A"] = Agent.agentAddsLineThread(FILE, 1, "A · Split on `;` as well as `,`?")
        ids["B"] = Agent.agentAddsLineThread(FILE, 5, "B · Rename `total` to `order_total`?")
        ids["C"] = Agent.agentAddsLineThread(FILE, 9, "C · Drop `legacy_helper`, nothing calls it?")
        ids["D"] = Agent.agentAddsLineThread(FILE, 2, "D · Strip spaces around each part?")
        for (name in listOf("A", "B", "C")) {
            SandboxUi.openThreadInEditor(id(name), FILE)
            settle()
            SandboxUi.userAgrees(id(name))
        }
        SandboxUi.openThreadInEditor(id("D"), FILE)
        settle()
        SandboxUi.userReplies(id("D"), "Only on the left, please.")
        val agreed = readUntil("A, B and C end on an Agree", read = { agreedIds() }) { it.containsAll(listOf(id("A"), id("B"), id("C"))) }
        check("setup: A, B and C end on the user's Agree", agreed.containsAll(listOf(id("A"), id("B"), id("C"))), agreed)
        Agent.agentAnswersAgree(id("B"), "working")
        Agent.agentAnswersAgree(id("C"), "acknowledged")
        SandboxUi.openInEditor(FILE)
        settle()
        shot("1-agreed")
    }

    @Test
    @Order(2)
    fun theTreeShowsWhatEachAgreeCameTo() = case("the_tree_shows_what_each_agree_came_to") {
        val expected = mapOf("A" to "agreed", "B" to "working", "C" to "none", "D" to "agent")
        val signals = readUntil("the tree's signals", read = { threadSignals() }) { shown ->
            expected.all { (name, signal) -> shown[name] == signal }
        }
        check("tree: A, agreed and not answered yet, shows 👍", signals["A"] == "agreed", signals["A"])
        check("tree: B, which the agent took on, shows ⏳", signals["B"] == "working", signals["B"])
        check("tree: C, which the agent acknowledged, shows no sign", signals["C"] == "none", signals["C"])
        check("tree: D, answered in words, still shows ✈", signals["D"] == "agent", signals["D"])
        val counts = fileCountsUntil(FileCounts(you = null, agent = 1))
        check("tree: the file row counts only D as the agent's move (✈ 1)", counts == FileCounts(you = null, agent = 1), counts)
    }

    @Test
    @Order(3)
    fun theAgentStillOwesAnUnansweredAgree() = case("the_agent_still_owes_an_unanswered_agree") {
        val awaiting = awaitingAgentIds()
        check("API: A, not answered yet, is in the agent's awaiting=agent", id("A") in awaiting, awaiting)
        check("API: D, answered in words, is in awaiting=agent", id("D") in awaiting, awaiting)
        check("API: B and C, answered, await nobody", id("B") !in awaiting && id("C") !in awaiting, awaiting)
        val states = agentStates()
        check("API: B carries agent_state working", states[id("B")] == "working", states)
        check("API: C carries agent_state acknowledged and stays open", states[id("C")] == "acknowledged" && status(id("C")) == "open", states)
    }

    @Test
    @Order(4)
    fun aFileOfAgreedThreadsCarriesNoAgentsMove() = case("a_file_of_agreed_threads_carries_no_agents_move") {
        Agent.resolveThread(id("D"))
        val badge = readUntil("the file's editor-tab badge clears", read = { fileTurn(FILE) }) { it == "null" }
        check("editor tab: with only agreed threads left, the file shows no ✈ badge", badge == "null", badge)
        val counts = fileCountsUntil(FileCounts(you = null, agent = null))
        check("tree: the file row shows no counts", counts == FileCounts(you = null, agent = null), counts)
        val awaitingFilter = readUntil("the Awaiting Agent filter", read = { awaitingAgentFilterIds() }) { id("A") !in it }
        check("tree: the Awaiting Agent filter leaves out the agreed thread A", id("A") !in awaitingFilter, awaitingFilter)
    }

    @Test
    @Order(5)
    fun theMarginTabShowsTheThumbsUpToo() = case("the_margin_tab_shows_the_thumbs_up_too") {
        ids["Project"] = Agent.agentStartsProjectThread("Project · Cut the 0.3 release on Friday?")
        SandboxUi.openMarginTab(id("Project"))
        settle()
        SandboxUi.userAgrees(id("Project"))
        val cards = readUntil("the project card's signal", read = { marginCardSignals() }) { it[id("Project")] == "agreed" }
        shot("2-margin-tab")
        check("Margin tab: the agreed project thread's card shows 👍", cards[id("Project")] == "agreed", cards)
        val title = readUntil("the Margin tab title", read = { marginTabTitle() }) { it == "Margin" }
        check("Margin tab: the title counts no agent's move for it", title == "Margin", title)
    }

    @Test
    @Order(6)
    fun theNextWordBringsTheTurnsBack() = case("the_next_word_brings_the_turns_back") {
        SandboxUi.openInEditor(FILE)
        settle()
        Agent.agentReplies(id("B"), "Renamed to `order_total`.")
        // B's panel is still open from the Agree, so the reply may be read at once; ✉ shows only while it is unread.
        val signal = readUntil("B's signal after the agent's reply", read = { threadSignals()["B"] }) { it != "working" }
        val expected = if (unreadByUser(id("B")) > 0) "you" else "none"
        shot("3-agent-reported")
        check("tree: the agent's reply on B replaces ⏳ with the usual signal ($expected)", signal == expected, signal)
        check("API: B is the user's move again", id("B") in awaitingUserIds(), awaitingUserIds())
        check("API: B no longer carries an agent_state", agentStates()[id("B")] == null, agentStates())
    }

    private fun id(name: String): String = ids[name] ?: error("thread $name was not seeded")

    private fun fileCountsUntil(expected: FileCounts): FileCounts? =
        readUntil("the file row's counts $expected", read = { treeSignals().files[FILE_NAME] }) { it == expected }

    private fun threadSignals(): Map<String, String> {
        val shown = treeSignals().threads
        return ids.mapValues { (_, threadId) -> shown[threadId] ?: "missing" }
    }

    private fun agreedIds(): List<String> = SandboxUi.obj(
        SandboxUi.js(
            """
            var result = [];
            var threads = store.getThreads().all();
            for (var i = 0; i < threads.size(); i++) {
                var messages = threads.get(i).getMessages();
                if (messages.size() > 0 && messages.get(messages.size() - 1).getAgrees()) result.push(String(threads.get(i).getId()));
            }
            String(JSON.stringify({ ids: result }))
            """,
        ),
    ).getAsJsonArray("ids").map { it.asString }

    private fun summaries() = Agent.apiGet("comment_list", mapOf("summary" to "true")).getAsJsonArray("threads").map { it.asJsonObject }

    private fun agentStates(): Map<String, String?> =
        summaries().associate { it.get("thread_id").asString to it.get("agent_state")?.asString }

    private fun status(threadId: String): String? =
        summaries().firstOrNull { it.get("thread_id").asString == threadId }?.get("status")?.asString

    private fun unreadByUser(threadId: String): Int = SandboxUi.js("String(store.getThreads().byId('$threadId').unreadByUserCount())").toInt()

    private fun awaitingUserIds(): Set<String> =
        Agent.apiGet("comment_list", mapOf("awaiting" to "user", "summary" to "true"))
            .getAsJsonArray("threads").map { it.asJsonObject.get("thread_id").asString }.toSet()

    private fun awaitingAgentIds(): Set<String> =
        Agent.apiGet("comment_list", mapOf("awaiting" to "agent", "summary" to "true"))
            .getAsJsonArray("threads").map { it.asJsonObject.get("thread_id").asString }.toSet()

    /** The threads the tool window's "Awaiting Agent" filter keeps, read from its own predicate. */
    private fun awaitingAgentFilterIds(): List<String> = SandboxUi.obj(
        SandboxUi.js(
            """
            var filters = pluginLoader.loadClass('dev.marginalis.plugin.ui.toolwindow.TreeFilter');
            var lens = filters.getField('AWAITING_AGENT').get(null);
            var threads = store.getThreads().all();
            var result = [];
            for (var i = 0; i < threads.size(); i++) {
                if (String(lens.getMatches().invoke(threads.get(i))) == 'true') result.push(String(threads.get(i).getId()));
            }
            String(JSON.stringify({ ids: result }))
            """,
        ),
    ).getAsJsonArray("ids").map { it.asString }

    /** The turn behind the editor tab and Project view badges of a file. */
    private fun fileTurn(path: String): String = SandboxUi.js("var turn = store.getFileTurns().of('$path'); String(turn == null ? 'null' : turn.name())")

    private data class FileCounts(val you: Int?, val agent: Int?)

    private data class TreeSignals(val threads: Map<String, String>, val files: Map<String, FileCounts>)

    /**
     * What the Open section of the tool window tree shows: per thread id the signal after its mark (`you`, `agent`,
     * `agreed`, `working` or `none`), and per file name the ✉ and ✈ counts on its row (null when hidden).
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

    /** Per thread id, the signal after the mark on its Margin tab card. */
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

    /** The Margin tab's title as its editor tab shows it. */
    private fun marginTabTitle(): String = awaitValue("the Margin tab's label") {
        val shown = SandboxUi.obj(
            SandboxUi.marginJs(
                """
                var tab = marginFile();
                var title = null;
                var windows = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getWindows();
                for (var i = 0; i < windows.length && title == null; i++) {
                    var tabs = windows[i].getTabbedPane().getTabs().getTabs();
                    for (var j = 0; j < tabs.size(); j++) {
                        var object = tabs.get(j).getObject();
                        if (tab != null && (object === tab || (object != null && object.getFile && object.getFile() === tab))) {
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

    private companion object {
        const val FILE = "src/review_target.py"
        const val FILE_NAME = "review_target.py"

        const val SIGNALS = """
var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
var turns = pluginLoader.loadClass('dev.marginalis.core.Turn');
var agreements = pluginLoader.loadClass('dev.marginalis.core.AgreementSignal');
var yourMoveIcon = icons.turnSignal(turns.getField('USER_OWES').get(null));
var agentsMoveIcon = icons.turnSignal(turns.getField('AGENT_OWES').get(null));
var agreedIcon = icons.agreementSignal(agreements.getField('AGREED').get(null));
var workingIcon = icons.agreementSignal(agreements.getField('AGENT_ON_IT').get(null));
function signalOf(icon) {
    if (icon === yourMoveIcon) return 'you';
    if (icon === agentsMoveIcon) return 'agent';
    if (icon === agreedIcon) return 'agreed';
    if (icon === workingIcon) return 'working';
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
