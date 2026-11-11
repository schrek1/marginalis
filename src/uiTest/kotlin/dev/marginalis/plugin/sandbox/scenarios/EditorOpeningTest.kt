package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Every way the plugin opens an editor still lands where it did (MARG-224): a code link opens its file at the line and
 * selects a range, and a reveal brings the Margin tab to the front, whether the tab is closed or already open behind
 * the file you read. The plugin moved off the `FileEditorManager` open calls 2026.3 deprecates; the IDE must neither
 * ask which file type the Margin tab is nor log an error on the way.
 */
class EditorOpeningTest : SandboxScenario("editor_opening") {

    private var lineThread = ""

    @Test
    @Order(1)
    fun aRangeLinkOpensTheFileWithTheRangeSelected() = case("a_range_link_opens_the_file_with_the_range_selected") {
        // GIVEN - a thread in review_target.py links to lines 5-6 of greeting.py
        SandboxUi.openInEditor(FILE)
        lineThread = Agent.agentAddsLineThread(FILE, 1, "See [farewell]($RANGE_LINK) and [the greeting]($LINE_LINK).")
        SandboxUi.openThreadInEditor(lineThread, FILE)

        // WHEN - the user clicks the range link
        followLink(lineThread, RANGE_LINK)

        // THEN - greeting.py is in front, the caret on line 5, both lines selected
        val front = readUntil("greeting.py in front after the range link", read = { frontText() }) { it.get("file").asString == TARGET_NAME }
        shot("1-range-link")
        check("the range link brings greeting.py to the front", front.get("file").asString == TARGET_NAME, front)
        check("the caret sits on the link's first line", front.get("caretLine").asInt == 5, front)
        check("the range's lines are selected", front.get("selection").asString == RANGE_TEXT, front)
        checkNoDialog()
    }

    @Test
    @Order(2)
    fun aLineLinkMovesTheCaretWithoutSelecting() = case("a_line_link_moves_the_caret_without_selecting") {
        // GIVEN - the thread's file is in front again
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(lineThread, FILE)

        // WHEN - the user clicks the single-line link
        followLink(lineThread, LINE_LINK)

        // THEN - greeting.py is in front with the caret on line 2 and nothing selected
        val front = readUntil("greeting.py in front with the caret on line 2", read = { frontText() }) {
            it.get("file").asString == TARGET_NAME && it.get("caretLine").asInt == 2
        }
        check("the line link brings greeting.py to the front", front.get("file").asString == TARGET_NAME, front)
        check("the caret sits on the linked line", front.get("caretLine").asInt == 2, front)
        check("nothing is selected", front.get("selection").asString.isEmpty(), front)
    }

    @Test
    @Order(3)
    fun aRevealOpensTheClosedMarginTabInFront() = case("a_reveal_opens_the_closed_margin_tab_in_front") {
        // GIVEN - a project thread exists and the Margin tab is closed
        val projectThread = Agent.agentStartsProjectThread("A project thread for the Margin tab to reveal.")
        SandboxUi.openMarginTab(projectThread)
        SandboxUi.js(SandboxUi.MARGIN + CLOSE_MARGIN_TAB)
        readUntil("the Margin tab closed", read = { SandboxUi.js(SandboxUi.MARGIN + "String(marginFile() != null)") }) { it == "false" }
        SandboxUi.openInEditor(FILE)

        // WHEN - the thread is revealed
        SandboxUi.openMarginTab(projectThread)

        // THEN - the tab is in front and shows the thread
        shot("2-reveal-closed-tab")
        check("the reveal opens the Margin tab in front", selectedName() == "Margin", selectedName())
        val expanded = readUntil("the revealed thread open in the tab", read = { expandedThreadIds() }) { projectThread in it }
        check("the tab opens the revealed thread", projectThread in expanded, expanded)
        checkNoDialog()
    }

    @Test
    @Order(4)
    fun aRevealBringsTheOpenTabBackToTheFront() = case("a_reveal_brings_the_open_tab_back_to_the_front") {
        // GIVEN - the Margin tab is open behind the file you read, and a second project thread arrives
        val second = Agent.agentStartsProjectThread("A second project thread, revealed while the tab is open behind the file.")
        SandboxUi.openInEditor(FILE)
        readUntil("review_target.py in front", read = { selectedName() }) { it == FILE_NAME }

        // WHEN - the second thread is revealed
        SandboxUi.openMarginTab(second)

        // THEN - the open tab comes to the front with the second thread, and there is still one Margin tab
        shot("3-reveal-open-tab")
        check("the reveal brings the open Margin tab to the front", selectedName() == "Margin", selectedName())
        val expanded = readUntil("the second thread open in the tab", read = { expandedThreadIds() }) { second in it }
        check("the already open tab handles the reveal", second in expanded, expanded)
        val tabs = SandboxUi.js(MARGIN_TAB_COUNT).toInt()
        check("the reveal reuses the open tab instead of adding one", tabs == 1, tabs)
    }

    /** Fires the link's click as its own EDT event, as a real click does: a robot script holds no lock to open an editor under. */
    private fun followLink(threadId: String, href: String) {
        SandboxUi.js(
            """
            var link = ${SandboxUi.lit(href)};
            var pane = null;
            function find(c) {
                if (pane == null && c instanceof javax.swing.JEditorPane && String(c.getText()).indexOf('href="' + link + '"') >= 0) pane = c;
                var children = c.getComponents ? c.getComponents() : [];
                for (var i = 0; i < children.length; i++) find(children[i]);
            }
            find(component);
            if (pane == null) throw 'no message pane links to ' + link;
            var click = new javax.swing.event.HyperlinkEvent(pane, javax.swing.event.HyperlinkEvent.EventType.ACTIVATED, null, link);
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(
                new java.lang.Runnable({ run: function () { pane.fireHyperlinkUpdate(click); } }));
            String(true)
            """,
            SandboxUi.threadPanel(threadId),
        )
    }

    private fun frontText(): JsonObject = JsonParser.parseString(
        SandboxUi.js(
            """
            var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedTextEditor();
            var state = { file: '', caretLine: -1, selection: '' };
            if (editor != null) {
                var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.getDocument());
                state.file = file == null ? '' : String(file.getName());
                state.caretLine = (read(function () { return editor.getCaretModel().getLogicalPosition().line; }) | 0) + 1;
                var selected = read(function () { return editor.getSelectionModel().getSelectedText(); });
                state.selection = selected == null ? '' : String(selected);
            }
            String(JSON.stringify(state))
            """,
        ),
    ).asJsonObject

    private fun selectedName(): String = SandboxUi.js(
        """
        var files = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedFiles();
        String(files.length ? files[0].getName() : '')
        """,
    )

    /** A reveal opens the thread's card in the tab: its thread panel exists. */
    private fun expandedThreadIds(): List<String> = JsonParser.parseString(
        SandboxUi.marginJs(
            """
            var cards = fieldOf(marginPanel(), 'cards');
            var expanded = [];
            var ids = cards.keySet().toArray();
            for (var i = 0; i < ids.length; i++) if (cards.get(ids[i]).getPanel() != null) expanded.push(String(ids[i]));
            String(JSON.stringify(expanded))
            """,
        ),
    ).asJsonArray.map { it.asString }

    /** A file type question would be a modal dialog: the Margin tab has no extension to tell its type by. */
    private fun checkNoDialog() {
        val dialogs = SandboxUi.js(
            """
            var titles = [];
            var windows = java.awt.Window.getWindows();
            for (var i = 0; i < windows.length; i++) {
                if (windows[i] instanceof java.awt.Dialog && windows[i].isShowing()) titles.push(String(windows[i].getTitle()));
            }
            String(JSON.stringify(titles))
            """,
        )
        check("the IDE asks nothing on the way", dialogs == "[]", dialogs)
    }

    private companion object {
        const val FILE = "src/review_target.py"
        const val FILE_NAME = "review_target.py"
        const val TARGET_NAME = "greeting.py"
        const val RANGE_LINK = "src/greeting.py#L5-L6"
        const val LINE_LINK = "src/greeting.py#L2"
        const val RANGE_TEXT = "def farewell(name: str) -> str:\n    return f\"Goodbye, {name}.\""

        const val CLOSE_MARGIN_TAB = """
            var tab = marginFile();
            if (tab != null) com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(tab);
            String(true)
        """

        const val MARGIN_TAB_COUNT = """
            var open = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getOpenFiles();
            var count = 0;
            for (var i = 0; i < open.length; i++) if (String(open[i].getUrl()).indexOf('marginalis://margin/') == 0) count++;
            String(count)
        """
    }
}
