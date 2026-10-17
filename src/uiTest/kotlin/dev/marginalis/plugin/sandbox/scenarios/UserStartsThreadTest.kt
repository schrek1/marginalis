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

/**
 * The user starts a thread in a real IDE (MARG-106) through the plugin's own editor actions: on the caret's line
 * ("Add Marginalis Comment"), on a selection (the same action), and on the whole file ("Comment on File"). After
 * Submit the agent's `comment_list` returns each with its anchor: line and anchor text, the selected words as the
 * segment, or no line at all.
 */
@Tag("core")
class UserStartsThreadTest : SandboxScenario("user_starts_thread") {

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun userStartsAThreadOnTheCaretsLine() = case("user_starts_a_thread_on_the_carets_line") {
        SandboxUi.openInEditor(GREETING)
        SandboxUi.moveCaret(GREETING, 5)

        val thread = startAndSubmit(GREETING, ADD_COMMENT, "Should farewell() trim the name too?")

        check("the agent lists the thread on the caret's line", thread.get("line")?.asInt == 5, thread)
        check("its anchor text is that line", thread.get("anchor_text")?.asString == "def farewell(name: str) -> str:", thread)
        check("a caret alone makes no segment", thread.get("segment") == null, thread)
        check("the user wrote it", authorKind(thread) == "user", thread)
    }

    @Test
    @Order(2)
    fun userStartsAThreadOnASelection() = case("user_starts_a_thread_on_a_selection") {
        SandboxUi.openInEditor(REVIEW_TARGET)
        SandboxUi.selectText(REVIEW_TARGET, 2, SELECTED)

        val thread = startAndSubmit(REVIEW_TARGET, ADD_COMMENT, "Commas only, or whitespace too?")

        check("the agent lists the thread on the selection's line", thread.get("line")?.asInt == 2, thread)
        check("the selected words are its segment", thread.getAsJsonObject("segment")?.get("exact")?.asString == SELECTED, thread)
    }

    @Test
    @Order(3)
    fun userStartsAThreadOnTheWholeFile() = case("user_starts_a_thread_on_the_whole_file") {
        SandboxUi.openInEditor(LONG_MODULE)

        val thread = startAndSubmit(LONG_MODULE, COMMENT_ON_FILE, "This module needs a docstring.")

        check("the agent lists a file-level thread: no line", thread.get("line") == null, thread)
        check("it is about the file", thread.get("file")?.asString == LONG_MODULE, thread)
    }

    /** Runs the action, submits the draft it opens, and returns the thread as the agent lists it. */
    private fun startAndSubmit(file: String, actionId: String, body: String): JsonObject {
        SandboxUi.runEditorAction(file, actionId)
        val draft = SandboxUi.openDraftId()
        SandboxUi.userSubmitsDraft(draft, body)
        return readUntil("the agent lists the new thread", read = { listed(file, draft) }) { it != null }
            ?: error("the agent's comment_list has no thread $draft in $file")
    }

    private fun listed(file: String, threadId: String): JsonObject? =
        Agent.apiGet("comment_list", mapOf("file" to file)).getAsJsonArray("threads")
            .map { it.asJsonObject }
            .firstOrNull { it.get("thread_id").asString == threadId }

    private fun authorKind(thread: JsonObject): String? =
        thread.getAsJsonArray("messages")?.firstOrNull()?.asJsonObject?.getAsJsonObject("author")?.get("kind")?.asString

    private companion object {
        const val GREETING = "src/greeting.py"
        const val REVIEW_TARGET = "src/review_target.py"
        const val LONG_MODULE = "src/long_module.py"
        const val ADD_COMMENT = "marginalis.addComment"
        const val COMMENT_ON_FILE = "marginalis.commentOnFile"
        const val SELECTED = "text.split(\",\")"
    }
}
