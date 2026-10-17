package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.writeText

/**
 * The Margin tab shows only with something in it (MARG-48): a fresh project opens without it, the agent's first
 * project thread brings it, an empty tab does not come back after a restart, and a project with an open project
 * thread opens it unpinned.
 *
 * The scenario's first start is the fresh project the base class opens.
 */
class NoEmptyMarginTabTest : SandboxScenario("no_empty_margin_tab") {

    private var firstThread: String? = null

    @Test
    @Order(1)
    fun freshProjectOpensWithoutTheTab() = case("fresh_project_opens_without_the_tab") {
        settleAfterStart(threads = 0)
        settle()
        shot("1-fresh-project")
        check("a fresh project without project threads opens without the Margin tab", !marginTab().open, marginTab())
    }

    @Test
    @Order(2)
    fun firstProjectThreadBringsTheTab() = case("first_project_thread_brings_the_tab") {
        firstThread = Agent.agentStartsProjectThread("The first project thread.")
        val tab = marginTabUntilOpen()
        shot("2-first-project-thread")
        check("the agent's first project thread opens the Margin tab", tab.open, tab)
        check("the Margin tab opened by a thread is not pinned", !tab.pinned, tab)
    }

    @Test
    @Order(3)
    fun emptyTabIsNotRestored() = case("empty_tab_is_not_restored") {
        val thread = firstThread ?: error("the first project thread was not posted")
        Agent.resolveThread(thread)
        awaitOrFail("the resolved thread on disk") { persistedStatus(thread) == "RESOLVED" }
        check("the resolved thread leaves the Margin tab open and empty (precondition)", marginTab().open, marginTab())
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        settleAfterStart(threads = 1)
        settle()
        shot("3-restart-after-empty-tab")
        check("a restart does not bring back an empty Margin tab", !marginTab().open, marginTab())
    }

    @Test
    @Order(4)
    fun openThreadOpensTheTabUnpinned() = case("open_thread_opens_the_tab_unpinned") {
        val thread = Agent.agentStartsProjectThread("An open project thread before the project is opened again.")
        awaitOrFail("the open thread on disk") { persistedStatus(thread) == "OPEN" }
        val threadsOnDisk = Sandbox.projectText(THREADS_FILE) ?: error("no $THREADS_FILE in the project")
        // A first open of a project that already has project threads, as after installing or updating the plugin.
        Sandbox.restart(fresh = true) {
            val threadsFile = Sandbox.project.resolve(THREADS_FILE)
            Files.createDirectories(threadsFile.parent)
            threadsFile.writeText(threadsOnDisk)
        }
        settleAfterStart(threads = 2)
        val tab = marginTabUntilOpen()
        shot("4-first-open-with-a-project-thread")
        check("a project with an open project thread opens with the Margin tab", tab.open, tab)
        check("that Margin tab is not pinned", !tab.pinned, tab)
    }

    private data class MarginTab(val open: Boolean, val pinned: Boolean)

    private fun marginTab(): MarginTab {
        val shown = JsonParser.parseString(
            SandboxUi.js(
                """
                var marginFile = pluginLoader.loadClass('dev.marginalis.plugin.ui.tab.MarginFile').getField('INSTANCE').get(null);
                var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
                var windows = manager.getWindows();
                var pinned = false;
                for (var i = 0; i < windows.length; i++) {
                    if (windows[i].isFileOpen(marginFile) && windows[i].isFilePinned(marginFile)) pinned = true;
                }
                String(JSON.stringify({open: manager.isFileOpen(marginFile), pinned: pinned}))
                """,
            ),
        ).asJsonObject
        return MarginTab(shown.get("open").asBoolean, shown.get("pinned").asBoolean)
    }

    // The tab's startup decision runs on the EDT right after the persisted threads land in the store.
    private fun settleAfterStart(threads: Int) {
        awaitOrFail("the project's first-open mark") {
            SandboxUi.js("String(com.intellij.ide.util.PropertiesComponent.getInstance(project).getBoolean('marginalis.projectTab.introduced'))") == "true"
        }
        awaitOrFail("$threads persisted threads in the store") { SandboxUi.js("String(store.getThreads().all().size())").toInt() >= threads }
    }

    private fun marginTabUntilOpen(): MarginTab = readUntil("the Margin tab open", read = { marginTab() }) { it.open }

    private fun awaitOrFail(what: String, condition: () -> Boolean) = awaitUntil(what, Duration.ofSeconds(60), condition = condition)

    private fun persistedStatus(threadId: String): String? {
        val text = Sandbox.projectText(THREADS_FILE) ?: return null
        val threads = JsonParser.parseString(text).asJsonObject.getAsJsonArray("threads") ?: return null
        return threads.map { it.asJsonObject }.firstOrNull { it.get("id").asString == threadId }?.get("status")?.asString
    }

    private companion object {
        const val THREADS_FILE = ".idea/marginalis.json"

        /** Time for the startup decision to open a Margin tab that must stay shut. */
        fun settle() = Thread.sleep(3_000)
    }
}
