package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * A conversation goes on after the code it points at is gone (MARG-275): an orphaned thread the user answers shows
 * the agent's move, the next Submit round hands it to the agent, and a live thread stays live through orphaning, so
 * the user's reply wakes the agent at once. Each case orphans its own thread by erasing its anchored line.
 */
class OrphanedThreadKeepsTalkingTest : SandboxScenario("orphaned_thread_keeps_talking") {

    private lateinit var answered: String

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun anOrphanTheUserAnswersShowsTheAgentsMove() = case("an_orphan_the_user_answers_shows_the_agents_move") {
        answered = orphanedThread(FIRST_ANCHOR, "Should step_3 guard against negatives?")

        SandboxUi.userReplies(answered, "Why not raise instead?")

        val signal = readUntil("the orphan's turn signal in the tree", read = { treeSignal(answered) }) { it == "agent" }
        check("tree: the orphan the user answered shows ✈, the agent's move", signal == "agent", signal)
    }

    @Test
    @Order(2)
    fun aSubmitRoundHandsTheOrphanToTheAgent() = case("a_submit_round_hands_the_orphan_to_the_agent") {
        val wake = agentWaitsWhile(WAIT_SECONDS) {
            readUntil("Submit round turns on", read = { ToolWindowUi.isToolWindowActionEnabled(SUBMIT_ROUND) }) { it }
            ToolWindowUi.pressToolWindowAction(SUBMIT_ROUND)
        }

        check("the round wakes the agent", wake.get("reason")?.asString == "hand_back", wake)
        val orphan = awaiting(wake).firstOrNull { it.get("thread_id").asString == answered }
        check("the wake hands over the orphan the user answered, marked orphaned", orphan?.get("status")?.asString == "orphaned", wake)
    }

    @Test
    @Order(3)
    fun aLiveThreadStaysLiveThroughOrphaningAndWakesTheAgent() = case("a_live_thread_stays_live_through_orphaning_and_wakes_the_agent") {
        SandboxUi.openInEditor(FILE)
        val anchorLine = lineOf(LIVE_ANCHOR)
        val live = Agent.agentAddsLineThread(FILE, anchorLine, "Does step_6 repeat step_5?")
        SandboxUi.openThreadInEditor(live, FILE)
        Agent.listensWhile { SandboxUi.toggleThreadLive(live) }
        check("the thread goes live", readUntil("the thread goes live", read = { SandboxUi.isLive(live) }) { it })

        SandboxUi.eraseLine(FILE, anchorLine)
        readUntil("the live thread orphans", read = { status(live) }) { it == "orphaned" }

        check("an orphaned live thread stays live", SandboxUi.isLive(live))
        val wake = agentWaitsWhile(WAIT_SECONDS) { SandboxUi.userReplies(live, "It does, merge them.") }
        check("the user's reply in the orphan wakes the agent at once, as live", wake.get("reason")?.asString == "live", wake)
        check("the live wake hands over only the orphan", awaiting(wake).map { it.get("thread_id").asString } == listOf(live), wake)
    }

    private fun orphanedThread(anchor: String, body: String): String {
        SandboxUi.openInEditor(FILE)
        val anchorLine = lineOf(anchor)
        val thread = Agent.agentAddsLineThread(FILE, anchorLine, body)
        SandboxUi.eraseLine(FILE, anchorLine)
        val orphaned = readUntil("the thread orphans", read = { status(thread) }) { it == "orphaned" }
        check("erasing the anchored line orphans the thread", orphaned == "orphaned", orphaned)
        SandboxUi.openThreadInEditor(thread, FILE)
        return thread
    }

    /** How the agent's wait ended while [during] ran. */
    private fun agentWaitsWhile(timeoutSeconds: Int, during: () -> Unit): JsonObject {
        var wake: JsonObject? = null
        var failure: Exception? = null
        val waiting = thread {
            try {
                wake = Agent.agentWaits(timeoutSeconds)
            } catch (error: Exception) {
                failure = error
            }
        }
        Thread.sleep(1_500)
        during()
        waiting.join()
        failure?.let { throw it }
        return wake ?: error("comment_wait returned nothing")
    }

    private fun awaiting(wake: JsonObject): List<JsonObject> = wake.getAsJsonArray("awaiting")?.map { it.asJsonObject }.orEmpty()

    private fun status(threadId: String): String =
        Agent.apiGet("comment_list", mapOf("file" to FILE, "summary" to "true")).getAsJsonArray("threads")
            .map { it.asJsonObject }.first { it.get("thread_id").asString == threadId }
            .get("status").asString.lowercase()

    /** The turn signal after a thread's row in any tree section: `you`, `agent`, `none` or `missing`. */
    private fun treeSignal(threadId: String): String = SandboxUi.js(
        ToolWindowUi.TREE + """
        var icons = pluginLoader.loadClass('dev.marginalis.plugin.ui.MarginalisIcons').getField('INSTANCE').get(null);
        var signals = pluginLoader.loadClass('dev.marginalis.core.ThreadSignal');
        var yourMove = icons.threadSignal(signals.getField('YOUR_MOVE').get(null));
        var agentsMove = icons.threadSignal(signals.getField('AGENTS_MOVE').get(null));
        var tree = marginalisTree();
        var renderer = tree.getCellRenderer();
        var nodes = tree.getModel().getRoot().preorderEnumeration();
        var found = 'missing';
        while (nodes.hasMoreElements()) {
            var node = nodes.nextElement();
            var thread = threadOfNode(node);
            if (thread == null || String(thread.getId()) != '$threadId') continue;
            var cell = renderer.getTreeCellRendererComponent(tree, node, false, true, node.isLeaf(), 0, false);
            var parts = cell.getComponents();
            var icon = null;
            for (var i = 0; i < parts.length; i++) {
                if (parts[i] instanceof com.intellij.ui.ColoredTreeCellRenderer && parts[i].getIcon() != null) icon = parts[i].getIcon().getIcon(2);
            }
            found = icon === agentsMove ? 'agent' : icon === yourMove ? 'you' : 'none';
        }
        String(found)
        """,
    )

    /** 1-based line of the first line that reads exactly `text`. */
    private fun lineOf(text: String): Int = SandboxUi.documentText(FILE).lines().indexOf(text) + 1

    private companion object {
        const val FILE = "src/long_module.py"
        const val FIRST_ANCHOR = "def step_3(value: int) -> int:"
        const val LIVE_ANCHOR = "def step_6(value: int) -> int:"
        const val WAIT_SECONDS = 60
        const val SUBMIT_ROUND = "Submit round"
    }
}
