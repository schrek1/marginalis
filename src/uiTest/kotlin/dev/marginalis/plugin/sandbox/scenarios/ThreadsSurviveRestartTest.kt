package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Everything the user left in the margin survives a real stop and start of the IDE process (MARG-105): line, file and
 * project threads with their replies, what the user has read, a resolved thread, and the project's own settings (Sort,
 * Live by default, Guided layout). After the start the markers sit on their lines, and the Margin tab and the tool
 * window hold the same threads.
 */
@Tag("core")
class ThreadsSurviveRestartTest : SandboxScenario("threads_survive_restart") {

    private lateinit var line: String
    private lateinit var fileLevel: String
    private lateinit var projectLevel: String
    private lateinit var resolved: String
    private lateinit var unread: String
    private lateinit var before: Snapshot

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun theUserLeavesAWorkingMargin() = case("the_user_leaves_a_working_margin") {
        line = Agent.agentAddsLineThread(GREETING, 1, "Should greet() trim the name?")
        SandboxUi.openInEditor(GREETING)
        SandboxUi.openThreadInEditor(line, GREETING)
        readUntil("the line thread's panel opens", read = { SandboxUi.panelView(line).texts }) { it.isNotEmpty() }
        SandboxUi.userReplies(line, "Yes, trim it.")
        fileLevel = Agent.apiPost("comment_add", mapOf("body" to "This module needs a docstring.", "file" to LONG_MODULE, "project" to Agent.PROJECT_NAME, "author_name" to "Claude", "author_id" to "claude-sandbox"))
            .get("thread_id").asString
        projectLevel = Agent.agentStartsProjectThread("Release notes for 0.3: anything missing?")
        Agent.agentReplies(projectLevel, "I added the sort menu to the draft.")
        SandboxUi.userReads(projectLevel)
        resolved = Agent.agentAddsLineThread(REVIEW_TARGET, 2, "Split on commas or on whitespace?")
        Agent.resolveThread(resolved)
        unread = Agent.agentAddsLineThread(GREETING, 5, "farewell() could reuse greet()'s trimming.")

        SandboxUi.openMarginTab(projectLevel)
        SandboxUi.chooseMarginSort("Newest first")
        ToolWindowUi.pressToolWindowAction("Live by default")
        ToolWindowUi.chooseGuidedLayout("By file")

        before = snapshot()
        check("before the restart: five threads, one resolved", before.statuses.size == 5 && before.statuses[resolved] == "resolved", before.statuses)
        check("before the restart: the user's reply is in the line thread", before.messages[line] == 2, before.messages)
        check("before the restart: the user has read the project thread, not the new line thread",
            before.unreadByUser[projectLevel] == 0 && before.unreadByUser[unread] == 1, before.unreadByUser)
        check("before the restart: the project's settings are set",
            before.settings == listOf("NEWEST_FIRST", "true", "BY_FILE"), before.settings)
    }

    @Test
    @Order(2)
    fun theRestartedIdeShowsTheSameMargin() = case("the_restarted_ide_shows_the_same_margin") {
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        SandboxUi.showToolWindow()

        val after = readUntil("the store holds the threads again", read = { snapshot() }) { it.statuses.size == 5 }
        check("every thread is back, each with its status", after.statuses == before.statuses, "${after.statuses} vs ${before.statuses}")
        check("every thread keeps its messages", after.messages == before.messages, "${after.messages} vs ${before.messages}")
        check("what the user had read stays read, and what not, unread", after.unreadByUser == before.unreadByUser,
            "${after.unreadByUser} vs ${before.unreadByUser}")
        check("the project's settings survive: Sort, Live by default, Guided layout", after.settings == before.settings,
            "${after.settings} vs ${before.settings}")

        SandboxUi.openInEditor(GREETING)
        val icons = readUntil("the markers in greeting.py", read = { SandboxUi.editorIconLines(GREETING) }) { it.containsAll(listOf(0, 4)) }
        check("the markers sit on their lines again", icons.containsAll(listOf(0, 4)), icons)

        val tree = readUntil("the tool window lists the threads", read = { ToolWindowUi.treeThreadIds() }) { it.containsAll(before.statuses.keys) }
        check("the tool window holds every thread", tree.containsAll(before.statuses.keys), tree)

        SandboxUi.openMarginTab(projectLevel)
        val tab = readUntil("the Margin tab lists the project thread", read = { SandboxUi.marginTabOrder() }) { projectLevel in it }
        check("the Margin tab holds the project thread", projectLevel in tab, tab)
    }

    /** The margin as the user and the agent see it: per thread its status, message count and unread-by-user count. */
    private data class Snapshot(
        val statuses: Map<String, String>,
        val messages: Map<String, Int>,
        val unreadByUser: Map<String, Int>,
        val settings: List<String>,
    )

    private fun snapshot(): Snapshot {
        val threads = Agent.apiGet("comment_list", mapOf("summary" to "true")).getAsJsonArray("threads").map { it.asJsonObject }
            .plus(Agent.apiGet("comment_list", mapOf("summary" to "true", "status" to "resolved")).getAsJsonArray("threads").map { it.asJsonObject })
            .associateBy { it.get("thread_id").asString }
        val unreadByUser = SandboxUi.obj(
            SandboxUi.js(
                """
                var all = store.getThreads().all();
                var counts = {};
                for (var i = 0; i < all.size(); i++) counts[String(all.get(i).getId())] = all.get(i).unreadByUserCount();
                String(JSON.stringify(counts))
                """,
            ),
        ).entrySet().associate { (id, count) -> id to count.asInt }
        return Snapshot(
            statuses = threads.mapValues { (_, thread) -> status(thread) },
            messages = threads.mapValues { (_, thread) -> thread.get("messages").asInt },
            unreadByUser = unreadByUser,
            settings = listOf(SandboxUi.marginSort(), SandboxUi.liveByDefault().toString(), ToolWindowUi.guidedLayout()),
        )
    }

    private fun status(thread: JsonObject): String = thread.get("status").asString.lowercase()

    private companion object {
        const val GREETING = "src/greeting.py"
        const val LONG_MODULE = "src/long_module.py"
        const val REVIEW_TARGET = "src/review_target.py"
    }
}
