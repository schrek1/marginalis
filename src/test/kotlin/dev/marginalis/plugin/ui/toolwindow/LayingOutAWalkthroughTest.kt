package dev.marginalis.plugin.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.marginalis.core.Opened
import dev.marginalis.core.WalkthroughLayout
import dev.marginalis.plugin.store.ProjectWalkthroughLayout
import dev.marginalis.plugin.testing.AllowReflection

class LayingOutAWalkthroughTest : ToolWindowTestCase() {

    private val saved get() = ProjectWalkthroughLayout.getInstance(project)

    fun testTheHeaderDropdownOffersTheLayoutsAndAfterThemTheGrouping() {
        assertEquals(
            listOf("By Step Number", "By Folder", "By File", "---", "Group Consecutive Steps by File"),
            window.walkthroughLayoutMenu(),
        )
    }

    fun testTheHeaderDropdownSaysWhatItIsFor() {
        val dropdown = window.presentation("Walkthrough Layout")

        assertEquals("Lay out each walkthrough's steps by step number, by folder or by file", dropdown.description)
        assertSame(AllIcons.Actions.GroupBy, dropdown.icon)
        assertTrue(dropdown.isPopupGroup)
    }

    fun testAFreshProjectListsStepsByStepNumberWithoutGrouping() {
        assertEquals(mapOf("By Step Number" to true, "By Folder" to false, "By File" to false), window.walkthroughLayouts())
        assertFalse(window.groupsConsecutiveStepsByFile())
        assertTrue(window.canGroupConsecutiveStepsByFile())
    }

    fun testGroupingIsOfferedOnlyWhileStepsAreListedByStepNumber() {
        window.layOutWalkthroughsBy("By Folder")
        assertFalse(window.canGroupConsecutiveStepsByFile())

        window.layOutWalkthroughsBy("By File")
        assertFalse(window.canGroupConsecutiveStepsByFile())

        window.layOutWalkthroughsBy("By Step Number")
        assertTrue(window.canGroupConsecutiveStepsByFile())
    }

    fun testChoosingALayoutTicksItAloneAndRelaysTheTree() {
        interleaved()
        val byStepNumber = section("SHOP-77")

        window.layOutWalkthroughsBy("By Folder")

        assertEquals(mapOf("By Step Number" to false, "By Folder" to true, "By File" to false), window.walkthroughLayouts())
        assertTrue(section("SHOP-77"), section("SHOP-77").lines()[1].startsWith("  acme/backend  18"))

        window.layOutWalkthroughsBy("By Step Number")

        assertEquals(byStepNumber, section("SHOP-77"))
    }

    fun testByFileGivesEachFileOneNodeInTheOrderOfItsFirstStep() {
        interleaved()

        window.layOutWalkthroughsBy("By File")

        assertEquals(
            """
            SHOP-77  18
              OrderController.java  steps 1, 10, 18 ✉2
                (1/18)  L12  Step 1
                ✉ (10/18)  L30  Step 10
                ✉ (18/18)  L45  Step 18
              OrderService.java  steps 2–3, 6, 9 ✉4
                ✉ (2/18)  L20  Step 2
                ✉ (3/18)  L28  Step 3
                ✉ (6/18)  L41  Step 6
                ✉ (9/18)  L55  Step 9
              LoyaltyDiscountPolicy.java  steps 4–5, 14 ✉3
                ✉ (4/18)  L8  Step 4
                ✉ (5/18)  L22  Step 5
                ✉ (14/18)  L40  Step 14
              OrderRepository.java  step 7 ✉1
                ✉ (7/18)  L15  Step 7
              Order.java  step 8 ✉1
                ✉ (8/18)  L10  Step 8
              add-loyalty-discount.sql  step 11 ✉1
                ✉ (11/18)  L3  Step 11
              OrderServiceTest.java  steps 12–13, 17 ✉3
                ✉ (12/18)  L14  Step 12
                ✉ (13/18)  L36  Step 13
                ✉ (17/18)  L58  Step 17
              LoyaltyDiscountPolicyTest.java  steps 15–16 ✉2
                ✉ (15/18)  L12  Step 15
                ✉ (16/18)  L30  Step 16
            """.trimIndent(),
            section("SHOP-77"),
        )
    }

    fun testByFileProjectStepsShareOneProjectNodePlacedByTheirFirstStep() {
        projectFirstWalk()

        window.layOutWalkthroughsBy("By File")

        assertEquals(
            """
            SHOP-90  9
              Project  steps 1–3, 5, 8–9
                ✉ (1/9)  project  Step 1: overview
                ✉ (2/9)  project  Step 2: overview
                ✉ (3/9)  project  Step 3: overview
                ✉ (5/9)  project  Step 5: overview
                ✉ (8/9)  project  Step 8: overview
                ✉ (9/9)  project  Step 9: overview
              Sample.kt  step 4 ✉1
                ✉ (4/9)  L3  Step 4: review note
              Other.kt  steps 6–7 ✉2
                ✉ (6/9)  L5  Step 6: review note
                ✉ (7/9)  L9  Step 7: review note
            """.trimIndent(),
            section("SHOP-90"),
        )
    }

    fun testGroupingFoldsConsecutiveStepsInOneFileWhileALoneStepStaysARowNamingItsFile() {
        interleaved()

        window.groupConsecutiveStepsByFile(true)

        assertEquals(
            """
            SHOP-77  18
              (1/18)  OrderController.java L12  Step 1
              OrderService.java  steps 2–3 ✉2
                ✉ (2/18)  L20  Step 2
                ✉ (3/18)  L28  Step 3
              LoyaltyDiscountPolicy.java  steps 4–5 ✉2
                ✉ (4/18)  L8  Step 4
                ✉ (5/18)  L22  Step 5
              ✉ (6/18)  OrderService.java L41  Step 6
              ✉ (7/18)  OrderRepository.java L15  Step 7
              ✉ (8/18)  Order.java L10  Step 8
              ✉ (9/18)  OrderService.java L55  Step 9
              ✉ (10/18)  OrderController.java L30  Step 10
              ✉ (11/18)  add-loyalty-discount.sql L3  Step 11
              OrderServiceTest.java  steps 12–13 ✉2
                ✉ (12/18)  L14  Step 12
                ✉ (13/18)  L36  Step 13
              ✉ (14/18)  LoyaltyDiscountPolicy.java L40  Step 14
              LoyaltyDiscountPolicyTest.java  steps 15–16 ✉2
                ✉ (15/18)  L12  Step 15
                ✉ (16/18)  L30  Step 16
              ✉ (17/18)  OrderServiceTest.java L58  Step 17
              ✉ (18/18)  OrderController.java L45  Step 18
            """.trimIndent(),
            section("SHOP-77"),
        )
    }

    fun testAFileTheWalkthroughReturnsToIsGroupedAgainAsANewNode() {
        returningWalk()

        window.groupConsecutiveStepsByFile(true)

        assertEquals(
            """
            tour  5
              Sample.kt  steps 1–2 ✉2
                ✉ (1/5)  L3  one
                ✉ (2/5)  L6  two
              ✉ (3/5)  Other.kt L5  three
              Sample.kt  steps 4–5 ✉2
                ✉ (4/5)  L9  four
                ✉ (5/5)  L12  five
            """.trimIndent(),
            section("tour"),
        )
    }

    fun testConsecutiveProjectStepsFoldUnderAProjectNodeWhileALoneOneStaysARow() {
        projectFirstWalk()

        window.groupConsecutiveStepsByFile(true)

        assertEquals(
            """
            SHOP-90  9
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
            section("SHOP-90"),
        )
    }

    fun testAGroupNamesTheStepsInItAndAResolvedStepKeepsItsPlaceInTheGroup() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "intent" to "question", "body" to "one")
        val resolved = agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.addOnLine(9, "order" to 3, "walkthrough" to "tour", "intent" to "question", "body" to "three")
        agent.post("comment_resolve", "thread_id" to resolved)
        window.groupConsecutiveStepsByFile(true)

        assertEquals("Sample.kt  steps 1–3 ✉2", section("tour").lines()[1].trim())
        assertEquals("(2/3)  L6  two", section("tour").lines()[3].trim())

        window.filterBy("Questions")

        assertEquals("Sample.kt  steps 1, 3 ✉2", section("tour").lines()[1].trim())
    }

    fun testAStepYouHaveReadShowsNoEnvelopeInStepOrderWhetherGroupedOrNot() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        val read = agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.addOnLine(5, "order" to 3, "walkthrough" to "tour", "body" to "three", file = "src/Other.kt")
        user.read(read)

        assertEquals(
            listOf("tour  3", "✉ (1/3)  Sample.kt L3  one", "(2/3)  Sample.kt L6  two", "✉ (3/3)  Other.kt L5  three"),
            window.rows(),
        )

        window.groupConsecutiveStepsByFile(true)

        assertEquals(
            """
            tour  3
              Sample.kt  steps 1–2 ✉1
                ✉ (1/3)  L3  one
                (2/3)  L6  two
              ✉ (3/3)  Other.kt L5  three
            """.trimIndent(),
            section("tour"),
        )
    }

    fun testAnAgreedStepShowsAThumbsUpInStepOrder() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        val agreed = agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        user.agree(agreed)

        assertEquals(listOf("tour  2", "✉ (1/2)  Sample.kt L3  one", "👍 (2/2)  Sample.kt L6  two"), window.rows())

        window.groupConsecutiveStepsByFile(true)

        assertEquals(listOf("tour  2", "Sample.kt  steps 1–2 ✉1", "✉ (1/2)  L3  one", "👍 (2/2)  L6  two"), window.rows())
    }

    fun testTurningGroupingOffListsEveryStepAsARowAgain() {
        returningWalk()
        window.groupConsecutiveStepsByFile(true)

        window.groupConsecutiveStepsByFile(false)

        assertEquals(
            listOf("tour  5", "✉ (1/5)  Sample.kt L3  one", "✉ (2/5)  Sample.kt L6  two", "✉ (3/5)  Other.kt L5  three",
                "✉ (4/5)  Sample.kt L9  four", "✉ (5/5)  Sample.kt L12  five"),
            window.rows(),
        )
    }

    fun testARepeatedFileKeepsEachGroupsFoldAndTheSelectionAcrossRebuilds() {
        returningWalk()
        window.groupConsecutiveStepsByFile(true)
        window.toggleFolding("steps 1–2")
        window.select("five")

        window.rebuiltBy { agent.addOnLine(30, "body" to "loose") }

        assertFalse(window.isExpanded("steps 1–2"))
        assertTrue(window.isExpanded("steps 4–5"))
        assertEquals("✉ (5/5)  L12  five", window.selected())
        assertEquals(listOf("tour  5", "Sample.kt  steps 4–5 ✉2", "✉ (5/5)  L12  five"), window.selectedPath())
    }

    fun testRevealingAFileSelectsItsFirstStepRowInTheStepList() {
        returningWalk()
        window.outline()

        MarginalisToolWindow.reveal(project, ThreadInFront.Found(Opened.File(SAMPLE)), focus = false)

        assertEquals("✉ (1/5)  Sample.kt L3  one", window.selected())
    }

    fun testRevealingAFileSelectsItsFirstGroupWhenGroupedAndItsLoneRowOtherwise() {
        returningWalk()
        window.groupConsecutiveStepsByFile(true)

        MarginalisToolWindow.reveal(project, ThreadInFront.Found(Opened.File(SAMPLE)), focus = false)
        assertEquals("Sample.kt  steps 1–2 ✉2", window.selected())

        MarginalisToolWindow.reveal(project, ThreadInFront.Found(Opened.File("src/Other.kt")), focus = false)
        assertEquals("✉ (3/5)  Other.kt L5  three", window.selected())
    }

    fun testRevealingTheProjectSelectsAWalkthroughsFirstProjectStep() {
        projectFirstWalk()
        window.outline()

        MarginalisToolWindow.reveal(project, ThreadInFront.Found(Opened.ProjectThreads), focus = false)
        assertEquals("✉ (1/9)  project  Step 1: overview", window.selected())

        window.groupConsecutiveStepsByFile(true)
        MarginalisToolWindow.reveal(project, ThreadInFront.Found(Opened.ProjectThreads), focus = false)
        assertEquals("Project  steps 1–3", window.selected())
    }

    fun testTheArrowsWalkTheStepNumbersInEveryLayout() {
        interleaved()
        val layouts = listOf<() -> Unit>(
            { window.layOutWalkthroughsBy("By Step Number") },
            { window.groupConsecutiveStepsByFile(true) },
            { window.layOutWalkthroughsBy("By Folder") },
            { window.layOutWalkthroughsBy("By File") },
        )

        for (layOut in layouts) {
            layOut()
            window.selectNothing()
            window.press("First Step")
            val walked = listOf(selectedStep()) + (1..5).map {
                window.press("Next Step")
                selectedStep()
            }
            assertEquals(window.walkthroughLayouts().toString(), (2..7).toList(), walked)
        }
    }

    fun testBothChoicesAreSavedAndReadBack() {
        window.layOutWalkthroughsBy("By File")
        window.layOutWalkthroughsBy("By Step Number")
        window.groupConsecutiveStepsByFile(true)
        window.layOutWalkthroughsBy("By File")

        val written = JDOMUtil.writeElement(XmlSerializer.serialize(saved.state))
        val reopened = ProjectWalkthroughLayout().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(written), ProjectWalkthroughLayout.State::class.java))
        }

        assertEquals(WalkthroughLayout.BY_FILE, reopened.layout)
        assertTrue(reopened.groupsConsecutiveSteps)
    }

    fun testAToolWindowOpensWithTheProjectsSavedChoices() {
        returningWalk()
        saved.layout = WalkthroughLayout.BY_FOLDER
        saved.groupsConsecutiveSteps = true

        assertEquals(mapOf("By Step Number" to false, "By Folder" to true, "By File" to false), window.walkthroughLayouts())
        assertTrue(window.groupsConsecutiveStepsByFile())
        assertEquals("src  5", section("tour").lines()[1].trim())
    }

    fun testAToolWindowOpensWithTheProjectsSavedGrouping() {
        returningWalk()
        saved.groupsConsecutiveSteps = true

        assertEquals("Sample.kt  steps 1–2 ✉2", section("tour").lines()[1].trim())
    }

    fun testCollapseTreatsAGroupOfStepsInAFileAsALevelLikeAGroupOfProjectSteps() {
        projectFirstWalk()
        window.groupConsecutiveStepsByFile(true)

        window.select("Step 2: overview")
        assertEquals("Collapse Project", collapseText())

        window.select("Step 6: review note")
        assertEquals("Collapse File", collapseText())
        window.press("Collapse")

        assertFalse(window.isExpanded("Other.kt  steps 6–7"))
        assertEquals("Other.kt  steps 6–7 ✉2", window.selected())
        assertEquals("Collapse Section", collapseText())
    }

    fun testAGroupFoldedByHandWhileFilteredStaysFoldedAcrossARefresh() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "intent" to "question", "body" to "one")
        agent.addOnLine(5, "order" to 2, "walkthrough" to "tour", "intent" to "finding", "body" to "two", file = "src/Other.kt")
        agent.addOnLine(9, "order" to 3, "walkthrough" to "tour", "intent" to "question", "body" to "three")
        window.groupConsecutiveStepsByFile(true)
        window.filterBy("Questions")
        window.toggleFolding("Sample.kt  steps 1, 3")

        window.rebuiltBy { agent.addOnLine(20, "intent" to "question", "body" to "another question") }

        assertFalse(window.isExpanded("Sample.kt  steps 1, 3"))
    }

    @AllowReflection("the storage name and file keep the choices per project and out of version control; only the @State annotation states them")
    fun testTheChoicesAreKeptInTheWorkspaceFileUnderTheirOwnName() {
        val storage = ProjectWalkthroughLayout::class.java.getAnnotation(State::class.java)

        assertEquals("MarginalisWalkthroughLayout", storage.name)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), storage.storages.map { it.value })
    }

    private fun returningWalk() {
        sample()
        sample("src/Other.kt")
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.addOnLine(5, "order" to 3, "walkthrough" to "tour", "body" to "three", file = "src/Other.kt")
        agent.addOnLine(9, "order" to 4, "walkthrough" to "tour", "body" to "four")
        agent.addOnLine(12, "order" to 5, "walkthrough" to "tour", "body" to "five")
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

    private fun collapseText(): String = window.presentation("Collapse").text

    private fun selectedStep(): Int? = window.selected()?.let { STEP_PREFIX.find(it)?.groupValues?.get(1)?.toInt() }

    private companion object {
        val STEP_PREFIX = Regex("""\((\d+)/\d+\)""")
    }
}
