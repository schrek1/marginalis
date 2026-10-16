package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.xmlb.XmlSerializer
import dev.marginalis.core.GuidedLayout
import dev.marginalis.plugin.testing.WalkthroughFixture
import javax.swing.tree.TreePath

class GuidedLayoutTest : ToolWindowTestCase() {

    private val setting get() = GuidedLayoutSetting.getInstance(project)

    fun testAFreshProjectLaysWalkthroughsOutByStepNumber() {
        assertEquals(GuidedLayout.BY_STEP_NUMBER, setting.layout)
        assertEquals(listOf("By step number"), chosenLayouts())
    }

    fun testByDefaultADeepWalkthroughReadsInStepOrder() {
        deep()

        assertEquals(DEEP_OPEN_STEPS, stepNumbersIn("Guided"))
    }

    fun testComingBackFromByFolderListsTheStepsByNumberAgainWithoutFolders() {
        deep()
        val byStepNumber = section("Guided")

        window.layOutGuided("By folder")
        val byFolder = section("Guided")
        window.layOutGuided("By step number")

        assertFalse(byFolder == byStepNumber)
        assertEquals(byStepNumber, section("Guided"))
        assertEquals(DEEP_OPEN_STEPS, stepNumbersIn("Guided"))
    }

    fun testEachLoneStepOfADeepWalkthroughNamesItsFile() {
        deep()

        val loneSteps = section("Guided").lines().drop(1).filter { it.startsWith("  ✉ (") }

        assertFalse(loneSteps.isEmpty())
        assertEquals(emptyList<String>(), loneSteps.filterNot { Regex("""\.java L\d+ """).containsMatchIn(it) })
    }

    fun testConsecutiveProjectStepsFoldUnderAProjectNodeOfTheirOwnWhileALoneOneStaysARow() {
        projectFirstWalk()

        assertEquals(
            """
            Guided SHOP-90  9
              Project  steps 1–3
                ✉ (1/9)  project  Step 1: overview
                ✉ (2/9)  project  Step 2: overview
                ✉ (3/9)  project  Step 3: overview
              ✉ (4/9)  Sample.kt L3  Step 4: review note
              ✉ (5/9)  project  Step 5: overview
              Other.kt  steps 6–7 ✉2
                ✉ (6/9)  L5  Step 6: review note
                ✉ (7/9)  L9  Step 7: review note
              Project  steps 8–9
                ✉ (8/9)  project  Step 8: overview
                ✉ (9/9)  project  Step 9: overview
            """.trimIndent(),
            section("Guided SHOP-90"),
        )
    }

    fun testNextWalksIntoThroughAndOutOfTheProjectRunsInStepOrder() {
        projectFirstWalk()
        select(step = 1, of = 9)

        val walked = listOf(selectedStep()) + (2..9).map {
            window.press("Next Step")
            selectedStep()
        }

        assertEquals((1..9).toList(), walked)
    }

    fun testByFileGathersEveryStepOfAFileUnderItsFullPathInTheOrderOfItsFirstStep() {
        interleaved()

        window.layOutGuided("By file")

        assertEquals(
            """
            Guided SHOP-77  17
              acme/backend/shop-orders/src/main/java/com/acme/orders/OrderService.java ✉4
                ✉ (2/18)  L20  Step 2
                ✉ (3/18)  L28  Step 3
                ✉ (6/18)  L41  Step 6
                ✉ (9/18)  L55  Step 9
              acme/backend/shop-common/src/main/java/com/acme/backend/common/discount/LoyaltyDiscountPolicy.java ✉3
                ✉ (4/18)  L8  Step 4
                ✉ (5/18)  L22  Step 5
                ✉ (14/18)  L40  Step 14
              acme/backend/shop-orders/src/main/java/com/acme/orders/OrderRepository.java ✉1
                ✉ (7/18)  L15  Step 7
              acme/backend/shop-common/src/main/java/com/acme/backend/common/order/Order.java ✉1
                ✉ (8/18)  L10  Step 8
              acme/backend/shop-orders/src/main/java/com/acme/orders/api/OrderController.java ✉2
                ✉ (10/18)  L30  Step 10
                ✉ (18/18)  L45  Step 18
              acme/backend/shop-orders/src/main/resources/db/changelog/add-loyalty-discount.sql ✉1
                ✉ (11/18)  L3  Step 11
              acme/backend/shop-orders/src/test/java/com/acme/orders/OrderServiceTest.java ✉3
                ✉ (12/18)  L14  Step 12
                ✉ (13/18)  L36  Step 13
                ✉ (17/18)  L58  Step 17
              acme/backend/shop-orders/src/test/java/com/acme/orders/discount/LoyaltyDiscountPolicyTest.java ✉2
                ✉ (15/18)  L12  Step 15
                ✉ (16/18)  L30  Step 16
            """.trimIndent(),
            section("Guided"),
        )
    }

    fun testByFileADeepWalkthroughsFilesFollowTheirFirstStepAndTheirStepsFollowTheNumbers() {
        deep()

        window.layOutGuided("By file")

        val stepsPerFile = section("Guided").split(Regex("\n  (?=\\S)")).drop(1).map { file -> stepNumbers(file) }
        assertEquals(stepsPerFile.map { it.first() }.sorted(), stepsPerFile.map { it.first() })
        assertEquals(stepsPerFile.map { it.sorted() }, stepsPerFile)
        assertEquals(DEEP_OPEN_STEPS, stepsPerFile.flatten().sorted())
    }

    // The hopping walk, since the deep one's files happen to list in step order By file too.
    fun testInByFileNextWalksTheStepNumbers() {
        interleaved()
        window.layOutGuided("By file")
        select(step = 2, of = 18)

        val walked = listOf(selectedStep()) + (1..5).map {
            window.press("Next Step")
            selectedStep()
        }

        assertEquals((2..7).toList(), walked)
    }

    fun testTheChosenLayoutIsSavedAndReadBack() {
        window.layOutGuided("By file")

        val saved = JDOMUtil.writeElement(XmlSerializer.serialize(setting.state))
        val reopened = GuidedLayoutSetting().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(saved), GuidedLayoutSetting.State::class.java))
        }

        assertEquals(GuidedLayout.BY_FILE, reopened.layout)
    }

    fun testTheChoiceIsKeptInTheWorkspaceFileUnderItsOwnName() {
        val storage = GuidedLayoutSetting::class.java.getAnnotation(State::class.java)

        assertEquals("MarginalisGuidedLayout", storage.name)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), storage.storages.map { it.value })
    }

    private fun projectFirstWalk() {
        sample()
        sample("src/Other.kt")
        for (order in listOf(1, 2, 3, 5, 8, 9)) {
            agent.add("order" to order, "walkthrough" to "SHOP-90", "body" to "Step $order: overview")
        }
        agent.addOnLine(3, "order" to 4, "walkthrough" to "SHOP-90", "body" to "Step 4: review note")
        agent.addOnLine(5, "order" to 6, "walkthrough" to "SHOP-90", "body" to "Step 6: review note", file = "src/Other.kt")
        agent.addOnLine(9, "order" to 7, "walkthrough" to "SHOP-90", "body" to "Step 7: review note", file = "src/Other.kt")
    }

    private fun chosenLayouts(): List<String> {
        val menu = window.action("Guided Layout") as ActionGroup
        return menu.getChildren(null).filterIsInstance<ToggleAction>()
            .filter { it.isSelected(TestActionEvent.createTestEvent(it, SimpleDataContext.getProjectContext(project))) }
            .map { it.templatePresentation.text }
    }

    private fun stepNumbersIn(title: String): List<Int> = stepNumbers(section(title))

    private fun stepNumbers(rows: String): List<Int> = STEP_PREFIX.findAll(rows).map { it.groupValues[1].toInt() }.toList()

    private fun select(step: Int, of: Int) {
        val row = window.rowOf("($step/$of)")
        window.tree.selectionPath = TreePath(window.tree.getPathForRow(row).path)
        window.settle()
    }

    private fun selectedStep(): Int? = window.selected()?.let { STEP_PREFIX.find(it)?.groupValues?.get(1)?.toInt() }

    private companion object {
        val STEP_PREFIX = Regex("""\((\d+)/\d+\)""")
        val DEEP_OPEN_STEPS: List<Int> = WalkthroughFixture.deep().let { walk ->
            walk.steps.map { it.order }.filterNot { it in walk.resolved }.sorted()
        }
    }
}
