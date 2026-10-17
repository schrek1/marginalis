package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.ReviewDiffUi
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.awaitUntil
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Duration
import kotlin.io.path.readText
import kotlin.math.abs

/**
 * The fork's PRs #1–#4, all merged into schreko_stable. One case runs on its own with
 * `--tests '*StablePrsTest.zoom'`.
 */
class StablePrsTest : SandboxScenario("stable_prs") {

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    /** PR #2: GFM tables and strikethrough; a wide top-level table scrolls sideways. */
    @Test
    @Order(1)
    fun tables() = case("tables") {
        val thread = Agent.agentStartsProjectThread(
            "Before the table, prose that should keep wrapping to the panel.\n\n" + WIDE_TABLE + "\nAnd ~~struck out~~ text after it.",
        )
        // The panel lays its panes out a moment after it renders them; a table read before has no viewport width yet.
        val panes = readUntil("the thread's messages laid out", read = { SandboxUi.messagePanes(thread) }) { panes ->
            panes.isNotEmpty() && panes.filter { it.table }.all { it.viewportWidth > 0 }
        }
        val tables = panes.filter { it.table }
        check("the table renders as a table", tables.size == 1, panes)
        check("strikethrough renders as <s>", panes.any { it.strike }, panes)
        tables.firstOrNull()?.let { table ->
            check("the wide table keeps its natural width", table.width > table.viewportWidth && table.viewportWidth > 0, table)
            check("the wide table scrolls sideways", table.horizontalBarVisible, table)
        }
        SandboxUi.screenshotThread(thread, shots.resolve("pr2-table.png"))
    }

    /** PR #3: thread icons in review diffs, re-anchored on the diff's text; added files too. */
    @Test
    @Order(2)
    fun reviewDiffIcons() = case("review_diff_icons") {
        Agent.agentAddsLineThread(REVIEW_FILE, 2, "On parse")
        Agent.agentAddsLineThread(REVIEW_FILE, 6, "On total")
        Agent.agentAddsLineThread(REVIEW_FILE, 9, "On legacy_helper, which the PR deletes")
        Thread.sleep(1_000)
        SandboxUi.openReviewDiff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        // 1-based 2 and 6 in the base (0-based 1 and 5) move to 0-based 4 and 8 in the PR text; legacy_helper is gone.
        val lines = iconLinesUntil(listOf(4, 8))
        check("icons sit on the threads' lines in the PR text", lines == listOf(4, 8), lines)

        SandboxUi.openReviewDiff(REVIEW_FILE, "", REVIEW_AFTER, added = true)
        // The PR diff shows the same text, and its icons would pass for the added file's while it is still on screen.
        awaitUntil("the PR diff off screen behind the added file's diff", Duration.ofSeconds(20)) { !ReviewDiffUi.diffShows(REVIEW_BEFORE) }
        val addedLines = iconLinesUntil(listOf(4, 8))
        check("an added file's one-sided diff shows icons too", addedLines == listOf(4, 8), addedLines)
    }

    /**
     * PR #1: Next Step within the same file stays in a diff that shows the file itself (a checked-out branch). A diff
     * of a revision's copy (PR #3's case) is reported, not checked: #1 does not cover it.
     */
    @Test
    @Order(3)
    fun stayInDiff() = case("stay_in_diff") {
        val first = Agent.agentAddsLineThread(REVIEW_FILE, 2, "Step 1: parse", order = 1)
        val second = Agent.agentAddsLineThread(REVIEW_FILE, 6, "Step 2: total", order = 2)
        Thread.sleep(1_000)

        SandboxUi.closeAllEditors()
        SandboxUi.openLocalDiff(REVIEW_FILE, REVIEW_AFTER)
        Thread.sleep(2_000)
        SandboxUi.openThreadInDiff(first, REVIEW_BEFORE)
        Thread.sleep(1_000)
        SandboxUi.pressPanelAction(first, "Next Step")
        val openInDiff = openInDiffUntil(second, REVIEW_BEFORE)
        check("next step in a local diff keeps the diff in front", SandboxUi.selectedEditorKind() == "DIFF", SandboxUi.selectedEditorKind())
        check("the next step opens in that diff", second in openInDiff, openInDiff)

        SandboxUi.closeAllEditors()
        SandboxUi.openReviewDiff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        Thread.sleep(2_000)
        SandboxUi.openThreadInDiff(first, REVIEW_AFTER)
        Thread.sleep(1_000)
        SandboxUi.pressPanelAction(first, "Next Step")
        Thread.sleep(2_000)
        report("INFO  next step in a revision-copy diff lands in: ${SandboxUi.selectedEditorKind()}")
    }

    /** PR #4: Ctrl+wheel zoom with a thread open keeps the view where the mouse points. */
    @Test
    @Order(4)
    fun zoom() = case("zoom") {
        val file = "src/long_module.py"
        val paragraphs = (0 until 30).joinToString("\n\n") { i -> "Paragraph $i: the body is long so its end is far below the line it is anchored on." }
        val thread = Agent.agentAddsLineThread(file, 92, "A long thread.\n\n$paragraphs")
        SandboxUi.openInEditor(file)
        Thread.sleep(1_000)
        SandboxUi.openThreadInEditor(thread, file)
        Thread.sleep(1_000)
        SandboxUi.scrollEditorTo(file, 70)
        Thread.sleep(1_000)
        val sizeBefore = SandboxUi.editorFontSize(file)
        val before = SandboxUi.lineUnderPoint(file, 200, 150)
        SandboxUi.ctrlWheelZoom(file, 200, 150)
        // Time for the open thread to re-lay out and move the view away from the mouse, which it must not.
        Thread.sleep(2_000)
        val (sizeAfter, after) = readUntil(
            "the zoomed view with the same line under the mouse",
            read = { SandboxUi.editorFontSize(file) to SandboxUi.lineUnderPoint(file, 200, 150) },
        ) { (size, line) -> size > sizeBefore && abs(line - before) <= 2 }
        check("the zoom happened", sizeAfter > sizeBefore, sizeAfter)
        check("the line under the mouse stays put", abs(after - before) <= 2, "$before -> $after")
    }

    /** Resolve with auto-advance in a diff of a revision's copy (an MR not checked out), the way GitLab shows it. */
    @Test
    @Order(5)
    @EnabledIfEnvironmentVariable(named = "MARGINALIS_SANDBOX_KNOWN_GAPS", matches = "true", disabledReason = "a known gap, not in the default run")
    fun resolveInReviewDiff() = case("resolve_in_review_diff") {
        val first = Agent.agentAddsLineThread(REVIEW_FILE, 2, "Resolve me: parse", order = 1)
        val second = Agent.agentAddsLineThread(REVIEW_FILE, 6, "Then me: total", order = 2)
        Thread.sleep(1_000)
        SandboxUi.closeAllEditors()
        SandboxUi.openReviewDiff(REVIEW_FILE, REVIEW_BEFORE, REVIEW_AFTER)
        Thread.sleep(2_000)
        SandboxUi.openThreadInDiff(first, REVIEW_AFTER)
        Thread.sleep(1_000)
        SandboxUi.pressPanelAction(first, "Resolve")
        val openInDiff = openInDiffUntil(second, REVIEW_AFTER)
        val kind = SandboxUi.selectedEditorKind()
        check("Resolve in a review diff keeps the diff in front", kind == "DIFF", kind)
        check("the next step opens in that diff", second in openInDiff, openInDiff)
        SandboxUi.closeAllEditors()
        SandboxUi.openLocalDiff(REVIEW_FILE, REVIEW_AFTER)
        Thread.sleep(2_000)
        SandboxUi.openThreadInDiff(second, REVIEW_BEFORE)
        Thread.sleep(1_000)
        SandboxUi.pressPanelAction(second, "Resolve")
        Thread.sleep(2_000)
        report("INFO  resolve in a local diff (checked-out branch) lands in: ${SandboxUi.selectedEditorKind()}")
    }

    private fun iconLinesUntil(expected: List<Int>): List<Int> =
        readUntil("thread icons on the diff's lines $expected", read = { SandboxUi.diffIconLines(REVIEW_AFTER) }) { it == expected }

    private fun openInDiffUntil(threadId: String, shownText: String): List<String> =
        readUntil("thread $threadId open in the diff", read = { SandboxUi.threadIdsOpenInDiff(shownText) }) { threadId in it }

    private companion object {
        const val REVIEW_FILE = "src/review_target.py"

        /** The PR's version: three lines added on top (every surviving line moves down by three), legacy_helper removed. */
        val REVIEW_AFTER = listOf(
            "import csv",
            "",
            "",
            "def parse(text: str) -> list[str]:",
            "    return text.split(\",\")",
            "",
            "",
            "def total(values: list[int]) -> int:",
            "    return sum(values)",
        ).joinToString("\n", postfix = "\n")
        val REVIEW_BEFORE = Sandbox.harness.resolve("project-template").resolve(REVIEW_FILE).readText().replace("\r\n", "\n")

        val WIDE_TABLE = (1..24).joinToString(" | ", "| ", " |\n") { i -> "column $i" } +
            "|" + "---|".repeat(24) + "\n" +
            (1..24).joinToString(" | ", "| ", " |\n") { i -> "value $i" }
    }
}
