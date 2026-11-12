package dev.marginalis.plugin.ui.toolwindow

import com.intellij.ide.FileSelectInContext
import com.intellij.ide.SelectInTarget
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.core.Opened

class FoldingTest : ToolWindowTestCase() {

    private lateinit var deep: String

    private lateinit var other: String

    private lateinit var done: String

    private fun nested() {
        sample("a/b/c/Deep.kt")
        sample("a/b/Side.kt")
        sample("lib/Other.kt")
        deep = agent.addOnLine(2, "body" to "deep", file = "a/b/c/Deep.kt")
        agent.addOnLine(2, "body" to "side", file = "a/b/Side.kt")
        other = agent.addOnLine(2, "body" to "other", file = "lib/Other.kt")
        done = agent.addOnLine(4, "body" to "done", file = "lib/Other.kt")
        agent.post("comment_resolve", "thread_id" to done)
    }

    private val everythingOpen = listOf(
        "Open  3", "a/b  2", "c  1", "Deep.kt ✉1", "✉ L2  deep", "Side.kt ✉1", "✉ L2  side",
        "lib  1", "Other.kt ✉1", "✉ L2  other", "Resolved  1",
    )

    private val everythingOpenWithResolved = everythingOpen + listOf("lib  1", "Other.kt", "L4  done")

    private fun collapseText(): String = window.presentation("Collapse").text

    private fun expandText(): String = window.presentation("Expand").text

    fun testTheTreeOpensEverythingButTheResolvedSection() {
        nested()

        assertEquals(everythingOpen, window.rows())
    }

    fun testAFolderFoldedByHandStaysFoldedWhenAThreadIsRepliedTo() {
        nested()
        window.toggleFolding("lib")

        window.rebuiltBy { agent.reply(deep, "more") }

        assertFalse(window.isExpanded("lib"))
        assertTrue(window.isExpanded("a/b"))
    }

    fun testAFolderFoldedByHandStaysFoldedWhenANewThreadArrivesInIt() {
        nested()
        window.toggleFolding("c  1")

        window.rebuiltBy { agent.addOnLine(6, "body" to "another", file = "a/b/c/Deep.kt") }

        assertEquals(listOf("Open  4", "a/b  3", "c  2", "Side.kt ✉1"), window.rows().take(4))
    }

    fun testAFolderFoldedByHandStaysFoldedWhenAStepIsMarkedRead() {
        window.layOutWalkthroughsBy("By Folder")
        sample("guide/Tour.kt")
        sample("lib/Other.kt")
        val step = agent.addOnLine(2, "order" to 1, "body" to "step one", file = "guide/Tour.kt")
        agent.addOnLine(2, "body" to "other", file = "lib/Other.kt")
        window.toggleFolding("lib")

        window.rebuiltBy { user.read(step) }

        assertFalse(window.isExpanded("lib"))
        assertTrue(window.isExpanded("guide", under = "Walkthrough"))
    }

    fun testTheResolvedSectionUnfoldedByHandStaysOpenWhenAThreadChanges() {
        nested()
        window.toggleFolding("Resolved")

        window.rebuiltBy { agent.reply(deep, "more") }

        assertTrue(window.isExpanded("Resolved"))
    }

    fun testANewThreadAfterTheOnlyOneWasDeletedArrivesInAnOpenTree() {
        sample()
        val only = agent.addOnLine(3, "body" to "only")
        window.selectNothing()
        window.press("Collapse")
        assertEquals(listOf("Open  1"), window.rows())

        window.rebuiltBy { user.delete(only) }
        window.rebuiltBy { agent.addOnLine(6, "body" to "fresh") }

        assertEquals(listOf("Open  1", "src  1", "Sample.kt ✉1", "✉ L6  fresh"), window.rows())
    }

    fun testAFolderThatEmptiesAndFillsAgainOpensAsNew() {
        nested()
        window.toggleFolding("lib")

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to other) }
        window.rebuiltBy { agent.addOnLine(6, "body" to "again", file = "lib/Other.kt") }

        assertTrue(window.isExpanded("lib"))
        assertTrue(window.rows().toString(), "✉ L6  again" in window.rows())
    }

    fun testAResolvedSectionThatEmptiesStartsFoldedAgain() {
        nested()
        window.toggleFolding("Resolved")

        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to done) }
        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to other) }

        assertFalse(window.isExpanded("Resolved"))
    }

    fun testAFolderNeverSeenBeforeOpens() {
        nested()
        window.toggleFolding("lib")
        sample("fresh/New.kt")

        window.rebuiltBy { agent.addOnLine(2, "body" to "new", file = "fresh/New.kt") }

        assertTrue(window.isExpanded("fresh"))
        assertTrue(window.isExpanded("New.kt"))
        assertFalse(window.isExpanded("lib"))
    }

    fun testANewFolderUnderTheResolvedSectionStaysFolded() {
        nested()
        window.toggleFolding("Resolved")

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to deep) }

        assertTrue(window.isExpanded("Resolved"))
        assertEquals(listOf("Resolved  2", "a/b/c  1", "lib  1"), window.rows().dropWhile { !it.startsWith("Resolved") })
    }

    fun testCollapseFoldsOneLevelFurtherOutWithEachPressAndTheSelectionLandsOnTheFoldedRow() {
        nested()
        window.toggleFolding("Resolved")
        window.select("deep")

        assertEquals("Collapse Folder", collapseText())
        window.press("Collapse")
        assertEquals("c  1", window.selected())
        assertEquals(listOf("Open  3", "a/b  2", "c  1", "Side.kt ✉1"), window.rows().take(4))

        assertEquals("Collapse Folder", collapseText())
        window.press("Collapse")
        assertEquals("a/b  2", window.selected())
        assertEquals(listOf("Open  3", "a/b  2", "lib  1"), window.rows().take(3))

        assertEquals("Collapse Section", collapseText())
        window.press("Collapse")
        assertEquals("Open  3", window.selected())
        assertEquals(listOf("Open  3", "Resolved  1", "lib  1"), window.rows())

        assertEquals("Collapse All Sections", collapseText())
        window.press("Collapse")
        assertEquals("Open  3", window.selected())
        assertEquals(listOf("Open  3", "Resolved  1"), window.rows())
        assertEquals("Collapse All Sections", collapseText())
    }

    fun testReopeningACollapsedSectionShowsOneLevel() {
        nested()
        window.select("Open")
        window.press("Collapse")

        window.toggleFolding("Open")

        assertEquals(listOf("Open  3", "a/b  2", "lib  1", "Resolved  1"), window.rows())
    }

    fun testReopeningACollapsedFolderShowsOneLevel() {
        nested()
        window.select("a/b")
        window.press("Collapse")

        window.toggleFolding("a/b")

        assertEquals(listOf("Open  3", "a/b  2", "c  1", "Side.kt ✉1", "lib  1"), window.rows().take(5))
    }

    fun testExpandOpensAFoldedSectionAllTheWayDownThenEverySection() {
        nested()
        window.press("Collapse")
        window.select("Open")

        assertEquals("Expand Section", expandText())
        window.press("Expand")
        assertEquals(everythingOpen, window.rows())
        assertEquals("Open  3", window.selected())

        assertEquals("Expand All Sections", expandText())
        window.press("Expand")
        assertEquals(everythingOpenWithResolved, window.rows())
    }

    fun testExpandOpensAFoldedFolderAllTheWayDownAndNothingElse() {
        nested()
        window.toggleFolding("lib")
        window.select("deep")
        window.press("Collapse")
        window.press("Collapse")
        assertEquals("a/b  2", window.selected())

        assertEquals("Expand Folder", expandText())
        window.press("Expand")

        assertEquals(everythingOpen.take(8) + "Resolved  1", window.rows())
        assertEquals("Expand Section", expandText())
    }

    fun testExpandOpensTheFoldedFileOfTheSelectionThroughItsFolder() {
        nested()
        window.select("Deep.kt")
        window.toggleFolding("Deep.kt")

        assertEquals("Expand Folder", expandText())
        window.press("Expand")

        assertTrue(window.isExpanded("Deep.kt"))
        assertEquals("Expand All Sections", expandText())
    }

    fun testWithNothingSelectedBothActOnEverySection() {
        nested()
        window.toggleFolding("Resolved")
        window.selectNothing()

        assertEquals("Collapse All Sections", collapseText())
        assertEquals("Expand All Sections", expandText())

        window.press("Collapse")
        assertEquals(listOf("Open  3", "Resolved  1"), window.rows())
        assertNull(window.selected())

        window.press("Expand")
        assertEquals(everythingOpenWithResolved, window.rows())
    }

    fun testOnASectionRowCollapseFoldsTheSectionThenEverySection() {
        nested()
        window.select("Open")

        assertEquals("Collapse Section", collapseText())
        assertEquals("Expand All Sections", expandText())
        window.press("Collapse")

        assertEquals(listOf("Open  3", "Resolved  1"), window.rows())
        assertEquals("Collapse All Sections", collapseText())
        assertEquals("Expand Section", expandText())
    }

    fun testBothAreDisabledWhenTheTreeHasNoSections() {
        assertFalse(window.isEnabled("Expand"))
        assertFalse(window.isEnabled("Collapse"))

        nested()
        window.settle()
        assertTrue(window.isEnabled("Expand"))
        assertTrue(window.isEnabled("Collapse"))

        window.filterBy("FYI")
        assertFalse(window.isEnabled("Expand"))
        assertFalse(window.isEnabled("Collapse"))
    }

    fun testExpandIsDisabledWhenEverySectionIsAlreadyOpen() {
        nested()
        window.selectNothing()

        window.press("Expand")

        assertEquals(everythingOpenWithResolved, window.rows())
        assertFalse(window.isEnabled("Expand"))
        assertTrue(window.isEnabled("Collapse"))
    }

    fun testCollapseIsDisabledWhenEverySectionIsAlreadyFolded() {
        nested()
        window.selectNothing()

        window.press("Collapse")

        assertEquals(listOf("Open  3", "Resolved  1"), window.rows())
        assertFalse(window.isEnabled("Collapse"))
        assertTrue(window.isEnabled("Expand"))
    }

    fun testTheProjectNodeIsALevelOfItsOwn() {
        sample()
        agent.add("body" to "on the project")
        agent.addOnLine(3, "body" to "on a line")
        window.select("on the project")

        assertEquals("Collapse Project", collapseText())
        window.press("Collapse")

        assertEquals("Project  1", window.selected())
        assertEquals(listOf("Open  2", "Project  1", "src  1", "Sample.kt ✉1", "✉ L3  on a line"), window.rows())
        assertEquals("Collapse Section", collapseText())
        assertEquals("Expand Project", expandText())
    }

    fun testAFileOutsideAnyFolderGoesStraightToItsSection() {
        sample("Root.kt")
        agent.addOnLine(3, "body" to "at the root", file = "Root.kt")
        window.select("at the root")

        assertEquals("Collapse Section", collapseText())
        window.press("Collapse")

        assertEquals(listOf("Open  1"), window.rows())
    }

    fun testCollapseOnAStepInTheStepListFoldsItsOwnWalkthroughSectionAndThenEverySection() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "SHOP-42", "body" to "first walk")
        agent.addOnLine(6, "order" to 1, "walkthrough" to "r2", "body" to "second walk")
        window.select("first walk")

        assertEquals("Collapse Section", collapseText())
        window.press("Collapse")
        assertEquals("SHOP-42  1", window.selected())
        assertEquals(listOf("SHOP-42  1", "r2  1", "✉ (1/1)  Sample.kt L6  second walk"), window.rows())

        assertEquals("Collapse All Sections", collapseText())
        window.press("Collapse")
        assertEquals(listOf("SHOP-42  1", "r2  1"), window.rows())
    }

    fun testByFolderCollapseOnAStepFoldsItsFolderThenItsOwnWalkthroughSectionAndThenEverySection() {
        window.layOutWalkthroughsBy("By Folder")
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "SHOP-42", "body" to "first walk")
        agent.addOnLine(6, "order" to 1, "walkthrough" to "r2", "body" to "second walk")
        window.select("first walk")
        window.press("Collapse")

        assertEquals("Collapse Section", collapseText())
        window.press("Collapse")
        assertEquals("SHOP-42  1", window.selected())
        assertEquals(listOf("SHOP-42  1", "r2  1", "src  1"), window.rows().take(3))

        window.press("Collapse")
        assertEquals(listOf("SHOP-42  1", "r2  1"), window.rows())
    }

    fun testAResolvedWalkthroughsFoldsInsideResolvedAreKeptApartFromTheCommentsBesideIt() {
        window.layOutWalkthroughsBy("By Folder")
        sample()
        val steps = listOf(
            agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one"),
            agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two"),
        )
        val settled = agent.addOnLine(9, "body" to "settled")
        val open = agent.addOnLine(12, "body" to "still open")
        (steps + settled).forEach { agent.post("comment_resolve", "thread_id" to it) }
        listOf("Resolved", "tour", "src", "Sample.kt").forEach { window.toggleFolding(it, under = "Resolved") }
        window.select("L3  one")

        window.rebuiltBy { agent.reply(open, "more") }

        assertEquals("(1/2)  L3  one", window.selected())
        assertEquals(
            """
            Resolved  3
              tour  2
                src  2
                  Sample.kt
                    (1/2)  L3  one
                    (2/2)  L6  two
              src  1
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testCollapseOnAResolvedWalkthroughFoldsThatWalkthrough() {
        sample()
        agent.post("comment_resolve", "thread_id" to agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one"))
        listOf("Resolved", "tour").forEach { window.toggleFolding(it, under = "Resolved") }
        window.select("tour", under = "Resolved")

        assertEquals("Collapse Walkthrough", collapseText())
        window.press("Collapse")

        assertEquals(listOf("Resolved  1", "tour  1"), window.rows())
        assertEquals("Collapse Section", collapseText())
    }

    fun testAResolvedStepSelectedInItsWalkthroughStaysSelectedAcrossARefresh() {
        sample()
        val first = agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        val second = agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.post("comment_resolve", "thread_id" to first)
        window.select("L3  one")

        window.rebuiltBy { agent.reply(second, "more") }

        assertEquals("(1/2)  Sample.kt L3  one", window.selected())
        assertEquals(listOf("tour  2", "(1/2)  Sample.kt L3  one"), window.selectedPath())
    }

    fun testCollapseAllSectionsFoldsTheOrphanedSectionToo() {
        sample()
        agent.addOnLine(20, "body" to "about this line")
        eraseLine(20)
        window.rebuiltBy { agent.addOnLine(3, "body" to "something else") }
        assertEquals("Orphaned  1", window.rows()[4])

        window.press("Collapse")

        assertEquals(listOf("Open  1", "Orphaned  1"), window.rows())
    }

    fun testRevealingAThreadOpensTheFoldsOnItsPathOnly() {
        nested()
        window.toggleFolding("c  1")
        window.toggleFolding("lib")

        MarginalisToolWindow.reveal(project, Opened.MarginThread(deep), focus = false)
        window.settle()

        assertEquals("✉ L2  deep", window.selected())
        assertFalse(window.isExpanded("lib"))
    }

    fun testSelectOpenedThreadOpensTheFoldsOnItsPathOnly() {
        nested()
        window.select("a/b")
        window.press("Collapse")
        window.toggleFolding("lib")
        user.openFile("a/b/c/Deep.kt", 2)

        window.selectOpenedThread()

        assertEquals("✉ L2  deep", window.selected())
        assertFalse(window.isExpanded("Side.kt"))
        assertFalse(window.isExpanded("lib"))
    }

    fun testSelectInMarginalisOpensTheFoldsOnItsPathOnly() {
        nested()
        window.toggleFolding("c  1")
        window.toggleFolding("lib")
        user.openFile("a/b/c/Deep.kt", 2)
        val target = SelectInTarget.EP_NAME.getExtensions(project).single { it.toString() == "Marginalis" }

        target.selectIn(FileSelectInContext(project, project.guessProjectDir()!!.findFileByRelativePath("a/b/c/Deep.kt")!!), false)
        window.settle()

        assertEquals("✉ L2  deep", window.selected())
        assertFalse(window.isExpanded("lib"))
    }

    fun testFirstStepOpensTheFoldsOnTheWayToTheCommentOnly() {
        nested()
        window.toggleFolding("c  1")
        window.toggleFolding("lib")
        window.click("L2  side")

        window.press("First Step")

        assertEquals("✉ L2  deep", window.selected())
        assertFalse(window.isExpanded("lib"))
    }

    fun testAFoldTheFilterLeavesInViewSurvivesTheFilterAndItsReset() {
        nested()
        window.toggleFolding("lib")

        window.filterBy("Awaiting You")
        assertFalse(window.isExpanded("lib"))

        window.filterBy("All")
        assertFalse(window.isExpanded("lib"))
    }

    fun testAFoldTheFilterHidesSurvivesTheFilterAndItsReset() {
        sample("src/Main.kt")
        sample("lib/Other.kt")
        agent.addOnLine(2, "severity" to "blocker", "body" to "blocking", file = "src/Main.kt")
        agent.addOnLine(2, "body" to "other", file = "lib/Other.kt")
        window.toggleFolding("lib")

        window.filterBy("Blockers Only")
        assertEquals(listOf("Open  1  ·  1 blocker", "src  1", "Main.kt ✉1", "✉ L2  blocking"), window.rows())

        window.filterBy("All")
        assertFalse(window.isExpanded("lib"))
        assertTrue(window.isExpanded("src"))
    }

    fun testAFoldedFolderStaysFoldedWhenANewFileSplitsItsRow() {
        sample("a/b/Deep.kt")
        sample("a/Shallow.kt")
        agent.addOnLine(5, "body" to "deep", file = "a/b/Deep.kt")
        window.toggleFolding("a/b")

        window.rebuiltBy { agent.addOnLine(3, "body" to "shallow", file = "a/Shallow.kt") }

        assertEquals(listOf("Open  2", "a  2", "b  1", "Shallow.kt ✉1", "✉ L3  shallow"), window.rows())
    }

    fun testAFoldedOuterFolderStaysFoldedWhenItsRowJoinsAndSplitsAgain() {
        sample("a/b/Deep.kt")
        sample("a/Shallow.kt")
        agent.addOnLine(5, "body" to "deep", file = "a/b/Deep.kt")
        val shallow = agent.addOnLine(3, "body" to "shallow", file = "a/Shallow.kt")
        window.toggleFolding("a  2")

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to shallow) }
        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to shallow) }

        assertEquals(listOf("Open  2", "a  2"), window.rows())
    }

    fun testAFoldedFolderStaysFoldedWhenItsRowJoinsTheFolderAbove() {
        sample("a/b/Deep.kt")
        sample("a/Shallow.kt")
        agent.addOnLine(5, "body" to "deep", file = "a/b/Deep.kt")
        val shallow = agent.addOnLine(3, "body" to "shallow", file = "a/Shallow.kt")
        window.toggleFolding("b  1")

        window.rebuiltBy { agent.post("comment_resolve", "thread_id" to shallow) }

        assertEquals(listOf("Open  1", "a/b  1", "Resolved  1"), window.rows())
    }
}
