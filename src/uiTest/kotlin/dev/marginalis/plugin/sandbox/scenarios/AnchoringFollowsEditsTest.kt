package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * A thread follows its code in a real editor (MARG-107): lines inserted above move its marker and its listed line;
 * erasing the anchored line orphans it; `comment_reanchor` brings it back where the code now lives, and
 * `comment_reanchor_all` rescues every thread of a rewritten file at once. Checked in the editor (the marker) and
 * through the agent's `comment_list`. The cases build on each other in order.
 */
@Tag("core")
class AnchoringFollowsEditsTest : SandboxScenario("anchoring_follows_edits") {

    private lateinit var thread: String

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun linesInsertedAboveMoveTheThread() = case("lines_inserted_above_move_the_thread") {
        thread = Agent.agentAddsLineThread(FILE, 9, "Should step_3 guard against negatives?")
        SandboxUi.openInEditor(FILE)
        readUntil("the marker on line 9", read = { SandboxUi.editorIconLines(FILE) }) { 8 in it }

        SandboxUi.insertBeforeLine(FILE, 1, "# one\n# two\n# three\n")

        val icons = readUntil("the marker moves down three lines", read = { SandboxUi.editorIconLines(FILE) }) { it == listOf(11) }
        check("the marker moves with its line", icons == listOf(11), icons)
        val listed = readUntil("the listed line follows", read = { listed(thread) }) { it.get("line")?.asInt == 12 }
        check("the agent lists the thread on the moved line", listed.get("line")?.asInt == 12, listed)
        check("it stays open", status(listed) == "open", listed)
    }

    @Test
    @Order(2)
    fun erasingTheAnchoredLineOrphansTheThread() = case("erasing_the_anchored_line_orphans_the_thread") {
        SandboxUi.eraseLine(FILE, 12)

        val listed = readUntil("the thread orphans", read = { listed(thread) }) { status(it) == "orphaned" }
        check("erasing the anchored line orphans the thread", status(listed) == "orphaned", listed)
        val icons = SandboxUi.editorIconLines(FILE)
        check("no marker stays on a line that is gone", 11 !in icons, icons)
    }

    @Test
    @Order(3)
    fun reanchorBringsTheThreadBack() = case("reanchor_brings_the_thread_back") {
        SandboxUi.insertBeforeLine(FILE, 30, "$ANCHOR\n")
        check("the code lives on line 30 now", lineOf(ANCHOR) == 30, lineOf(ANCHOR))

        val answer = Agent.apiPost("comment_reanchor", mapOf("thread_id" to thread, "line" to 30, "anchor_text" to ANCHOR))

        check("comment_reanchor reopens the thread on the line given", answer.get("line")?.asInt == 30 && answer.get("status")?.asString == "open", answer)
        val icons = readUntil("the marker on line 30", read = { SandboxUi.editorIconLines(FILE) }) { 29 in it }
        check("the marker sits on the rescued line", 29 in icons, icons)
    }

    @Test
    @Order(5)
    fun reanchorAllRescuesARewrittenFile() = case("reanchor_all_rescues_a_rewritten_file") {
        val second = Agent.agentAddsLineThread(FILE, lineOf(SECOND_ANCHOR), "step_6 repeats step_5.")
        val gone = Agent.agentAddsLineThread(FILE, lineOf(GONE_ANCHOR), "step_7 could go.")

        // A tool's rewrite: every line new, the two anchored lines moved, step_7 gone.
        SandboxUi.rewriteFile(FILE, (1..40).joinToString("\n") { line ->
            when (line) {
                REWRITTEN_ANCHOR_LINE -> ANCHOR
                REWRITTEN_SECOND_LINE -> SECOND_ANCHOR
                else -> "# rewritten $line"
            }
        } + "\n")
        val threads = listOf(thread, second, gone)
        val orphaned = readUntil("the threads orphan", read = { threads.map { status(listed(it)) } }) { it.all { s -> s == "orphaned" } }
        check("a rewrite orphans every thread of the file", orphaned.all { it == "orphaned" }, orphaned)

        val answer = Agent.apiPost("comment_reanchor_all", mapOf("file" to FILE, "project" to Agent.PROJECT_NAME))
        val rescued = answer.getAsJsonArray("results").associate { result ->
            result.asJsonObject.get("thread_id").asString to result.asJsonObject.get("line")?.asInt
        }
        check("comment_reanchor_all finds each moved thread's code in the rewritten file",
            rescued[thread] == REWRITTEN_ANCHOR_LINE && rescued[second] == REWRITTEN_SECOND_LINE, "$rescued, answer $answer")
        check("a thread whose code is gone stays orphaned", rescued.containsKey(gone) && rescued[gone] == null && status(listed(gone)) == "orphaned", answer)
        check("the moved threads are open again", listOf(listed(thread), listed(second)).all { status(it) == "open" })
    }

    // FixMe: Known issue - #53 deleting a thread's line with Delete Line moves the thread onto the next line instead of orphaning it
    @Test
    @Order(4)
    @EnabledIfEnvironmentVariable(named = "MARGINALIS_SANDBOX_KNOWN_GAPS", matches = "true", disabledReason = "a known gap, not in the default run")
    fun deleteLineOrphansTheThread() = case("delete_line_orphans_the_thread") {
        val line = lineOf(DELETED_ANCHOR)
        val doomed = Agent.agentAddsLineThread(FILE, line, "step_9 is unused.")
        SandboxUi.moveCaret(FILE, line)

        SandboxUi.runEditorAction(FILE, "EditorDeleteLine")

        val listed = readUntil("the thread orphans", read = { listed(doomed) }) { status(it) == "orphaned" }
        check("Delete Line on the anchored line orphans the thread", status(listed) == "orphaned", listed)
    }

    private fun listed(threadId: String): JsonObject =
        Agent.apiGet("comment_list", mapOf("file" to FILE)).getAsJsonArray("threads")
            .map { it.asJsonObject }.first { it.get("thread_id").asString == threadId }

    private fun status(thread: JsonObject): String = thread.get("status").asString.lowercase()

    /** 1-based line of the first line that reads exactly `text`. */
    private fun lineOf(text: String): Int = SandboxUi.documentText(FILE).lines().indexOf(text) + 1

    private companion object {
        const val FILE = "src/long_module.py"
        const val ANCHOR = "def step_3(value: int) -> int:"
        const val SECOND_ANCHOR = "def step_6(value: int) -> int:"
        const val DELETED_ANCHOR = "def step_9(value: int) -> int:"
        const val GONE_ANCHOR = "def step_7(value: int) -> int:"
        const val REWRITTEN_ANCHOR_LINE = 33
        const val REWRITTEN_SECOND_LINE = 37
    }
}
