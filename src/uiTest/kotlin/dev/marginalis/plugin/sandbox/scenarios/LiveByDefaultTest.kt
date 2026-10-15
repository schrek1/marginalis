package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread
import kotlin.io.path.readText

/**
 * Live by default: the tool window toggle, a thread's own switch, and the Settings default; a port of
 * `sandbox/scenarios/live_by_default.py`. The Settings default is application-wide, so it is switched off before the
 * first fresh project and again at the end.
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

        val quiet = wakeAfterReply(first, "Before live by default", QUIET_WAIT_SECONDS)
        check("without the default a reply does not wake the agent", quiet.reason == "timeout", quiet)

        ToolWindowUi.pressToolWindowAction(LIVE_BY_DEFAULT)
        check("the toolbar switches the project's default on", SandboxUi.liveByDefault())
        check("every open thread is live", SandboxUi.isLive(first) && SandboxUi.isLive(second))

        val wake = wakeAfterReply(second, "After live by default", 30)
        check("a reply in any thread wakes the agent with that thread", wokeLive(wake, second), wake)
    }

    @Test
    @Order(2)
    fun threadSwitchOverridesTheDefault() = case("thread_switch_overrides_the_default") {
        val thread = Agent.agentStartsProjectThread("Thread to switch off")
        Thread.sleep(1_000)
        SandboxUi.toggleThreadLive(thread)
        check("a thread's own switch turns it off under the default", !SandboxUi.isLive(thread))

        val quiet = wakeAfterReply(thread, "Switched off", QUIET_WAIT_SECONDS)
        check("a thread switched off stays quiet", quiet.reason == "timeout", quiet)

        ToolWindowUi.pressToolWindowAction(LIVE_BY_DEFAULT)
        ToolWindowUi.pressToolWindowAction(LIVE_BY_DEFAULT)
        check("flipping the default clears the thread's override", SandboxUi.isLive(thread))
    }

    @Test
    @Order(3)
    fun settingsDefaultIsCopiedOnce() = case("settings_default_is_copied_once") {
        setAppDefault(true)
        restart(fresh = true)
        check("a new project takes the Settings default", SandboxUi.liveByDefault())

        setAppDefault(false)
        restart(fresh = false)
        check("changing Settings leaves an existing project alone", SandboxUi.liveByDefault())

        ToolWindowUi.pressToolWindowAction(LIVE_BY_DEFAULT)
        restart(fresh = false)
        check("the project's own choice survives a restart", !SandboxUi.liveByDefault())

        val workspace = Sandbox.project.resolve(".idea").resolve("workspace.xml").readText()
        check("the choice lives in workspace.xml", "name=\"MarginalisLiveDefault\"" in workspace)
    }

    /** How the agent's wait ended: `reason`, and the ids of the threads it was woken for. */
    private data class Wake(val reason: String?, val awaiting: List<String>, val raw: JsonObject) {
        override fun toString() = raw.toString()
    }

    /** The agent is waiting while the user submits a reply in the thread; returns how the wait ended. */
    private fun wakeAfterReply(threadId: String, body: String, timeoutSeconds: Int): Wake {
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
        SandboxUi.userReplies(threadId, body)
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
        const val LIVE_BY_DEFAULT = "Live by default"
    }
}
