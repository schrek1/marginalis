package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.ToolWindowUi
import dev.marginalis.plugin.sandbox.ToolWindowUi.ShapeNode
import dev.marginalis.plugin.sandbox.fixtures.DeepWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.InterleavedWalkthrough
import dev.marginalis.plugin.sandbox.fixtures.postWalkthrough
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

/**
 * Guided layout (fork MARG-10, MARG-47): the tool window lays a walkthrough out by step number (the default;
 * consecutive steps in one file fold under it, consecutive project-level steps under a project node), by folder or by
 * file, remembers the choice per project, and the step arrows follow step numbers in every layout; a port of
 * `sandbox/scenarios/guided_layout.py`.
 */
class GuidedLayoutTest : SandboxScenario("guided_layout") {

    @BeforeAll
    fun postThreeWalkthroughs() {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        postWalkthrough(InterleavedWalkthrough)
        for ((order, file, line) in PROJECT_FIRST_STEPS) {
            if (file == null || line == null) {
                Agent.agentAddsProjectStep("Step $order: overview", order, PROJECT_FIRST_LABEL)
            } else {
                Agent.agentAddsStep(file, line, "Step $order: review note", order, PROJECT_FIRST_LABEL)
            }
        }
        Thread.sleep(3_000)
    }

    @Test
    @Order(1)
    fun defaultIsTheStepNumberList() = case("default_is_the_step_number_list") {
        val shape = ToolWindowUi.guidedShape()
        check("by default the layout is By step number", ToolWindowUi.guidedLayout() == "BY_STEP_NUMBER", ToolWindowUi.guidedLayout())
        check("by default the steps read in step order", topLevelSteps(shape) == OPEN_STEPS, topLevelSteps(shape))
    }

    @Test
    @Order(2)
    fun byFolderGroupsUnderFolders() = case("by_folder_groups_under_folders") {
        ToolWindowUi.chooseGuidedLayout("By folder")
        pause()
        val shape = ToolWindowUi.guidedShape()
        toolWindowShot("1-by-folder")
        check("By folder groups the steps under folders", shape.any { it.kind == "DirNode" }, shape)
    }

    @Test
    @Order(3)
    fun byStepNumberListsStepsInOrder() = case("by_step_number_lists_steps_in_order") {
        ToolWindowUi.chooseGuidedLayout("By step number")
        pause()
        val shape = ToolWindowUi.guidedShape()
        toolWindowShot("2-by-step-number")
        val kinds = shape.map { it.kind }.toSet()
        check("By step number has no folder nodes", kinds.all { it in setOf("ThreadNode", "FileNode") }, kinds)
        check("By step number lists the steps 1, 2, 3 … in order", topLevelSteps(shape) == OPEN_STEPS, topLevelSteps(shape))
        check("each lone step's row names its file", shape.filter { it.kind == "ThreadNode" }.all { it.showsFile == true })
    }

    @Test
    @Order(4)
    fun byStepNumberFoldsRunsInOneFile() = case("by_step_number_folds_runs_in_one_file") {
        val shape = ToolWindowUi.guidedShape(HOPPING)
        toolWindowShot("3-by-step-number-hopping")
        val steps = shape.map { it.steps }
        check(
            "in a hopping walk, consecutive steps in one file fold under one node and lone steps stay rows",
            steps == HOPPING_BY_STEP, steps,
        )
        val runs = shape.filter { it.kind == "FileNode" }
        check(
            "every folded run holds two or more steps, four runs in all",
            runs.all { it.steps.size > 1 } && runs.size == 4, runs.map { it.name to it.steps },
        )
        check(
            "a file the walk comes back to appears again in step order",
            shape.filter { it.name == InterleavedWalkthrough.SERVICE }.map { it.steps } == listOf(listOf(2, 3)) &&
                listOf(6) in steps && listOf(9) in steps,
            shape.map { it.name to it.steps },
        )
    }

    @Test
    @Order(5)
    fun byStepNumberFoldsProjectLevelRuns() = case("by_step_number_folds_project_level_runs") {
        val shape = ToolWindowUi.guidedShape(PROJECT_FIRST)
        toolWindowShot("3b-by-step-number-project-first")
        val kindsAndSteps = shape.map { it.kind to it.steps }
        check(
            "consecutive project-level steps fold under a project node and a lone one stays a row",
            kindsAndSteps == PROJECT_FIRST_BY_STEP, kindsAndSteps,
        )
        check(
            "a project-level run after file steps folds under a project node of its own",
            shape.filter { it.kind == "ProjectNode" }.map { it.steps } == listOf(listOf(1, 2, 3), listOf(8, 9)), kindsAndSteps,
        )
        ToolWindowUi.selectStep(1, PROJECT_FIRST)
        pause()
        val seen = mutableListOf(ToolWindowUi.selectedStep()!!.order)
        repeat(PROJECT_FIRST_STEPS.size - 1) {
            ToolWindowUi.pressToolWindowAction(*NEXT)
            pause()
            seen += ToolWindowUi.selectedStep()!!.order
        }
        val expected = PROJECT_FIRST_STEPS.map { it.order }
        check("Next walks into, through and out of the project runs in step order", seen == expected, "$seen vs $expected")
    }

    @Test
    @Order(6)
    fun byFileHasOneNodePerFile() = case("by_file_has_one_node_per_file") {
        ToolWindowUi.chooseGuidedLayout("By file")
        pause()
        val shape = ToolWindowUi.guidedShape()
        toolWindowShot("4-by-file")
        val files = shape.filter { it.kind == "FileNode" }
        check("By file has a file node per file and no folders", files.size == shape.size && files.size > 1, shape.map { it.kind })
        check("a file node shows the file's full path", files.all { "/" in it.name.orEmpty() }, files.map { it.name }.take(3))
        val firsts = files.map { it.steps.min() }
        check("files follow their first step", firsts == firsts.sorted(), firsts)
        check("steps inside a file follow their numbers", files.all { it.steps == it.steps.sorted() })
        val hoppingFiles = ToolWindowUi.guidedShape(HOPPING).associate { it.name to it.steps }
        check("in a hopping walk, By file gathers every step of a file", hoppingFiles[InterleavedWalkthrough.SERVICE] == listOf(2, 3, 6, 9), hoppingFiles)
    }

    @Test
    @Order(7)
    fun arrowsFollowStepNumbersByFile() = case("arrows_follow_step_numbers_by_file") {
        ToolWindowUi.selectStep(OPEN_STEPS.first())
        pause()
        val seen = mutableListOf(ToolWindowUi.selectedStep()!!.order)
        repeat(5) {
            ToolWindowUi.pressToolWindowAction(*NEXT)
            pause()
            seen += ToolWindowUi.selectedStep()!!.order
        }
        check("in By file Next walks the step numbers", seen == OPEN_STEPS.take(6), "$seen vs ${OPEN_STEPS.take(6)}")
    }

    @Test
    @Order(8)
    fun choiceSurvivesARestart() = case("choice_survives_a_restart") {
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        SandboxUi.showToolWindow()
        check("the project's layout survives a restart", ToolWindowUi.guidedLayout() == "BY_FILE", ToolWindowUi.guidedLayout())
        val workspace = Sandbox.project.resolve(".idea").resolve("workspace.xml").readText()
        check("the choice lives in workspace.xml", "name=\"MarginalisGuidedLayout\"" in workspace)
    }

    private fun topLevelSteps(shape: List<ShapeNode>): List<Int> = shape.flatMap { it.steps }

    private fun toolWindowShot(name: String) = ToolWindowUi.screenshotToolWindow(shots.resolve("$name.png"))

    private data class ProjectFirstStep(val order: Int, val file: String?, val line: Int?)

    private companion object {
        val OPEN_STEPS = DeepWalkthrough.steps.map { it.order }.filter { it !in DeepWalkthrough.resolved }.sorted()
        val HOPPING = "Guided ${InterleavedWalkthrough.label}"

        /** The hopping walk by step number: runs of consecutive steps in one file fold, a lone step stays a row. */
        val HOPPING_BY_STEP = listOf(
            listOf(2, 3), listOf(4, 5), listOf(6), listOf(7), listOf(8), listOf(9), listOf(10), listOf(11),
            listOf(12, 13), listOf(14), listOf(15, 16), listOf(17), listOf(18),
        )

        // Opens with project-level steps (no file) and comes back to them after file steps; its label sorts after the
        // other two, so the plain "Guided" prefix still finds the 33-step walk.
        const val PROJECT_FIRST_LABEL = "SHOP-90"
        const val PROJECT_FIRST = "Guided $PROJECT_FIRST_LABEL"
        val PROJECT_FIRST_STEPS = listOf(
            ProjectFirstStep(1, null, null),
            ProjectFirstStep(2, null, null),
            ProjectFirstStep(3, null, null),
            ProjectFirstStep(4, InterleavedWalkthrough.CONTROLLER, 12),
            ProjectFirstStep(5, null, null),
            ProjectFirstStep(6, InterleavedWalkthrough.SERVICE_TEST, 14),
            ProjectFirstStep(7, InterleavedWalkthrough.SERVICE_TEST, 36),
            ProjectFirstStep(8, null, null),
            ProjectFirstStep(9, null, null),
        )

        /** By step number: a run of two or more project-level steps folds under a project node, a lone one stays a row. */
        val PROJECT_FIRST_BY_STEP = listOf(
            "ProjectNode" to listOf(1, 2, 3),
            "ThreadNode" to listOf(4),
            "ThreadNode" to listOf(5),
            "FileNode" to listOf(6, 7),
            "ProjectNode" to listOf(8, 9),
        )

        val NEXT = arrayOf("Next Step", "Next Occurrence")

        fun pause() = Thread.sleep(1_500)
    }
}
