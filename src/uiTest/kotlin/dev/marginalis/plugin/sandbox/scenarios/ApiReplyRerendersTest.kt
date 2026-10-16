package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * An open thread in the editor shows a reply the agent posts through the HTTP API (MARG-14).
 *
 * Each case opens a thread, lets the agent reply through `comment_reply`, and reads what the open panel renders: the
 * reply among the messages, and a panel at least as tall as it needs (an inlay that does not grow hides the new
 * message below its edge).
 */
class ApiReplyRerendersTest : SandboxScenario("api_reply_rerenders") {

    @BeforeAll
    fun openTheFile() {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
    }

    @Test
    @Order(1)
    fun agentRepliesToAnOpenThread() = case("agent_replies_to_an_open_thread") {
        val thread = Agent.agentAddsLineThread(FILE, 1, "Does this parse quoted fields?")
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        val reply = "Reply A, posted through the API while the thread is open."
        Agent.agentReplies(thread, reply)
        expectReplyShown("plain", thread, reply)
    }

    @Test
    @Order(2)
    fun agentRepliesAfterTheUserSubmitted() = case("agent_replies_after_the_user_submitted") {
        val thread = Agent.agentAddsLineThread(FILE, 6, "Should total() accept floats?")
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        SandboxUi.userReplies(thread, "Yes, floats too.")
        settle()
        val reply = "Reply B, after the user's own reply."
        Agent.agentReplies(thread, reply)
        expectReplyShown("after-user-reply", thread, reply)
    }

    @Test
    @Order(3)
    fun agentRepliesToAnOpenWalkthroughStep() = case("agent_replies_to_an_open_walkthrough_step") {
        val thread = Agent.agentAddsStep(FILE, 9, "Step 1: the legacy helper.", order = 1, walkthrough = "repro")
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        SandboxUi.userReplies(thread, "Why split on commas?")
        settle()
        val reply = "Reply C, in a walkthrough step."
        Agent.agentReplies(thread, reply)
        expectReplyShown("walkthrough-step", thread, reply)
    }

    @Test
    @Order(4)
    fun agentRepliesToAProjectStepInTheMarginTab() = case("agent_replies_to_a_project_step_in_the_margin_tab") {
        val thread = Agent.agentAddsProjectStep("Step 1: the plan, about the whole project.", order = 1, walkthrough = "tab")
        SandboxUi.openMarginTab(thread)
        settle()
        SandboxUi.userReplies(thread, "What about the tests?")
        settle()
        val reply = "Reply D, in a project-level step in the Margin tab."
        Agent.agentReplies(thread, reply)
        expectReplyShown("margin-tab-step", thread, reply)
    }

    private fun expectReplyShown(case: String, threadId: String, reply: String) {
        settle()
        val view = SandboxUi.panelView(threadId)
        shot(case)
        check("$case: the open thread renders the agent's reply", reply in view.texts, view.texts)
        check("$case: the panel grows to fit it", view.height >= view.preferredHeight, "height ${view.height} < preferred ${view.preferredHeight}")
    }

    private companion object {
        const val FILE = "src/review_target.py"

        fun settle() = Thread.sleep(2_000)
    }
}
