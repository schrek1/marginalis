package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.ToolWindowUi.ShapeNode
import dev.marginalis.plugin.sandbox.fixtures.InterleavedWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Grouping consecutive steps by file as a Settings default: a project that never chose follows it, a new project
 * included; a project that flips the toggle keeps its own choice. The Settings default is application-wide, so it is
 * switched off before the first fresh project and again at the end.
 */
class GroupingByDefaultTest : SandboxScenario("grouping_by_default") {

    @BeforeAll
    fun freshProjectWithoutTheSettingsDefault() {
        setAppDefault(false)
        restartWithTheHoppingWalk(fresh = true)
    }

    @AfterAll
    fun switchTheSettingsDefaultOff() = setAppDefault(false)

    @Test
    @Order(1)
    fun withoutTheDefaultNothingIsGrouped() = case("without_the_default_nothing_is_grouped") {
        val shape = shapeUntil("the hopping walk listed") { it.isNotEmpty() }
        toolWindowShot("1-not-grouped")
        check("without the Settings default a new project lists every step as its own row", shape.all { it.steps.size == 1 },
            shape.map { it.steps })
    }

    @Test
    @Order(2)
    fun aNewProjectFollowsTheDefault() = case("a_new_project_follows_the_default") {
        setAppDefault(true)
        restartWithTheHoppingWalk(fresh = true)
        val shape = shapeUntil("the hopping walk grouped into runs") { it.map(ShapeNode::steps) == HOPPING_GROUPED }
        toolWindowShot("2-new-project-grouped")
        check("a new project groups consecutive steps by file when Settings say so", shape.map { it.steps } == HOPPING_GROUPED,
            shape.map { it.steps })
    }

    @Test
    @Order(3)
    fun aProjectsOwnChoiceOutlastsTheDefault() = case("a_projects_own_choice_outlasts_the_default") {
        ToolWindowUi.chooseWalkthroughLayout(GROUP_CONSECUTIVE, selected = false)
        val turnedOff = shapeUntil("the project turned grouping off") { shape -> shape.all { it.steps.size == 1 } }
        toolWindowShot("3-project-turned-it-off")
        check("unticking the toggle ungroups the project under a ticked Settings default", turnedOff.all { it.steps.size == 1 },
            turnedOff.map { it.steps })
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        SandboxUi.showToolWindow()

        val shape = shapeUntil("the restarted project listed") { it.isNotEmpty() }
        check("the project's own off survives a restart, over the Settings default", shape.all { it.steps.size == 1 },
            shape.map { it.steps })
        val workspace = Sandbox.projectText(".idea/workspace.xml").orEmpty()
        check("the project's own off lives in workspace.xml", "name=\"groupConsecutiveSteps\" value=\"false\"" in workspace)
    }

    private fun restartWithTheHoppingWalk(fresh: Boolean) {
        Sandbox.restart(fresh)
        SandboxUi.showToolWindow()
        postWalkthrough(InterleavedWalkthrough)
    }

    private fun setAppDefault(enabled: Boolean) {
        SandboxUi.js(
            """
            var settings = com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(pluginLoader.loadClass('dev.marginalis.plugin.settings.MarginalisSettings'));
            settings.getState().setGroupConsecutiveStepsByDefault($enabled);
            String(true)
            """,
        )
    }

    private fun shapeUntil(what: String, done: (List<ShapeNode>) -> Boolean): List<ShapeNode> =
        readUntil(what, read = { ToolWindowUi.guidedShape(HOPPING) }, done = done)

    private fun toolWindowShot(name: String) = ToolWindowUi.screenshotToolWindow(shots.resolve("$name.png"))

    private companion object {
        val HOPPING = InterleavedWalkthrough.label
        const val GROUP_CONSECUTIVE = "Group Consecutive Steps by File"

        /** The hopping walk grouped, as in WalkthroughLayoutTest. */
        val HOPPING_GROUPED = listOf(
            listOf(1), listOf(2, 3), listOf(4, 5), listOf(6), listOf(7), listOf(8), listOf(9), listOf(10), listOf(11),
            listOf(12, 13), listOf(14), listOf(15, 16), listOf(17), listOf(18),
        )
    }
}
