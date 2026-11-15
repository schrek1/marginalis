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
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Walkthrough layout (upstream, replaces the fork's Guided layout): each walkthrough is its own section, listed in step
 * order with every row naming its file; the Walkthrough Layout menu switches to by folder or by file and can group
 * consecutive steps in one file; the project remembers both; the arrows walk open steps by number in every layout; a
 * walkthrough whose every step is resolved moves to Resolved.
 */
class WalkthroughLayoutTest : SandboxScenario("walkthrough_layout") {

    private lateinit var projectFirstSteps: Map<Int, String>

    @BeforeAll
    fun postThreeWalkthroughs() {
        SandboxUi.showToolWindow()
        postWalkthrough(DeepWalkthrough)
        postWalkthrough(InterleavedWalkthrough)
        projectFirstSteps = PROJECT_FIRST_STEPS.associate { (order, file, line) ->
            order to if (file == null || line == null) {
                Agent.agentAddsProjectStep("Step $order: overview", order, PROJECT_FIRST)
            } else {
                Agent.agentAddsStep(file, line, "Step $order: review note", order, PROJECT_FIRST)
            }
        }
    }

    @Test
    @Order(1)
    fun defaultListsEveryStepInStepOrder() = case("default_lists_every_step_in_step_order") {
        val shape = shapeUntil("every step listed in step order", DEEP) { steps(it) == ALL_DEEP_STEPS }
        toolWindowShot("1-step-order")
        check("by default the layout is By Step Number", ToolWindowUi.walkthroughLayout() == "BY_STEP_NUMBER", ToolWindowUi.walkthroughLayout())
        check("by default the steps read 1, 2, 3 …, resolved ones in their place", steps(shape) == ALL_DEEP_STEPS, steps(shape))
        check("by default nothing is grouped: every row is a step that names its file",
            shape.all { it.kind == "ThreadNode" && it.showsFile == true }, shape.map { it.kind to it.showsFile }.distinct())
    }

    @Test
    @Order(2)
    fun groupingFoldsRunsInOneFile() = case("grouping_folds_runs_in_one_file") {
        ToolWindowUi.chooseWalkthroughLayout(GROUP_CONSECUTIVE)
        val shape = shapeUntil("the hopping walk grouped into runs", HOPPING) { it.map(ShapeNode::steps) == HOPPING_GROUPED }
        toolWindowShot("2-grouped")
        check("consecutive steps in one file fold under that file, a lone step stays a row",
            shape.map { it.steps } == HOPPING_GROUPED, shape.map { it.steps })
        check("a file the walk comes back to appears again in step order",
            shape.filter { it.name == SERVICE }.map { it.steps } == listOf(listOf(2, 3)) && listOf(6) in shape.map { it.steps },
            shape.map { it.name to it.steps })
        val projectRuns = shapeUntil("the project-level runs grouped", PROJECT_FIRST) { it.any { node -> node.kind == "ProjectNode" } }
            .filter { it.kind == "ProjectNode" }.map { it.steps }
        check("consecutive project-level steps fold under a project node", projectRuns == listOf(listOf(1, 2, 3), listOf(8, 9)), projectRuns)
    }

    @Test
    @Order(3)
    fun byFolderGroupsUnderFolders() = case("by_folder_groups_under_folders") {
        ToolWindowUi.chooseWalkthroughLayout("By Folder")
        val shape = shapeUntil("the steps grouped under folders", DEEP) { shape -> shape.any { it.kind == "DirNode" } }
        toolWindowShot("3-by-folder")
        check("By Folder groups the steps under folders", shape.any { it.kind == "DirNode" }, shape.map { it.kind }.distinct())
    }

    @Test
    @Order(4)
    fun byFileHasOneNodePerFile() = case("by_file_has_one_node_per_file") {
        ToolWindowUi.chooseWalkthroughLayout("By File")
        val shape = shapeUntil("the hopping walk's service gathered in one node", HOPPING) { shape ->
            shape.any { it.name == SERVICE && it.steps == listOf(2, 3, 6, 9) }
        }
        toolWindowShot("4-by-file")
        check("By File gathers every step of a file in one node", shape.single { it.name == SERVICE }.steps == listOf(2, 3, 6, 9),
            shape.map { it.name to it.steps })
        val firsts = shape.map { it.steps.min() }
        check("files follow their first step", firsts == firsts.sorted(), firsts)
    }

    @Test
    @Order(5)
    fun arrowsWalkOpenStepsByNumber() = case("arrows_walk_open_steps_by_number") {
        val walk = OPEN_DEEP_STEPS.take(6)
        ToolWindowUi.selectStep(walk.first(), DEEP)
        val seen = mutableListOf(selectedOrderUntil(walk.first()))
        for (next in walk.drop(1)) {
            ToolWindowUi.pressToolWindowAction(*NEXT)
            seen += selectedOrderUntil(next)
        }
        check("in By File Next walks the open steps by number", seen == walk, "$seen vs $walk")
    }

    @Test
    @Order(6)
    fun choiceSurvivesARestart() = case("choice_survives_a_restart") {
        SandboxUi.saveProjectState()
        Sandbox.restart(fresh = false)
        SandboxUi.showToolWindow()
        check("the project's layout survives a restart", ToolWindowUi.walkthroughLayout() == "BY_FILE", ToolWindowUi.walkthroughLayout())
        val workspace = Sandbox.projectText(".idea/workspace.xml").orEmpty()
        check("the choice lives in workspace.xml", "name=\"MarginalisWalkthroughLayout\"" in workspace)
        check("grouping consecutive steps is remembered too", "name=\"groupConsecutiveSteps\" value=\"true\"" in workspace)
    }

    @Test
    @Order(7)
    fun aFinishedWalkthroughMovesToResolved() = case("a_finished_walkthrough_moves_to_resolved") {
        readUntil("the restarted IDE holds the walkthrough's steps again", read = { ToolWindowUi.treeThreadIds() }) {
            it.containsAll(projectFirstSteps.values)
        }
        projectFirstSteps.values.forEach(Agent::resolveThread)
        val sections = readUntil("the finished walkthrough leaves the top level", read = { ToolWindowUi.sectionsState().keys }) {
            PROJECT_FIRST !in it.map { title -> title.substringBefore("  ") }
        }
        val resolved = shapeUntil("the finished walkthrough listed under Resolved", "Resolved") { shape ->
            shape.any { it.kind == "WalkthroughNode" && it.steps.sorted() == (1..9).toList() }
        }
        toolWindowShot("5-finished-in-resolved")
        check("a walkthrough with every step resolved has no section of its own", sections.none { it.startsWith(PROJECT_FIRST) }, sections)
        check("…it is one group under Resolved, holding all its steps",
            resolved.count { it.kind == "WalkthroughNode" && it.steps.sorted() == (1..9).toList() } == 1, resolved.map { it.kind to it.steps })
    }

    private fun steps(shape: List<ShapeNode>): List<Int> = shape.flatMap { it.steps }

    private fun shapeUntil(what: String, sectionPrefix: String, done: (List<ShapeNode>) -> Boolean): List<ShapeNode> =
        readUntil(what, read = { ToolWindowUi.guidedShape(sectionPrefix) }, done = done)

    private fun selectedOrderUntil(order: Int): Int? =
        readUntil("step $order selected", read = { ToolWindowUi.selectedStep()!!.order }) { it == order }

    private fun toolWindowShot(name: String) = ToolWindowUi.screenshotToolWindow(shots.resolve("$name.png"))

    private data class ProjectFirstStep(val order: Int, val file: String?, val line: Int?)

    private companion object {
        val DEEP = DeepWalkthrough.label
        val HOPPING = InterleavedWalkthrough.label
        const val PROJECT_FIRST = "SHOP-90"
        const val GROUP_CONSECUTIVE = "Group Consecutive Steps by File"
        val SERVICE = InterleavedWalkthrough.SERVICE.substringAfterLast('/')

        val ALL_DEEP_STEPS = DeepWalkthrough.steps.map { it.order }.sorted()
        val OPEN_DEEP_STEPS = ALL_DEEP_STEPS.filter { it !in DeepWalkthrough.resolved }

        /** The hopping walk grouped: resolved step 1 keeps its place, runs in one file fold, lone steps stay rows. */
        val HOPPING_GROUPED = listOf(
            listOf(1), listOf(2, 3), listOf(4, 5), listOf(6), listOf(7), listOf(8), listOf(9), listOf(10), listOf(11),
            listOf(12, 13), listOf(14), listOf(15, 16), listOf(17), listOf(18),
        )

        /** Opens with project-level steps (no file) and comes back to them after file steps. */
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

        val NEXT = arrayOf("Next Step", "Next Occurrence")
    }
}
