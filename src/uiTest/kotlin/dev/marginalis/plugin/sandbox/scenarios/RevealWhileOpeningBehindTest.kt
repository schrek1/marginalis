package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import org.junit.jupiter.api.Test

/**
 * Revealing a project thread right after the agent posted it, while its Margin tab opens behind the file in front,
 * ends with the tab in front. The sandbox's CPU is capped so the reveal often lands before the queued opening runs.
 */
class RevealWhileOpeningBehindTest : SandboxScenario("reveal_while_opening_behind") {

    @Test
    fun aRevealWhileTheTabOpensBehindWins() = case("a_reveal_while_the_tab_opens_behind_wins") {
        Sandbox.limitCpus(CAPPED_CPUS)
        try {
            repeat(ROUNDS) { round ->
                closeMarginTab()
                SandboxUi.openInEditor(FILE)
                Thread.sleep(2_000)
                val thread = Agent.agentStartsProjectThread("Round ${round + 1}: a project thread while a file is in front.")
                SandboxUi.js(SandboxUi.MARGIN + "projectTab().reveal(project, store.getThreads().byId('$thread'), null); String(true)")
                // Long enough for the file to come back to the front, which it must not.
                Thread.sleep(4_000)
                val front = selected()
                check("round ${round + 1}: a reveal right after the agent's thread ends with the tab in front", front == "Margin", front)
            }
        } finally {
            Sandbox.limitCpus(null)
        }
    }

    private fun selected(): String =
        JsonParser.parseString(SandboxUi.js(SandboxUi.MARGIN + EDITOR_STATE)).asJsonObject.get("selected").asString

    private fun closeMarginTab() {
        SandboxUi.js(
            SandboxUi.MARGIN +
                """
                var tab = marginFile();
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function () {
                    if (tab != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(tab);
                });
                String(true)
                """,
        )
        Thread.sleep(1_000)
    }

    private companion object {
        const val FILE = "src/review_target.py"
        const val ROUNDS = 6
        const val CAPPED_CPUS = 0.3
        const val EDITOR_STATE = """
            var files = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedFiles();
            String(JSON.stringify({ selected: files.length ? String(files[0].getName()) : '' }))
            """
    }
}
