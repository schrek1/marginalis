package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

/**
 * A thread left open shows the agent's reply that arrived while its tab was in the background (MARG-50).
 *
 * The user writes into an open thread, moves to another tab while the agent works, the agent replies through
 * `comment_reply` while it also holds a `comment_wait` long-poll, and the user comes back. A background editor tab
 * is taken out of the Swing hierarchy, so the open panel misses the reply unless it catches up when it is shown
 * again. The control case replies while the panel stays in view.
 */
class ApiReplyWhileAwayTest : SandboxScenario("api_reply_while_away") {

    @BeforeAll
    fun showToolWindow() = SandboxUi.showToolWindow()

    @Test
    @Order(1)
    fun agentRepliesWhileThePanelIsInView() = case("agent_replies_while_the_panel_is_in_view") {
        val case = "control-in-view"
        val thread = Agent.agentAddsLineThread(FILE, 6, "Should total() accept floats?")
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        SandboxUi.userReplies(thread, "Yes, floats too. Can you check the callers?")
        settle()
        val reply = "Reply E, while the thread stays in view."
        agentRepliesWhileListening(case, thread, reply)
        expectReplyShown(case, thread, reply)
    }

    @Test
    @Order(2)
    fun agentRepliesWhileTheEditorTabIsInTheBackground() = case("agent_replies_while_the_editor_tab_is_in_the_background") {
        val case = "editor-tab-away"
        val thread = Agent.agentAddsStep(FILE, 9, "Step 7: the legacy helper.", order = 7, walkthrough = "away")
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        SandboxUi.userReplies(thread, "Why split on commas here?")
        settle()
        SandboxUi.openInEditor(OTHER_FILE)
        val onScreen = readUntil("$case: the thread's editor tab off screen", read = { editorOnScreen(FILE) }) { shown -> !shown }
        check("$case: the thread's editor tab left the screen", !onScreen)
        val reply = "Reply F, posted while the thread's editor tab was in the background."
        agentRepliesWhileListening(case, thread, reply)
        SandboxUi.openInEditor(FILE)
        expectReplyShown(case, thread, reply)
    }

    @Test
    @Order(3)
    fun agentRepliesWhileTheMarginTabIsInTheBackground() = case("agent_replies_while_the_margin_tab_is_in_the_background") {
        val case = "margin-tab-away"
        val thread = Agent.agentAddsProjectStep("Step 1: the release plan for SHOP-42.", order = 1, walkthrough = "away-tab")
        SandboxUi.openMarginTab(thread)
        settle()
        SandboxUi.userReplies(thread, "Does the plan cover the shop-api tests?")
        settle()
        SandboxUi.openInEditor(FILE)
        val onScreen = readUntil("$case: the Margin tab off screen", read = { marginTabOnScreen() }) { shown -> !shown }
        check("$case: the Margin tab left the screen", !onScreen)
        val reply = "Reply G, posted while the Margin tab was in the background."
        agentRepliesWhileListening(case, thread, reply)
        SandboxUi.openMarginTab(thread)
        expectReplyShown(case, thread, reply)
    }

    private fun agentRepliesWhileListening(case: String, threadId: String, reply: String) {
        val listening = AgentListening()
        check("$case: the agent's long-poll is open", listening.open, listening.error)
        Agent.agentReplies(threadId, reply)
        // Time for the reply to end the long-poll, which it must not.
        settle()
        check("$case: the long-poll is still open after the reply", listening.open, listening.error)
        listening.close()
    }

    private fun expectReplyShown(case: String, threadId: String, reply: String) {
        val view = readUntil("$case: the reply shown in a panel grown to fit it", read = { SandboxUi.panelView(threadId) }) { view ->
            reply in view.texts && view.height >= view.preferredHeight
        }
        shot(case)
        check("$case: the open thread renders the agent's reply", reply in view.texts, view.texts)
        check("$case: the panel grows to fit it", view.height >= view.preferredHeight, "height ${view.height} < preferred ${view.preferredHeight}")
    }

    private fun editorOnScreen(file: String): Boolean =
        SandboxUi.js(SandboxUi.EDITORS + "String(textEditorOf('$file').getContentComponent().isDisplayable())") == "true"

    private fun marginTabOnScreen(): Boolean =
        SandboxUi.marginJs("String(marginPanel().getComponent().isDisplayable())") == "true"

    /** The agent's `comment_wait`, held open in the background as a real agent holds it between replies. */
    private class AgentListening {
        @Volatile
        var error: Exception? = null
        private val waiting = thread(isDaemon = true) {
            try {
                Agent.agentWaits(LONG_POLL_SECONDS)
            } catch (failure: Exception) {
                error = failure
            }
        }

        init {
            settle()
        }

        val open: Boolean get() = waiting.isAlive && error == null

        fun close() = waiting.join((LONG_POLL_SECONDS + 30) * 1_000L)
    }

    private companion object {
        const val FILE = "src/review_target.py"
        const val OTHER_FILE = "src/greeting.py"
        const val LONG_POLL_SECONDS = 30

        fun settle() = Thread.sleep(2_000)
    }
}
