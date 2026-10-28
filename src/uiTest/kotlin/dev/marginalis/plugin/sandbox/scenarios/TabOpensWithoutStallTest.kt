package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Test

/**
 * Bringing the Margin tab back as at startup leaves the IDE responsive while it holds back its editor checks (MARG-88).
 * A write pending at startup holds back every editor provider that checks a file under a read lock; the tab used to
 * wait for them on the EDT, for up to the platform's 30 s. The scenario holds the checks back on purpose and times the
 * startup opening.
 */
class TabOpensWithoutStallTest : SandboxScenario("tab_opens_without_stall") {

    @Test
    fun theTabComesBackWithoutHoldingUpTheIde() = case("the_tab_comes_back_without_holding_up_the_ide") {
        // GIVEN - the pinned tab was open behind the file you are reading, and the IDE holds back its editor checks
        Agent.agentStartsProjectThread("A project thread, so the Margin tab has something in it.")
        readUntil("the Margin tab open", read = { tabState() }) { it.open }
        SandboxUi.js(SandboxUi.MARGIN + PIN_TAB)
        SandboxUi.openInEditor(FILE)
        readUntil("the file in front", read = { tabState() }) { it.selected == FILE_NAME }
        SandboxUi.js(SandboxUi.MARGIN + CLOSE_TAB_AS_BEFORE_A_RESTART)
        readUntil("the Margin tab closed", read = { tabState() }) { !it.present }
        SandboxUi.js(HOLD_BACK_EDITOR_CHECKS)

        // WHEN - the tab comes back as at project startup
        val openingMs = SandboxUi.js(SandboxUi.MARGIN + OPEN_ON_STARTUP).toLong()
        val stillHeldBack = SandboxUi.js("String(java.lang.System.getProperty('$HOLD_KEY') == 'holding')") == "true"
        SandboxUi.js(RELEASE_EDITOR_CHECKS)

        // THEN - opening took no time on the EDT, and the tab is back, pinned, behind the file
        report("INFO  opening the tab took $openingMs ms on the EDT with the editor checks held back for ${HOLD_SECONDS}s")
        check("opening the tab returns while the editor checks are still held back", stillHeldBack, "$openingMs ms")
        check("opening the tab holds the EDT for less than a second", openingMs < 1_000, "$openingMs ms")
        val tab = readUntil("the Margin tab open again", read = { tabState() }) { it.open }
        shot("1-tab-back-behind-the-file")
        check("the tab is back once the checks finish", tab.open, tab)
        check("the tab is pinned again", tab.pinned, tab)
        check("the file you were reading stays in front", tab.selected == FILE_NAME, tab)
    }

    private data class TabState(val present: Boolean, val open: Boolean, val pinned: Boolean, val selected: String)

    private fun tabState(): TabState {
        val state = JsonParser.parseString(SandboxUi.js(SandboxUi.MARGIN + TAB_STATE)).asJsonObject
        return TabState(
            state.get("present").asBoolean,
            state.get("open").asBoolean,
            state.get("pinned").asBoolean,
            state.get("selected").asString,
        )
    }

    private companion object {
        const val FILE = "src/review_target.py"
        const val FILE_NAME = "review_target.py"
        const val HOLD_KEY = "marginalis.sandbox.holdBackEditorChecks"
        const val HOLD_SECONDS = 8

        const val TAB_STATE = """
            var manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project);
            var tab = marginFile();
            var windows = manager.getWindows();
            var pinned = false;
            for (var i = 0; i < windows.length; i++) {
                if (tab != null && windows[i].isFileOpen(tab) && windows[i].isFilePinned(tab)) pinned = true;
            }
            var files = manager.getSelectedFiles();
            String(JSON.stringify({
                present: tab != null,
                open: marginTabOpen(),
                pinned: pinned,
                selected: files.length ? String(files[0].getName()) : ''
            }))
            """

        const val PIN_TAB = """
            var tab = marginFile();
            var windows = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).getWindows();
            for (var i = 0; i < windows.length; i++) if (windows[i].isFileOpen(tab)) windows[i].setFilePinned(tab, true);
            String(true)
            """

        // What a plugin update leaves behind: the tab closed, and the marks that it was open and pinned.
        const val CLOSE_TAB_AS_BEFORE_A_RESTART = """
            var properties = com.intellij.ide.util.PropertiesComponent.getInstance(project);
            properties.setValue('marginalis.projectTab.reopen', true);
            properties.setValue('marginalis.projectTab.pinned', true);
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).closeFile(marginFile());
            String(true)
            """

        // An editor provider that answers for the Margin tab only once released, or after HOLD_SECONDS.
        const val HOLD_BACK_EDITOR_CHECKS = """
            java.lang.System.setProperty('$HOLD_KEY', 'holding');
            var provider = new com.intellij.openapi.fileEditor.FileEditorProvider({
                accept: function (p, file) {
                    if (String(file.getUrl()).indexOf('marginalis://margin/') != 0) return false;
                    var until = java.lang.System.currentTimeMillis() + $HOLD_SECONDS * 1000;
                    while (java.lang.System.getProperty('$HOLD_KEY') == 'holding' && java.lang.System.currentTimeMillis() < until) {
                        java.lang.Thread.sleep(50);
                    }
                    java.lang.System.clearProperty('$HOLD_KEY');
                    return false;
                },
                acceptRequiresReadAction: function () { return false; },
                createEditor: function (p, file) { throw 'accepts no file'; },
                getEditorTypeId: function () { return 'sandbox-held-back-editor-check'; },
                getPolicy: function () { return com.intellij.openapi.fileEditor.FileEditorPolicy.NONE; }
            });
            var registration = com.intellij.openapi.util.Disposer.newDisposable('held-back editor checks');
            com.intellij.openapi.fileEditor.FileEditorProvider.EP_FILE_EDITOR_PROVIDER.getPoint().registerExtension(provider, registration);
            java.lang.System.getProperties().put('$HOLD_KEY.registration', registration);
            String(true)
            """

        const val RELEASE_EDITOR_CHECKS = """
            java.lang.System.clearProperty('$HOLD_KEY');
            var registration = java.lang.System.getProperties().remove('$HOLD_KEY.registration');
            if (registration != null) com.intellij.openapi.util.Disposer.dispose(registration);
            String(true)
            """

        const val OPEN_ON_STARTUP = """
            var started = java.lang.System.currentTimeMillis();
            projectTab().openOnStartup(project);
            String(java.lang.System.currentTimeMillis() - started)
            """
    }
}
