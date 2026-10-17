package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.concurrent.thread

/**
 * The plugin's core loop in a real IDE (MARG-104): the agent posts a thread, the editor shows it, the user opens it
 * from its gutter icon, replies and submits the round, the agent's waiting `comment_wait` wakes with the reply, the
 * agent answers into the open thread, the user resolves it and the agent sees it resolved. A Live thread wakes the
 * agent on the user's Submit alone. The cases build on each other in order, on one thread.
 */
@Tag("core")
class AgentUserLoopTest : SandboxScenario("agent_user_loop") {

    private lateinit var thread: String

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun agentPostsAThreadTheEditorShows() = case("agent_posts_a_thread_the_editor_shows") {
        thread = Agent.agentAddsLineThread(FILE, LINE, QUESTION)
        SandboxUi.openInEditor(FILE)

        val icons = readUntil("the thread's gutter icon", read = { SandboxUi.editorIconLines(FILE) }) { LINE - 1 in it }
        check("the editor shows the agent's thread as a gutter icon on its line", LINE - 1 in icons, icons)

        SandboxUi.clickThreadIcon(FILE, LINE - 1)
        val shown = readUntil("the thread opens", read = { SandboxUi.panelView(thread).texts }) { QUESTION in it.joinToString() }
        check("a click on the icon opens the thread with the agent's message", QUESTION in shown.joinToString(), shown)
    }

    @Test
    @Order(2)
    fun userRepliesAndSubmitsTheRound() = case("user_replies_and_submits_the_round") {
        val waiting = AgentWait(WAIT_SECONDS)
        val listening = readUntil("Submit round turns on", read = { ToolWindowUi.isToolWindowActionEnabled(SUBMIT_ROUND) }) { it }
        check("Submit round turns on while the agent listens", listening)

        SandboxUi.userReplies(thread, USER_REPLY)
        ToolWindowUi.pressToolWindowAction(SUBMIT_ROUND)
        val wake = waiting.result()

        check("the agent's wait wakes on the submitted round", wake.get("reason")?.asString == "hand_back", wake)
        check("the wake hands the agent the thread the user answered", awaitingIds(wake) == listOf(thread), wake)
        check("the wake carries the user's reply", USER_REPLY in wake.toString(), wake)
    }

    @Test
    @Order(3)
    fun agentAnswersIntoTheOpenThread() = case("agent_answers_into_the_open_thread") {
        Agent.agentReplies(thread, AGENT_ANSWER)

        val shown = readUntil("the agent's answer shows", read = { SandboxUi.panelView(thread).texts }) { AGENT_ANSWER in it.joinToString() }
        check("the open thread shows the agent's answer", AGENT_ANSWER in shown.joinToString(), shown)
    }

    @Test
    @Order(4)
    fun userResolvesAndTheAgentSeesIt() = case("user_resolves_and_the_agent_sees_it") {
        SandboxUi.pressPanelAction(thread, "Resolve")

        val resolved = readUntil("the agent lists the thread as resolved", read = { resolvedIds() }) { thread in it }
        check("the agent lists the thread as resolved", thread in resolved, resolved)
    }

    @Test
    @Order(5)
    fun liveThreadWakesTheAgentOnSubmit() = case("live_thread_wakes_the_agent_on_submit") {
        val live = Agent.agentAddsLineThread(FILE, LIVE_LINE, LIVE_QUESTION)
        SandboxUi.openThreadInEditor(live, FILE)
        awaitUntil("the live thread's panel opens", Duration.ofSeconds(30)) { LIVE_QUESTION in SandboxUi.panelView(live).texts.joinToString() }
        val waiting = AgentWait(WAIT_SECONDS)
        val listening = readUntil("the agent listens again", read = { ToolWindowUi.isToolWindowActionEnabled(SUBMIT_ROUND) }) { it }
        check("the agent listens again after the resolved round", listening, if (listening) "" else "its wait ended: ${waiting.result()}")
        if (!listening) return@case

        SandboxUi.toggleThreadLive(live)
        val isLive = readUntil("the thread goes live", read = { SandboxUi.isLive(live) }) { it }
        check("the thread goes live", isLive)
        SandboxUi.userReplies(live, LIVE_REPLY)
        val wake = waiting.result()

        check("the user's Submit alone wakes the agent, as live", wake.get("reason")?.asString == "live", wake)
        check("a live wake hands over only that thread", awaitingIds(wake) == listOf(live), wake)
    }

    private fun awaitingIds(wake: JsonObject): List<String> =
        wake.getAsJsonArray("awaiting")?.map { it.asJsonObject.get("thread_id").asString }.orEmpty()

    private fun resolvedIds(): List<String> =
        Agent.apiGet("comment_list", mapOf("status" to "resolved", "summary" to "true"))
            .getAsJsonArray("threads").map { it.asJsonObject.get("thread_id").asString }

    /** The agent's `comment_wait`, held on a thread of its own while the user acts. */
    private class AgentWait(timeoutSeconds: Int) {
        private var wake: JsonObject? = null
        private var failure: Exception? = null
        private val waiting = thread {
            try {
                wake = Agent.agentWaits(timeoutSeconds)
            } catch (error: Exception) {
                failure = error
            }
        }

        fun result(): JsonObject {
            waiting.join()
            failure?.let { throw it }
            return wake ?: error("comment_wait returned nothing")
        }
    }

    private companion object {
        const val FILE = "src/greeting.py"
        const val LINE = 1
        const val LIVE_LINE = 5
        const val WAIT_SECONDS = 60
        const val SUBMIT_ROUND = "Submit round"
        const val QUESTION = "Should greet() trim the name?"
        const val USER_REPLY = "Yes, trim it, and keep the comma."
        const val AGENT_ANSWER = "Done: greet() trims the name now."
        const val LIVE_QUESTION = "Does farewell() need the same?"
        const val LIVE_REPLY = "No, leave farewell() as it is."
    }
}
