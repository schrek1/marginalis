package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Margin tab order: oldest first by default, walkthrough steps in step order, the Sort menu, the new-thread draft on
 * the chosen side, and the choice remembered per project.
 *
 * Hold still's "N new threads" pill needs the tab in front, which the sandbox on another desktop never is; its
 * direction is covered by the core tests (HoldStillTest).
 */
class ProjectTabOrderTest : SandboxScenario("project_tab_order") {

    private val threads = mutableMapOf<String, String>()

    @Test
    @Order(1)
    fun defaultIsOldestFirst() = case("default_is_oldest_first") {
        listOf("a", "b", "c").forEach(::start)
        Agent.agentReplies(threads.getValue("a"), "news on the oldest thread")
        SandboxUi.openMarginTab(threads.getValue("a"))
        // Time for the reply to move the oldest thread, which it must not.
        settle()
        val oldestFirst = listedUntil("a", "b", "c") { it == listOf("a", "b", "c") }
        shot("1-default-oldest-first")
        check("by default the tab lists the oldest thread first, and a reply does not move it", oldestFirst == listOf("a", "b", "c"), oldestFirst)
    }

    @Test
    @Order(2)
    fun walkthroughStepsReadInStepOrder() = case("walkthrough_steps_read_in_step_order") {
        listOf(3, 1, 2).forEach(::step)
        val steps = listedUntil("step1", "step2", "step3") { it == listOf("step1", "step2", "step3") }
        shot("2-steps-in-step-order")
        check("project-level steps posted as 3, 1, 2 read 1, 2, 3", steps == listOf("step1", "step2", "step3"), steps)
    }

    @Test
    @Order(3)
    fun sortMenuSwitchesTheOrder() = case("sort_menu_switches_the_order") {
        SandboxUi.chooseMarginSort("Newest first")
        val listed = listedUntil("a", "b", "c", "step1", "step2", "step3") { it == listOf("step1", "step2", "step3", "c", "b", "a") }
        shot("3-newest-first")
        check("the Sort menu stores Newest first for the project", SandboxUi.marginSort() == "NEWEST_FIRST", SandboxUi.marginSort())
        check(
            "newest first lists the latest created thread first, steps still 1, 2, 3",
            listed == listOf("step1", "step2", "step3", "c", "b", "a"), listed,
        )

        SandboxUi.chooseMarginSort("Recent activity")
        Agent.agentReplies(threads.getValue("b"), "news on b")
        val byActivity = listedUntil("a", "b", "c") { it.take(1) == listOf("b") }
        shot("4-recent-activity")
        check("recent activity puts the thread with the latest reply first", byActivity.take(1) == listOf("b"), byActivity)
    }

    @Test
    @Order(4)
    fun draftSitsOnTheNewSide() = case("draft_sits_on_the_new_side") {
        SandboxUi.chooseMarginSort("Oldest first")
        SandboxUi.userStartsNewProjectThread()
        val oldestFirst = listedUntil("a", "b", "c") { it.takeLast(1) == listOf("draft") }
        shot("5-draft-oldest-first")
        check("with oldest first a new thread's draft sits below the threads", oldestFirst.takeLast(1) == listOf("draft"), oldestFirst)

        SandboxUi.chooseMarginSort("Newest first")
        val newestFirst = listedUntil("a", "b", "c") { it.take(1) == listOf("draft") }
        shot("6-draft-newest-first")
        check("with newest first the draft sits above the threads", newestFirst.take(1) == listOf("draft"), newestFirst)
    }

    @Test
    @Order(5)
    fun choiceSurvivesARestart() = case("choice_survives_a_restart") {
        SandboxUi.chooseMarginSort("Newest first")
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        check("the project's Sort choice survives a restart", SandboxUi.marginSort() == "NEWEST_FIRST", SandboxUi.marginSort())
        val workspace = Sandbox.projectText(".idea/workspace.xml").orEmpty()
        check("the choice lives in workspace.xml", "name=\"MarginalisProjectTabSort\"" in workspace)
    }

    /** The tab's order as scenario names, keeping only the given threads and the draft. */
    private fun listed(vararg names: String): List<String> {
        val byId = threads.entries.associate { (name, threadId) -> threadId to name }
        val wanted = names.toSet() + "draft"
        return SandboxUi.marginTabOrder().map { entry -> byId[entry] ?: entry }.filter { it in wanted }
    }

    private fun listedUntil(vararg names: String, done: (List<String>) -> Boolean): List<String> =
        readUntil("the Margin tab's order of ${names.toList()}", read = { listed(*names) }, done = done)

    private fun start(name: String) {
        threads[name] = Agent.agentStartsProjectThread("Thread $name")
        Thread.sleep(300)
    }

    private fun step(number: Int) {
        threads["step$number"] = Agent.agentAddsProjectStep("Step $number", order = number, walkthrough = "tour")
        Thread.sleep(300)
    }

    private fun settle() = Thread.sleep(1_500)
}
