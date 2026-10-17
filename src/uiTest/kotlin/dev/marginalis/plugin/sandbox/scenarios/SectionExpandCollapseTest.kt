package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Expand / Collapse in the tool window's toolbar: from the selection's folder one level further out per press, up to
 * its section, then every section; Collapse folds everything inside (as IntelliJ's Collapse All); folded folders stay
 * folded when the tree refreshes. Uses the deep walkthrough fixture.
 */
class SectionExpandCollapseTest : SandboxScenario("section_expand_collapse") {

    @BeforeAll
    fun postTheWalkthroughLaidOutByFolder() {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        folderLayout()
        // Creating 33 threads queues rebuilds; let them land before the first press.
        Thread.sleep(5_000)
    }

    @Test
    @Order(1)
    fun collapseLevels() = case("collapse_levels") {
        ToolWindowUi.selectStep(16)
        pause()
        val walked = mutableListOf<String?>()
        for (level in LEVELS_FROM_16) {
            ToolWindowUi.pressToolWindowAction(*COLLAPSE)
            walked += readUntil("$level selected after Collapse", read = { ToolWindowUi.selectedNodeName() }) { it == level }
            if (walked.size == 2) ToolWindowUi.screenshotToolWindow(shots.resolve("collapse-two-levels.png"))
        }
        report("INFO  Collapse from step 16 folded, press by press: $walked")
        check("Collapse folds one level further out with each press, up to the section", walked == LEVELS_FROM_16, walked)
        var state = sectionsUntil("only the Guided section folded") { state ->
            !state.getValue(GUIDED).expanded && state.getValue("Open").expanded
        }
        check(
            "Collapse up to the section leaves the other sections open",
            !state.getValue(GUIDED).expanded && state.getValue("Open").expanded, state,
        )

        ToolWindowUi.openSectionOnly(GUIDED)
        val folded = sectionsUntil("nothing open inside the reopened Guided section") { it.getValue(GUIDED).folded }.getValue(GUIDED).folded
        check("Collapse folds everything inside, as IntelliJ does", folded)
        ToolWindowUi.screenshotToolWindow(shots.resolve("reopened-section-one-level.png"))

        // The section is selected and open again: one press folds it, the next one every section.
        ToolWindowUi.pressToolWindowAction(*COLLAPSE)
        pause()
        ToolWindowUi.pressToolWindowAction(*COLLAPSE)
        state = sectionsUntil("every section folded") { state -> state.values.none { it.expanded } }
        check("past the section, Collapse folds every section", state.values.none { it.expanded }, state)
        ToolWindowUi.screenshotToolWindow(shots.resolve("collapse-all.png"))
    }

    @Test
    @Order(2)
    fun expandLevels() = case("expand_levels") {
        // Everything is folded and the Guided section is selected.
        ToolWindowUi.pressToolWindowAction(*EXPAND)
        var state = sectionsUntil("only the Guided section open all the way down") { state ->
            state.getValue(GUIDED).full && !state.getValue("Open").expanded
        }
        check("Expand opens the selected section all the way down", state.getValue(GUIDED).full, state)
        check("Expand leaves the other sections folded", !state.getValue("Open").expanded, state)

        ToolWindowUi.pressToolWindowAction(*EXPAND)
        state = sectionsUntil("every section open all the way down") { state -> state.values.all { it.full } }
        check("Expand again opens every section all the way down", state.values.all { it.full }, state)

        ToolWindowUi.foldDir(GUIDED, ORDERS_SRC)
        ToolWindowUi.selectDir(GUIDED, ORDERS_SRC)
        pause()
        ToolWindowUi.pressToolWindowAction(*EXPAND)
        val opened = readUntil("$ORDERS_SRC open", read = { ToolWindowUi.dirExpanded(GUIDED, ORDERS_SRC) }) { it }
        check("Expand on a folded folder opens that folder", opened)
    }

    @Test
    @Order(3)
    fun foldsSurviveRefresh() = case("folds_survive_refresh") {
        ToolWindowUi.foldDir(GUIDED, ORDERS_SRC)
        pause()
        ToolWindowUi.pokeAThread()
        // Time for the refresh to open the folder again, which it must not.
        pause()
        check("a folded folder stays folded when the tree refreshes", !ToolWindowUi.dirExpanded(GUIDED, ORDERS_SRC))
        ToolWindowUi.screenshotToolWindow(shots.resolve("folded-folder-after-refresh.png"))
    }

    @Test
    @Order(4)
    fun collapsingASectionFoldsEveryFolderInIt() = case("collapsing_a_section_folds_every_folder_in_it") {
        ToolWindowUi.selectSection(GUIDED)
        pause()
        ToolWindowUi.pressToolWindowAction(*EXPAND)
        check(sectionsUntil("the Guided section open all the way down") { it.getValue(GUIDED).full }.getValue(GUIDED).full) {
            "setup: the Guided section is not open all the way down"
        }
        // The section itself is the collapse target: no walk out from a step folds the folders on the way.
        ToolWindowUi.pressToolWindowAction(*COLLAPSE)
        pause()
        ToolWindowUi.openSectionOnly(GUIDED)
        val folderOpen = readUntil("$ORDERS_SRC folded in the reopened section", read = { ToolWindowUi.dirExpanded(GUIDED, ORDERS_SRC) }) { !it }
        check(
            "Collapse on a section folds a folder beside the selection too, so reopening it shows one level",
            !folderOpen, ToolWindowUi.sectionsState().getValue(GUIDED),
        )
        ToolWindowUi.screenshotToolWindow(shots.resolve("section-collapsed-directly.png"))
    }

    private fun sectionsUntil(what: String, done: (Map<String, ToolWindowUi.SectionState>) -> Boolean) =
        readUntil(what, read = { ToolWindowUi.sectionsState() }, done = done)

    /**
     * Folders are the levels Expand and Collapse step through; a build with a Guided layout menu (MARG-10) starts in
     * step order, which has none. Set on the panel rather than through the header menu: this is setup, and the header
     * is built a while after start.
     */
    private fun folderLayout() {
        SandboxUi.js(
            ToolWindowUi.TREE + """
            var panel = marginalisTree();
            while (panel != null && panel.getClass().getSimpleName() != 'MarginalisToolWindowPanel') panel = panel.getParent();
            var layouts = null;
            try { layouts = java.lang.Class.forName('dev.marginalis.core.GuidedLayout', true, panel.getClass().getClassLoader()); } catch (e) {}
            if (layouts != null) panel.setGuidedLayout(java.lang.Enum.valueOf(layouts, 'BY_FOLDER'));
            String(true)
            """,
        )
        pause()
    }

    private companion object {
        const val GUIDED = "Guided SHOP-42"
        const val ORDERS_SRC = "shop-orders/src"

        /** From step 16 outwards, the folders around it, then its section. */
        val LEVELS_FROM_16 = listOf("api", "customer/settings", "main/java/com/acme/backend/common", "shop-common/src", "backend", "acme", GUIDED)

        // The header renames these buttons after the scope they act on; one lookup tries every name.
        val COLLAPSE = arrayOf("Collapse Folder", "Collapse Section", "Collapse All Sections", "Collapse")
        val EXPAND = arrayOf("Expand Folder", "Expand Section", "Expand All Sections", "Expand")

        fun pause() = Thread.sleep(1_000)
    }
}
