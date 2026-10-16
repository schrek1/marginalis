package dev.marginalis.plugin.ui

import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.transport.HttpContractTestCase

class ReplyReachesAnOpenThreadTest : HttpContractTestCase() {

    override fun setUp() {
        super.setUp()
        sample()
    }

    fun testAnAgentReplyAfterTheUsersOwnReplyAppearsInTheOpenThread() {
        val thread = agent.addOnLine(6, "body" to "Should total() accept floats?")
        val panel = user.open(thread)
        user.type(panel, "Yes, floats too.")
        user.submit(panel)

        agent.reply(thread, "Reply B, after the user's own reply.")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(user.shownText(panel), user.shownText(panel).contains("Reply B, after the user's own reply."))
    }

    fun testAnAgentReplyAppearsInAnOpenWalkthroughStep() {
        val step = agent.addOnLine(9, "body" to "Step 1: the legacy helper.", "order" to 1, "walkthrough" to "repro")
        val panel = user.open(step)
        user.type(panel, "Why split on commas?")
        user.submit(panel)

        agent.reply(step, "Reply C, in a walkthrough step.")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(user.shownText(panel), user.shownText(panel).contains("Reply C, in a walkthrough step."))
    }

    fun testAnAgentReplyAppearsInAProjectStepOpenInTheMarginTab() {
        val step = agent.add("body" to "Step 1: the plan, about the whole project.", "order" to 1, "walkthrough" to "tab")
        val tab = user.openMarginTab()
        tab.panel("Step 1: the plan").let { panel ->
            user.type(panel, "What about the tests?")
            user.submit(panel)
        }

        agent.reply(step, "Reply D, in a project-level step in the Margin tab.")
        tab.settle()

        val panel = tab.panel("Step 1: the plan")
        assertTrue(user.shownText(panel), user.shownText(panel).contains("Reply D, in a project-level step in the Margin tab."))
    }
}
