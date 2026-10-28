package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.REVIEW_FILES
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.SHOWS_TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET
import dev.marginalis.plugin.sandbox.scenarios.ReviewDiffWalkthroughTest.Companion.TARGET_AFTER
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.readUntil
import dev.marginalis.plugin.sandbox.waitUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * A step's header stays readable in a review diff's side-by-side panel (MARG-65).
 *
 * The frame is narrowed until a side of the diff gives the panel less than its buttons' full width, then widened again. The
 * header is read as laid out on screen: where the status text ends, where the step buttons start and end.
 */
class ThreadHeaderInANarrowPanelTest : SandboxScenario("thread_header_in_a_narrow_panel") {

    private lateinit var step: String

    @Test
    @Order(1)
    fun build() = case("build") {
        SandboxUi.showToolWindow()
        step = Agent.agentAddsStep(TARGET, 6, "Step 1: total sums the values.", 1, "review")
        SandboxUi.closeAllEditors()
        ReviewDiffUi.openReviewDiffWithFiles(REVIEW_FILES, shown = SHOWS_TARGET)
        val shown = waitUntil("the review diff shows review_target.py") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        check("setup: the review diff shows review_target.py", shown)
    }

    @Test
    @Order(2)
    fun narrowPanel() = case("narrow_panel") {
        resizeFrame(NARROW_FRAME)
        awaitUntil("the review diff on screen in the narrowed frame") { ReviewDiffUi.diffShows(TARGET_AFTER) }
        SandboxUi.openThreadInDiff(step, TARGET_AFTER)
        awaitUntil("the step opens in the review diff") { step in SandboxUi.threadIdsOpenInDiff(TARGET_AFTER) }
        val header = headerOnceLaidOut { it.panelWidth <= NARROW_PANEL }
        shot("1-narrow")
        SandboxUi.screenshotThread(step, shots.resolve("1-narrow-thread.png"))
        check("setup: the panel is narrow", header.panelWidth <= NARROW_PANEL, header)
        check("the status text ends where the step buttons start", header.statusRight <= header.buttonsLeft, header)
        check("the buttons stay inside the header", header.buttonsLeft >= 0 && header.buttonsRight <= header.headerWidth, header)
        check("the status text is shortened to fit", header.statusWidth < header.statusPreferredWidth, header)
        check("the status text keeps its first word", header.statusWidth >= header.statusMinimumWidth, header)
    }

    @Test
    @Order(3)
    fun widePanel() = case("wide_panel") {
        resizeFrame(WIDE_FRAME)
        val header = headerOnceLaidOut { it.panelWidth > NARROW_PANEL && it.statusWidth >= it.statusPreferredWidth }
        SandboxUi.screenshotThread(step, shots.resolve("2-wide-thread.png"))
        check("setup: the panel is wide", header.panelWidth > NARROW_PANEL, header)
        check("the status text shows in full", header.statusWidth >= header.statusPreferredWidth, header)
        check("the status text ends where the step buttons start", header.statusRight <= header.buttonsLeft, header)
    }

    private data class Header(
        val panelWidth: Int,
        val headerWidth: Int,
        val statusText: String,
        val statusRight: Int,
        val statusWidth: Int,
        val statusPreferredWidth: Int,
        val statusMinimumWidth: Int,
        val buttonsLeft: Int,
        val buttonsRight: Int,
    )

    private fun resizeFrame(width: Int) {
        SandboxUi.js(
            """
            var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project);
            frame.setBounds(0, 0, $width, 1440);
            frame.validate();
            String(true)
            """,
        )
    }

    private fun headerOnceLaidOut(laidOut: (Header) -> Boolean): Header =
        readUntil("the step's header laid out", read = ::readHeader) { header -> header != null && laidOut(header) }
            ?: error("the step's header was never laid out")

    private fun readHeader(): Header? {
        val raw = SandboxUi.js(
            """
            function field(object, name) {
                var f = object.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            }
            var status = field(component, 'statusLabel');
            var buttons = field(component, 'headerToolbar').getComponent();
            var header = buttons.getParent().getParent();
            function inHeader(c) { return javax.swing.SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), header); }
            var s = inHeader(status);
            var b = inHeader(buttons);
            JSON.stringify({
                panelWidth: component.getWidth(), headerWidth: header.getWidth(), statusText: String(status.getText()),
                statusRight: s.x + s.width, statusWidth: s.width, statusPreferredWidth: status.getPreferredSize().width,
                statusMinimumWidth: status.getMinimumSize().width,
                buttonsLeft: b.x, buttonsRight: b.x + b.width
            })
            """,
            SandboxUi.threadPanel(step),
        )
        val json = JsonParser.parseString(raw).asJsonObject
        if (json.get("buttonsRight").asInt == 0) return null
        return Header(
            panelWidth = json.get("panelWidth").asInt,
            headerWidth = json.get("headerWidth").asInt,
            statusText = json.get("statusText").asString,
            statusRight = json.get("statusRight").asInt,
            statusWidth = json.get("statusWidth").asInt,
            statusPreferredWidth = json.get("statusPreferredWidth").asInt,
            statusMinimumWidth = json.get("statusMinimumWidth").asInt,
            buttonsLeft = json.get("buttonsLeft").asInt,
            buttonsRight = json.get("buttonsRight").asInt,
        )
    }

    private companion object {
        // With the tool window as wide as it opens, each side of the diff gets less than the toolbar's full width.
        const val NARROW_FRAME = 1500
        const val WIDE_FRAME = 2560
        const val NARROW_PANEL = 380
    }
}
