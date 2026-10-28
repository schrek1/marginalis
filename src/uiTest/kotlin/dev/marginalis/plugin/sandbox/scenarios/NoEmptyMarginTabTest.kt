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
 * project thread brings it, and an empty tab does not come back after a restart. With "Show the Margin tab when a
 * project opens" off, the default (MARG-192), even a project with an open project thread opens without it, until the
 * agent starts the next one. With it on, a tab open when the IDE closes comes back after a restart, pinned if it was.
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
        Sandbox.restart(fresh = false)
        settleAfterStart(threads = 1)
        settle()
        shot("3-restart-after-empty-tab")
        check("a restart does not bring back an empty Margin tab", !marginTab().open, marginTab())
    }

    @Test
    @Order(4)
    fun openThreadKeepsTheTabClosedOnOpen() = case("open_thread_keeps_the_tab_closed_on_open") {
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
        settle()
        shot("4-first-open-with-a-project-thread")
        check("with the setting off, a project with an open project thread opens without the Margin tab", !marginTab().open, marginTab())
        Agent.agentStartsProjectThread("A new project thread after the project opened.")
        val tab = marginTabUntilOpen()
        shot("5-next-project-thread")
        check("the agent's next project thread opens the Margin tab", tab.open, tab)
        check("that Margin tab is not pinned", !tab.pinned, tab)
    }

    // No save before these restarts: the tab comes back only if what the IDE writes while it closes survives the stop.
    @Test
    @Order(5)
    fun openTabComesBackAfterRestart() = case("open_tab_comes_back_after_restart") {
        check("the Margin tab is open and not pinned (precondition)", marginTab() == MarginTab(open = true, pinned = false), marginTab())
        showTabOnProjectOpen()
        Sandbox.restart(fresh = false)
        val tab = marginTabUntilOpen()
        shot("6-open-tab-after-restart")
        check("with the setting on, a Margin tab open at shutdown comes back after a restart", tab.open, tab)
        check("a tab that was not pinned comes back unpinned", !tab.pinned, tab)
    }

    @Test
    @Order(6)
    fun pinnedTabComesBackPinned() = case("pinned_tab_comes_back_pinned") {
        pinMarginTab()
        check("the Margin tab is pinned (precondition)", marginTab().pinned, marginTab())
        Sandbox.restart(fresh = false)
        val tab = marginTabUntilOpen()
        shot("7-pinned-tab-after-restart")
        check("with the setting on, a pinned Margin tab comes back after a restart", tab.open, tab)
        check("it comes back pinned", tab.pinned, tab)
    }

    private data class MarginTab(val open: Boolean, val pinned: Boolean)

    private fun marginTab(): MarginTab {
        val shown = JsonParser.parseString(
            SandboxUi.js(
                SandboxUi.MARGIN +
                    """
                var tab = marginFile();
                var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
                var windows = manager.getWindows();
                var pinned = false;
                for (var i = 0; i < windows.length; i++) {
                    if (tab != null && windows[i].isFileOpen(tab) && windows[i].isFilePinned(tab)) pinned = true;
                }
                String(JSON.stringify({open: tab != null, pinned: pinned}))
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

    // The container's app config lasts until the scenario ends, over its restarts.
    private fun showTabOnProjectOpen() {
        SandboxUi.js(
            """
            var settings = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(pluginLoader.loadClass('dev.marginalis.plugin.settings.MarginalisSettings'));
            settings.getState().setShowTabOnProjectOpen(true);
            String(true)
            """,
        )
    }

    private fun pinMarginTab() {
        SandboxUi.js(
            SandboxUi.MARGIN +
                """
            var tab = marginFile();
            var windows = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getWindows();
            for (var i = 0; i < windows.length; i++) {
                if (windows[i].isFileOpen(tab)) windows[i].setFilePinned(tab, true);
            }
            String(true)
            """,
        )
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
