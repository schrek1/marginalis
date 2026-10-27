package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * A whole project live (upstream's "Make every open thread live"): the tool window toggle, which needs a listening
 * agent, a thread's own switch, and the Settings default a project follows until it makes its own choice. The Settings
 * default is application-wide, so it is switched off before the first fresh project and again at the end.
 */
class LiveByDefaultTest : SandboxScenario("live_by_default") {

    @BeforeAll
    fun freshProjectWithoutTheSettingsDefault() {
        setAppDefault(false)
        restart(fresh = true)
    }

    @AfterAll
    fun switchTheSettingsDefaultOff() = setAppDefault(false)

    @Test
    @Order(1)
    fun toolbarMakesEveryOpenThreadLive() = case("toolbar_makes_every_open_thread_live") {
        val first = Agent.agentStartsProjectThread("First thread: does a reply wake me?")
        val second = Agent.agentStartsProjectThread("Second thread")
        Thread.sleep(1_000)

        check("with no agent listening the toggle is disabled", !ToolWindowUi.isProjectLiveToggleEnabled())

        val quiet = wakeAfterReply(first, "Before the project is live", QUIET_WAIT_SECONDS)
        check("without it a reply does not wake the agent", quiet.reason == "timeout", quiet)

        val missed = agentWaitsWhile(30) { ToolWindowUi.toggleProjectLive() }
        check("the toolbar makes the project live while an agent listens", SandboxUi.projectLive())
        check("every open thread is live", SandboxUi.isLive(first) && SandboxUi.isLive(second))
        check("going live hands the listening agent the reply it missed", wokeLive(missed, first), missed)

        val wake = wakeAfterReply(second, "After the project went live", 30)
        check("a reply in any thread wakes the agent with that thread", wokeLive(wake, second), wake)
    }

    @Test
    @Order(2)
    fun threadSwitchOverridesTheDefault() = case("thread_switch_overrides_the_default") {
        val thread = Agent.agentStartsProjectThread("Thread to switch off")
        Thread.sleep(1_000)
        SandboxUi.toggleThreadLive(thread)
        val live = readUntil("the thread switched off", read = { SandboxUi.isLive(thread) }) { live -> !live }
        check("a thread's own switch turns it off under the default", !live)

        val quiet = wakeAfterReply(thread, "Switched off", QUIET_WAIT_SECONDS)
        check("a thread switched off stays quiet", quiet.reason == "timeout", quiet)

        ToolWindowUi.toggleProjectLive()
        Agent.listensWhile { ToolWindowUi.toggleProjectLive() }
        check("turning the project off and on again clears the thread's own switch", SandboxUi.isLive(thread))
    }

    @Test
    @Order(3)
    fun projectFollowsSettingsUntilItChooses() = case("project_follows_settings_until_it_chooses") {
        setAppDefault(true)
        restart(fresh = true)
        check("a new project follows the Settings default", SandboxUi.projectLive())

        setAppDefault(false)
        restart(fresh = false)
        check("a project without its own choice follows a change in Settings", !SandboxUi.projectLive())

        Agent.listensWhile { ToolWindowUi.toggleProjectLive() }
        check("the toolbar makes the project live", SandboxUi.projectLive())
        SandboxUi.saveProjectState()
        restart(fresh = false)
        check("the project's own choice survives a restart, over the Settings default", SandboxUi.projectLive())

        val workspace = Sandbox.projectText(".idea/workspace.xml").orEmpty()
        check("the choice lives in workspace.xml", "name=\"MarginalisProjectLive\"" in workspace)
    }

    /** How the agent's wait ended: `reason`, and the ids of the threads it was woken for. */
    private data class Wake(val reason: String?, val awaiting: List<String>, val raw: JsonObject) {
        override fun toString() = raw.toString()
    }

    /** The agent is waiting while the user submits a reply in the thread; returns how the wait ended. */
    private fun wakeAfterReply(threadId: String, body: String, timeoutSeconds: Int): Wake =
        agentWaitsWhile(timeoutSeconds) { SandboxUi.userReplies(threadId, body) }

    /** The agent is waiting while [during] runs; returns how the wait ended. */
    private fun agentWaitsWhile(timeoutSeconds: Int, during: () -> Unit): Wake {
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
        val result = wake ?: error("comment_wait returned nothing")
        return Wake(
            reason = result.get("reason")?.asString,
            awaiting = result.getAsJsonArray("awaiting")?.map { it.asJsonObject.get("thread_id").asString }.orEmpty(),
            raw = result,
        )
    }

    private fun wokeLive(wake: Wake, threadId: String) = wake.reason == "live" && wake.awaiting == listOf(threadId)

    private fun setAppDefault(enabled: Boolean) {
        SandboxUi.js(
            """
            var settings = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(pluginLoader.loadClass('dev.marginalis.plugin.settings.MarginalisSettings'));
            settings.getState().setLiveByDefault($enabled);
            String(true)
            """,
        )
    }

    private fun restart(fresh: Boolean) {
        Sandbox.restart(fresh)
        SandboxUi.showToolWindow()
    }

    private companion object {
        const val QUIET_WAIT_SECONDS = 6
    }
}
