package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.PluginErrors
import dev.marginalis.plugin.sandbox.PluginUpdate
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.safe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration

/**
 * Updating the plugin in the running IDE, as Update does it (MARG-108): with a thread open in the editor, the Margin tab
 * and the tool window up, the next build (the same code under a higher version) replaces the loaded one without a
 * restart. The update logs no error from the plugin, and afterwards the margin holds the same threads and a round
 * between the agent and the user still works.
 */
class UpdatingThePluginTest : SandboxScenario("updating_the_plugin") {

    private lateinit var line: String
    private lateinit var projectLevel: String
    private lateinit var messagesBefore: Map<String, Int>

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun theUserWorksInTheMargin() = case("the_user_works_in_the_margin") {
        line = Agent.agentAddsLineThread(GREETING, 1, "Should greet() trim the name?")
        SandboxUi.openInEditor(GREETING)
        SandboxUi.openThreadInEditor(line, GREETING)
        readUntil("the line thread's panel opens", read = { SandboxUi.panelView(line).texts }) { it.isNotEmpty() }
        SandboxUi.userReplies(line, "Yes, trim it.")
        projectLevel = Agent.agentStartsProjectThread("Release notes for 0.3: anything missing?")
        SandboxUi.openMarginTab(projectLevel)

        messagesBefore = readUntil("the user's reply reaches the store", read = { messages() }) { it[line] == 2 }
        check("before the update: the line thread holds the user's reply, the project thread its message",
            messagesBefore == mapOf(line to 2, projectLevel to 1), messagesBefore)
        val tab = readUntil("the Margin tab lists the project thread", read = { SandboxUi.marginTabOrder() }) { projectLevel in it }
        check("before the update: the Margin tab is open on the project thread", projectLevel in tab, tab)
        val tree = readUntil("the tool window lists the threads", read = { ToolWindowUi.treeThreadIds() }) { it.containsAll(listOf(line, projectLevel)) }
        check("before the update: the tool window lists both threads", tree.containsAll(listOf(line, projectLevel)), tree)
    }

    @Test
    @Order(2)
    fun theNextBuildLoadsIntoTheRunningIde() = case("the_next_build_loads_into_the_running_ide") {
        val installed = checkNotNull(Sandbox.prebuiltPlugin) { "the update needs the prebuilt plugin (`./gradlew sandboxPrebuilt`, Docker)" }
        val update = Sandbox.runtime.resolve("updates").resolve("marginalis-next.zip")
        val nextVersion = PluginUpdate.nextVersion(installed, update)
        SandboxUi.rememberPluginLoader()
        val updateStarted = SandboxUi.ideLogClock()

        val installation = runCatching { SandboxUi.installPluginUpdate(Sandbox.fileForIde(update)) }

        check("the update loads without a restart", installation.isSuccess, installation.exceptionOrNull()?.message)
        val version = readUntil("the IDE reports the new version", read = { safe { SandboxUi.pluginVersion() } }) { it == nextVersion }
        check("the IDE runs the new version", version == nextVersion, "$version, expected $nextVersion")
        val errors = PluginErrors.inLog(Sandbox.ideLogLines(), since = updateStarted)
        check("the update logged no error from the plugin", errors.isEmpty(), errors.firstOrNull()?.let { "${errors.size} entries, first: $it" })
    }

    @Test
    @Order(3)
    fun theMarginWorksAfterTheUpdate() = case("the_margin_works_after_the_update") {
        val messagesAfter = readUntil("the store holds the threads again", read = { safe { messages() } }) { it == messagesBefore }
        check("every thread is back with its messages", messagesAfter == messagesBefore, "$messagesAfter vs $messagesBefore")

        val icons = readUntil("the marker in greeting.py", read = { safe { SandboxUi.editorIconLines(GREETING) } }) { it == listOf(0) }
        check("the line thread's marker sits on its line again", icons == listOf(0), icons)

        SandboxUi.showToolWindow()
        val tree = readUntil("the tool window lists the threads", read = { safe { ToolWindowUi.treeThreadIds() } }) {
            it is Set<*> && it.containsAll(listOf(line, projectLevel))
        }
        check("the tool window lists both threads again", tree is Set<*> && tree.containsAll(listOf(line, projectLevel)), tree)

        SandboxUi.openMarginTab(projectLevel)
        val tab = readUntil("the Margin tab lists the project thread", read = { SandboxUi.marginTabOrder() }) { projectLevel in it }
        check("the Margin tab opens on the project thread", projectLevel in tab, tab)

        Agent.agentReplies(line, "Done: greet() trims the name.")
        SandboxUi.openInEditor(GREETING)
        SandboxUi.openThreadInEditor(line, GREETING)
        val shown = readUntil("the agent's answer shows in the line thread", read = { SandboxUi.panelView(line).texts }) {
            it.any { text -> "greet() trims the name" in text }
        }
        check("the agent's answer shows in the reopened thread", shown.any { text -> "greet() trims the name" in text }, shown)
        SandboxUi.userReplies(line, "Thanks.")
        val messages = readUntil("the user's new reply reaches the agent", read = { messages() }) { it[line] == 4 }
        check("the user's reply after the update reaches the agent", messages[line] == 4, messages)
    }

    // FixMe: Known issue - #240 the tool window's tree and an icon cache keep the old version's class loader after an update
    @Test
    @Order(4)
    @EnabledIfEnvironmentVariable(named = "MARGINALIS_SANDBOX_KNOWN_GAPS", matches = "true", disabledReason = "a known gap, not in the default run")
    fun theOldVersionLeavesNothingInMemory() = case("the_old_version_leaves_nothing_in_memory") {
        val released = readUntil("the old version's class loader is collected", timeout = Duration.ofSeconds(60), read = { SandboxUi.pluginLoaderReleased() }) { it }

        val leak = if (released) null else SandboxUi.pluginLeakAnalysis().also { analysis -> println("DIAG  what keeps the old version:\n$analysis") }
        check("nothing keeps the old version in memory", released, leak)
    }

    private fun messages(): Map<String, Int> =
        Agent.apiGet("comment_list", mapOf("summary" to "true")).getAsJsonArray("threads").map { it.asJsonObject }
            .associate { thread -> thread.get("thread_id").asString to thread.get("messages").asInt }

    private companion object {
        const val GREETING = "src/greeting.py"
    }
}
