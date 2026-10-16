package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Margin text spacing: a single Enter is a line break, paragraphs and lists get a gap, the first block sits flush.
 * Measured in the rendered message panes, in pixels.
 */
class MarginTextSpacingTest : SandboxScenario("margin_text_spacing") {

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun singleEnter() = case("single_enter") {
        val thread = posted("Alphaline first\nBravoline second")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphaline", "Bravoline"))
        check("a single Enter starts a new line", boxes["Bravoline"].top >= boxes["Alphaline"].bottom, boxes)
        SandboxUi.screenshotThread(thread, shots.resolve("spacing-single-enter.png"))
    }

    @Test
    @Order(2)
    fun hardBreakGuard() = case("hard_break_guard") {
        val thread = posted("Alphaline first  \nBravoline second")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphaline", "Bravoline"))
        val breaks = Regex("<br").findAll(boxes.html.orEmpty()).count()
        check("two trailing spaces still give exactly one break", breaks == 1, boxes.html)
    }

    @Test
    @Order(3)
    fun extraBlankLines() = case("extra_blank_lines") {
        val thread = posted("Alphablank one\n\nBravoblank two\n\n\nCharlieblank three")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphablank", "Bravoblank", "Charlieblank"))
        val oneBlank = boxes["Bravoblank"].top - boxes["Alphablank"].bottom
        val twoBlank = boxes["Charlieblank"].top - boxes["Bravoblank"].bottom
        val line = boxes["Alphablank"].bottom - boxes["Alphablank"].top
        check(
            "a second blank line adds an empty line", twoBlank >= oneBlank + line - 2,
            "one blank ${px(oneBlank)}, two blank ${px(twoBlank)}, line ${px(line)}",
        )
        SandboxUi.screenshotThread(thread, shots.resolve("spacing-blank-lines.png"))
    }

    @Test
    @Order(4)
    fun paragraphs() = case("paragraphs") {
        val thread = posted("Alphapara one.\n\nBravopara two.\n\nCharliepara three.")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphapara", "Bravopara", "Charliepara"))
        val gap = boxes["Bravopara"].top - boxes["Alphapara"].bottom
        check("paragraphs are set apart", gap >= MIN_GAP_PX, "gap ${px(gap)}")
        val first = boxes["Alphapara"].top - boxes["Alphapara"].paneTop
        check("the first paragraph sits flush with the header", first <= 1, "${px(first)} above it")
        SandboxUi.screenshotThread(thread, shots.resolve("spacing-paragraphs.png"))
    }

    @Test
    @Order(5)
    fun lists() = case("lists") {
        val thread = posted("Alphaintro text:\n\n- Bravoitem one\n- Charlieitem two\n\nDeltaoutro text.")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphaintro", "Bravoitem", "Charlieitem", "Deltaoutro"))
        val above = boxes["Bravoitem"].top - boxes["Alphaintro"].bottom
        check("a list is set off from the text above", above >= MIN_GAP_PX, "gap ${px(above)}")
        val between = boxes["Charlieitem"].top - boxes["Bravoitem"].bottom
        check("list items get a small gap", between >= 1, "gap ${px(between)}")
        val below = boxes["Deltaoutro"].top - boxes["Charlieitem"].bottom
        check("the gap below a list matches a paragraph gap", below >= MIN_GAP_PX && below <= 10, "gap ${px(below)}")
        SandboxUi.screenshotThread(thread, shots.resolve("spacing-lists.png"))
    }

    @Test
    @Order(6)
    fun leadingListGuard() = case("leading_list_guard") {
        val thread = posted("- Alphalead item\n- Bravolead item")
        val boxes = SandboxUi.wordBoxes(thread, listOf("Alphalead"))
        val first = boxes["Alphalead"].top - boxes["Alphalead"].paneTop
        check("a message starting with a list has no gap on top", first <= 3, "${px(first)} above it")
    }

    private fun posted(body: String): String {
        val thread = Agent.agentStartsProjectThread(body)
        Thread.sleep(2_000)
        return thread
    }

    private fun px(value: Double) = "${value}px"

    private companion object {
        const val MIN_GAP_PX = 4
    }
}
