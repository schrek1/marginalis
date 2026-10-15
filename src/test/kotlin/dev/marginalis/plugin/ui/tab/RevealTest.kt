package dev.marginalis.plugin.ui.tab

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.ui.MarginalisPalette
import dev.marginalis.plugin.ui.WalkthroughNavigator

class RevealTest : MarginTabTestCase() {

    private fun walkthrough(): List<String> {
        sample()
        val items = listOf(
            mapOf("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "order" to 1, "walkthrough" to "tour", "body" to "Step one"),
            mapOf("order" to 2, "walkthrough" to "tour", "body" to "Step two is about the project"),
            mapOf("file" to SAMPLE, "line" to 9, "anchor_text" to lineText(9), "order" to 3, "walkthrough" to "tour", "body" to "Step three"),
        )
        return agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results").map { it.asJsonObject["thread_id"].asString }
    }

    fun testAProjectStepOfAWalkthroughOpensTheTabAtThatThread() {
        val steps = walkthrough()
        tab.click("Step two")
        val stepOne = user.open(steps[0])

        user.press(stepOne, "Next Step")

        assertTrue(tab.isInFront)
        assertTrue(shown().toString(), shown().contains("✉ Step two is about the project ● [expanded]"))
        assertEquals(MarginalisPalette.ACCENT.rgb, tab.outlineColor("Step two").rgb)
    }

    fun testTheRevealedThreadsOutlineFadesBackAfterAMoment() {
        val steps = walkthrough()
        user.press(user.open(steps[0]), "Next Step")
        assertEquals(MarginalisPalette.ACCENT.rgb, tab.outlineColor("Step two").rgb)

        PlatformTestUtil.waitWithEventsDispatching("the outline never faded", { tab.outlineColor("Step two").rgb != MarginalisPalette.ACCENT.rgb }, 5)
    }

    fun testStepsInTheTabWalkOnToTheNextFile() {
        walkthrough()

        user.press(tab.panel("Step two"), "Next Step")

        assertFalse(tab.isInFront)
        assertEquals(9, user.editor().caretModel.logicalPosition.line + 1)
        assertTrue(user.shownText(user.panel()).contains("Step three"))
    }

    fun testAWalkthroughsProjectStepsReadInStepOrder() {
        val items = (1..3).map { mapOf("order" to it, "walkthrough" to "plan", "body" to "Plan step $it") }

        agent.post("comment_add_batch", "items" to items)

        assertEquals(listOf("Plan step 1", "Plan step 2", "Plan step 3"), shown().map { it.removePrefix("✉ ").removeSuffix(" ● [expanded]") })
    }

    fun testAReferenceToAProjectThreadOpensItInTheTabEvenWhenFolded() {
        sample()
        val target = agent.ask("The release plan")
        tab.click("The release plan")
        val citing = agent.addOnLine(5, "body" to "See mg:${target.take(8)} for the plan")
        val panel = user.open(citing)

        user.followLink(panel, "mg:${target.take(8)}")

        assertTrue(tab.isInFront)
        assertEquals(listOf("✉ The release plan ● [expanded]"), shown())
    }

    fun testAReferenceToAResolvedProjectThreadOpensTheResolvedFold() {
        sample()
        val target = agent.ask("The old plan")
        agent.post("comment_resolve", "thread_id" to target)
        val panel = user.open(agent.addOnLine(5, "body" to "As in mg:${target.take(8)}"))

        user.followLink(panel, "mg:${target.take(8)}")

        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD, "▾ 1 resolved", "The old plan ● [expanded]"), shown())
    }

    fun testRevealingAThreadTheSearchHidesClearsTheSearch() {
        sample()
        val target = agent.ask("The release plan")
        agent.ask("Something else")
        tab.find("else")
        val panel = user.open(agent.addOnLine(5, "body" to "See mg:${target.take(8)}"))

        user.followLink(panel, "mg:${target.take(8)}")

        assertNull(tab.filterText())
        assertEquals(2, shown().size)
    }

    fun testAReferenceToAMessageOpensItsThreadInTheTab() {
        sample()
        val target = agent.ask("The release plan")
        val reply = agent.reply(target, "Second thought").json["message_id"]!!.asString
        tab.openFileInFront(SAMPLE)
        val panel = user.open(agent.addOnLine(5, "body" to "See mg:${reply.take(8)}"))

        user.followLink(panel, "mg:${reply.take(8)}")

        assertTrue(tab.isInFront)
        assertEquals(listOf("✉ The release plan ● [expanded]"), shown())
    }

    fun testOpenThreadOnANotificationShowsTheProjectThreadInTheTab() {
        val target = agent.ask("The release plan")
        tab.openFileInFront(sample())
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "Marginalis") shown += notification
            }
        })
        tab.click("The release plan")

        agent.reply(target, "Any thoughts?")
        tab.settle()
        val open = shown.single().actions.single { it.templateText == "Open Thread" }
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(Notification.KEY, shown.single())
            .build()
        Notification.fire(shown.single(), open, context)
        tab.settle()

        assertTrue(tab.isInFront)
        assertEquals(listOf("✉ The release plan ● [expanded]"), shown())
    }

    fun testARevealedThreadWaitsForTheTabWhenItIsClosed() {
        val target = agent.ask("The release plan")
        tab.click("The release plan")
        tab.close()

        WalkthroughNavigator.navigateTo(project, user.thread(target))
        tab.settle()

        assertTrue(tab.isInFront)
        assertEquals(listOf("✉ The release plan ● [expanded]"), shown())
    }
}
