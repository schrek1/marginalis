package dev.marginalis.plugin.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.plugin.ProjectPaths
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.PluginUnload
import dev.marginalis.plugin.testing.ReviewDiff
import dev.marginalis.plugin.testing.ReviewDiffs
import dev.marginalis.plugin.testing.ReviewProcessorTab
import dev.marginalis.plugin.testing.ReviewProcessorTab.ReviewedFile
import dev.marginalis.plugin.testing.SteppingChoice
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.LastStep
import dev.marginalis.plugin.ui.WalkthroughNavigator
import java.util.concurrent.CopyOnWriteArrayList

class ReviewDiffStepsAcrossFilesTest : HttpContractTestCase() {

    private lateinit var editors: FileEditorManagerImpl

    override fun setUp() {
        super.setUp()
        editors = MarginTab(project, testRootDisposable).editors
        listOf(CART, ORDER, INVOICE).forEach { path -> myFixture.addFileToProject(path, text(localLines(path))) }
        stepsAcrossReviewFiles(true)
        ReviewDiffSteps.useInTests(testRootDisposable)
        Disposer.register(testRootDisposable) { ReviewDiffSteps.disposeAll() }
    }

    fun testNextStepInAnotherFileSwitchesTheReviewToThatFile() {
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testPreviousStepSwitchesTheReviewBackToTheFirstFile() {
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, ORDER, rowOf(20))

        user.press(user.panel(shownDiff().editor), "Previous Step")

        assertStepOpensInTheReview(review, CART, rowOf(5))
        assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
    }

    fun testResolvingAStepMovesTheReviewToTheNextStepsFile() {
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Resolve")

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
    }

    fun testNextStepInAnotherFileSwitchesAUnifiedReviewToThatFile() {
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER), unified = true)
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
    }

    fun testAStepInAFileOutsideTheReviewOpensInThatFilesTab() {
        walkthrough(CART to 5, INVOICE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(INVOICE, line = 4)
        assertEquals(CART, review.shownFile())
    }

    fun testAStepWhoseLineTheReviewRemovedInTheOtherFileOpensInThatFilesTabAndTheWalkthroughMovesOnToIt() {
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER, removing = 20))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(ORDER, review.shownFile())
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        backToTheReview()
        assertPanelRowsBecome(emptyList())
    }

    fun testWithTheSettingOffAStepInAnotherFileOpensInItsTabWithoutAskingTheReview() {
        stepsAcrossReviewFiles(false)
        val asked = reviewFilesAsking { error("the review was asked for its files with the setting off") }
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(CART, review.shownFile())
        assertEquals(0, asked.size)
    }

    fun testADiffThatIsNoReviewIsNeverAskedForItsFiles() {
        val asked = reviewFilesAsking { error("a plain diff was asked for its files") }
        walkthrough(CART to 5, ORDER to 20)
        val diff = ReviewDiffs(project, testRootDisposable)
            .inATab(editors, CART, text(localLines(CART)), text(ADDED_ON_TOP + localLines(CART)), unified = false, user::dataContextOf)
        diff.clickIcon(rowOf(5))

        user.press(user.panel(diff.editor), "Next Step")

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(0, asked.size)
    }

    fun testAReviewThatFailsToListItsFilesLeavesTheStepToItsFileTab() {
        reviewFilesAsking { throw NoSuchMethodError("getCurrentList") }
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(CART, review.shownFile())
        assertEquals(1, warnings.size)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testAReviewThatFailsToShowTheFileLeavesTheStepToItsFileTab() {
        reviewFilesAsking { review -> theReviewsFiles(review).showing { throw ClassCastException("the change is no longer a change") } }
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(CART, review.shownFile())
        assertEquals(1, warnings.size)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testAReviewThatSwitchesFilesAndThenFailsShowsTheStepOnlyInItsFileTab() {
        reviewFilesAsking { review ->
            val files = theReviewsFiles(review)
            files.showing { index ->
                files.show(index)
                throw NullPointerException("no presentation")
            }
        }
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(1, warnings.size)
        backToTheReview()
        assertEquals(ORDER, review.shownFile())
        assertPanelRowsBecome(emptyList())
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testAFileTheReviewCannotNameOpensTheStepInItsTabWithoutAWarning() {
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER)) { it.withoutAName(ORDER) }
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(CART, review.shownFile())
        assertEquals(emptyList<String>(), warnings)
    }

    fun testAFileTheReviewCannotNameDoesNotStopTheSwitchToAnother() {
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(INVOICE), reviewed(ORDER)) { it.withoutAName(INVOICE) }
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
        assertEquals(emptyList<String>(), warnings)
    }

    fun testWhileTheReviewLoadsTheOtherFileTheWalkthroughStaysOnTheStepYouLeftAndATabTakesOverIfItNeverArrives() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            theReviewsFiles(review).showing { }
        })
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile {
            user.press(user.panel(shownDiff().editor), "Next Step")
            assertEquals(listOf(rowOf(5)), panelRows())
            assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
            assertTrue(ReviewDiffSteps.hasStepsUnderWay())

            ReviewDiffSteps.timeOutArrivalsInTests()
            assertStepOpensInItsFileTab(ORDER, line = 20)
        }

        assertEquals(CART, review.shownFile())
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertEquals(1, warnings.size)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testClosingTheReviewWhileATimedOutStepWaitsForItsTabOpensNoTab() {
        reviewFilesAsking { review -> theReviewsFiles(review).showing { } }
        walkthrough(CART to 5, ORDER to 20)
        reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        ReviewDiffSteps.timeOutArrivalsInTests()
        assertTrue(ReviewDiffSteps.hasStepsUnderWay())
        closeTheReview()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testClosingTheReviewWhileAStepItCannotShowWaitsForItsTabOpensNoTab() {
        reviewFilesAsking { review -> theReviewsFiles(review).showing { } }
        walkthrough(CART to 5, ORDER to 20)
        reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        assertNull(ReviewDiffSteps.arrivingIn(reviewContext(), ORDER, canShowIt = false))
        assertTrue(ReviewDiffSteps.hasStepsUnderWay())
        closeTheReview()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testWhenTheReviewShowsAnotherFileDuringTheLoadTheWalkthroughStaysWhereItWas() {
        reviewFilesAsking { review ->
            val files = theReviewsFiles(review)
            files.showing { files.show(files.absolutePaths.indexOfFirst { it!!.endsWith(INVOICE) }) }
        }
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER), reviewed(INVOICE))
        openStepInTheReview(rowOf(5))

        val warnings = warningsWhile { user.press(user.panel(shownDiff().editor), "Next Step") }

        waitFor("the review never showed $INVOICE") { review.shownFile() == INVOICE && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
        assertEquals(emptyList<String>(), warnings)
    }

    fun testARefreshOfTheFileLeftDuringTheLoadStillLandsTheStepOnceTheReviewShowsItsFile() {
        var showLater: (() -> Unit)? = null
        reviewFilesAsking { review ->
            val files = theReviewsFiles(review)
            files.showing { index -> showLater = { files.show(index) } }
        }
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        refreshed(review)
        assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
        assertTrue(ReviewDiffSteps.hasStepsUnderWay())
        checkNotNull(showLater)()

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testARefreshOfTheFileLeftDuringALoadThatNeverEndsOpensTheStepInItsTabOnce() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            theReviewsFiles(review).showing { }
        })
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        refreshed(review)
        ReviewDiffSteps.timeOutArrivalsInTests()

        assertStepOpensInItsFileTab(ORDER, line = 20)
        waitFor("the review never gave up on the step") { !ReviewDiffSteps.hasStepsUnderWay() }
        assertEquals(1, editors.openFiles.count { fileOf(it) == ORDER })
        assertEquals(CART, review.shownFile())
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
    }

    fun testAStepShownAnotherWayWhileTheReviewLoadsKeepsTheWalkthroughOnIt() {
        var showLater: (() -> Unit)? = null
        reviewFilesAsking { review ->
            val files = theReviewsFiles(review)
            files.showing { index -> showLater = { files.show(index) } }
        }
        val ids = walkthrough(CART to 5, ORDER to 20, INVOICE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        WalkthroughNavigator.navigateTo(project, thread(ids[2]))
        assertStepOpensInItsFileTab(INVOICE, line = 4)
        backToTheReview()
        checkNotNull(showLater)()

        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertEquals(ids[2], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testAStepShownAnotherWayBeforeTheReviewGivesUpOpensNoTabForTheOldOne() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            theReviewsFiles(review).showing { }
        })
        val ids = walkthrough(CART to 5, ORDER to 20, INVOICE to 4)
        reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        WalkthroughNavigator.navigateTo(project, thread(ids[2]))
        assertStepOpensInItsFileTab(INVOICE, line = 4)

        val warnings = warningsWhile {
            ReviewDiffSteps.timeOutArrivalsInTests()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        assertEquals(emptyList<String>(), warnings)
        assertEquals(INVOICE, editors.selectedTextEditor?.let(::fileOf))
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertEquals(ids[2], LastStep.getInstance(project).mark?.threadId)
    }

    fun testAStepReplacedWhileItsDiffIsLandingDoesNotLand() {
        val ids = walkthrough(CART to 5, ORDER to 20, INVOICE to 4)
        val context = aDiffContext()
        val review = Disposer.newCheckedDisposable(testRootDisposable)
        var replacedShown = 0
        val replaced = ReviewDiffSteps.ArrivingStep(project, context, ORDER, CART, ids[0], thread(ids[1])) { replacedShown++ }
        replaced.start(review)
        checkNotNull(ReviewDiffSteps.arrivingIn(context, ORDER, canShowIt = true))
        ReviewDiffSteps.ArrivingStep(project, context, INVOICE, CART, ids[0], thread(ids[2])) {}.start(review)
        val orderTab = editors.openTextEditor(OpenFileDescriptor(project, projectFile(ORDER)), true)!!

        replaced.landIn(orderTab)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(0, replacedShown)
        assertEquals(emptyList<Any>(), user.panels(orderTab))
        assertNull(LastStep.getInstance(project).mark)
    }

    fun testOpeningTheStepAnotherWayDuringTheLoadShowsItOnlyOnce() {
        var showLater: (() -> Unit)? = null
        reviewFilesAsking { review ->
            val files = theReviewsFiles(review)
            files.showing { index -> showLater = { files.show(index) } }
        }
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        WalkthroughNavigator.navigateTo(project, thread(ids[1]))
        assertStepOpensInItsFileTab(ORDER, line = 20)
        backToTheReview()
        checkNotNull(showLater)()

        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testACancelledSwitchLeavesNothingBehind() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            theReviewsFiles(review).showing { throw ProcessCanceledException() }
        })
        walkthrough(CART to 5, ORDER to 20)
        reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        val cancelled = runCatching { user.press(user.panel(shownDiff().editor), "Next Step") }.exceptionOrNull()

        assertTrue("the cancellation was swallowed: $cancelled", cancelled is ProcessCanceledException)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        ReviewDiffSteps.timeOutArrivalsInTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
    }

    fun testAClaimedStepNeverTimesOut() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            theReviewsFiles(review).showing { }
        })
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        val claimed = checkNotNull(ReviewDiffSteps.arrivingIn(reviewContext(), ORDER, canShowIt = true))

        ReviewDiffSteps.timeOutArrivalsInTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        shown(review, ORDER)
        claimed.landIn(shownDiff().editor)

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testNextTwiceOnTheSameStepDuringTheLoadLandsItOnceInTheReview() = nextTwiceOnTheSameStep(lagging = false)

    fun testNextTwiceOnTheSameStepDuringTheLoadLandsItOnceInTheReviewWhileTheReviewStillReportsTheFileLeft() =
        nextTwiceOnTheSameStep(lagging = true)

    fun testASecondNextDuringTheLoadToAStepInTheFileLeftBringsTheReviewBackToIt() = secondNextToTheFileLeft(lagging = false)

    fun testASecondNextDuringTheLoadToAStepInTheFileLeftBringsTheReviewBackToItWhileTheReviewStillReportsTheFileLeft() =
        secondNextToTheFileLeft(lagging = true)

    fun testASecondNextDuringTheLoadToAStepInAThirdFileTakesTheReviewThere() = secondNextToAThirdFile(lagging = false)

    fun testASecondNextDuringTheLoadToAStepInAThirdFileTakesTheReviewThereWhileTheReviewStillReportsTheFileLeft() =
        secondNextToAThirdFile(lagging = true)

    fun testNextThenPreviousDuringTheLoadLeavesTheReviewOnTheStepAskedForLast() = nextThenPrevious(lagging = false)

    fun testNextThenPreviousDuringTheLoadLeavesTheReviewOnTheStepAskedForLastWhileTheReviewStillReportsTheFileLeft() =
        nextThenPrevious(lagging = true)

    private fun nextTwiceOnTheSameStep(lagging: Boolean) {
        val slow = slowReview(lagging)
        val ids = walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")

        user.press(user.panel(shownDiff().editor), "Next Step")
        slow.land()

        assertStepOpensInTheReview(review, ORDER, rowOf(20))
        assertEquals(1, slow.shows)
        assertFalse(editors.openFiles.any { fileOf(it) == ORDER })
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    private fun secondNextToTheFileLeft(lagging: Boolean) {
        val slow = slowReview(lagging)
        val ids = walkthrough(CART to 5, ORDER to 20, CART to 25, CART to 28)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(25))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")

        user.press(user.panels(shownDiff().editor).last(), "Next Step")
        slow.land()

        assertStepOpensInTheReview(review, CART, rowOf(28))
        assertFalse(editors.openFiles.any { fileOf(it) == CART })
        assertEquals(ids[3], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    private fun secondNextToAThirdFile(lagging: Boolean) {
        val slow = slowReview(lagging)
        val ids = walkthrough(CART to 5, ORDER to 20, CART to 25, INVOICE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER), reviewed(INVOICE))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(25))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")

        user.press(user.panels(shownDiff().editor).last(), "Next Step")
        slow.land()

        assertStepOpensInTheReview(review, INVOICE, rowOf(4))
        assertEquals(ids[3], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    private fun nextThenPrevious(lagging: Boolean) {
        val slow = slowReview(lagging)
        val ids = walkthrough(CART to 5, CART to 12, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(12))
        user.press(user.panel(shownDiff().editor), "Next Step")

        user.press(user.panel(shownDiff().editor), "Previous Step")
        slow.land()

        assertStepOpensInTheReview(review, CART, rowOf(5))
        assertFalse(editors.openFiles.any { fileOf(it) == CART })
        assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testAPlainCommentsNextInTheSameFileDuringALoadStaysInTheReviewAndAbandonsTheLoad() {
        val slow = slowReview(lagging = true)
        val ids = walkthrough(CART to 5, ORDER to 20)
        agent.add("file" to CART, "line" to 12, "anchor_text" to localLines(CART)[11], "body" to "First comment")
        agent.add("file" to CART, "line" to 25, "anchor_text" to localLines(CART)[24], "body" to "Second comment")
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(12))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")

        user.press(user.panels(shownDiff().editor).last(), "Next Comment")

        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertTrue(panelRows().toString(), rowOf(25) in panelRows())
        assertFalse(editors.openFiles.any { fileOf(it) == CART })
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        slow.land()
        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertEquals(ids[0], LastStep.getInstance(project).mark?.threadId)
    }

    fun testSwitchingTheSettingOffDuringALoadKeepsTheNextStepInTheFileOnScreenAndAbandonsTheLoad() {
        val slow = slowReview(lagging = true)
        val ids = walkthrough(CART to 5, ORDER to 20, CART to 25, CART to 28)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(25))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")
        MarginalisSettings.getInstance().state.keepStepsInReviewAcrossFiles = false

        user.press(user.panels(shownDiff().editor).last(), "Next Step")

        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertTrue(panelRows().toString(), rowOf(28) in panelRows())
        assertEquals(ids[3], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
        slow.land()
        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertEquals(ids[3], LastStep.getInstance(project).mark?.threadId)
    }

    fun testASecondStepThatCannotBeShownDuringTheLoadStillCancelsTheFirst() {
        val slow = slowReview(lagging = false)
        myFixture.addFileToProject(GONE, text(localLines(GONE)))
        val ids = walkthrough(CART to 5, ORDER to 20, CART to 25, GONE to 4)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(25))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")
        WriteAction.run<Throwable> { projectFile(GONE).delete(this) }

        user.press(user.panels(shownDiff().editor).last(), "Next Step")
        slow.land()

        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        assertPanelRowsBecome(emptyList())
        assertEquals(ids[2], LastStep.getInstance(project).mark?.threadId)
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    fun testResolvingAPlainCommentLeavesTheReviewAndOpensTheNextCommentInItsTab() {
        val asked = reviewFilesAsking { error("the review was asked for its files for a plain comment") }
        agent.add("file" to CART, "line" to 5, "anchor_text" to localLines(CART)[4], "body" to "First comment")
        agent.add("file" to ORDER, "line" to 20, "anchor_text" to localLines(ORDER)[19], "body" to "Second comment")
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Resolve")

        assertStepOpensInItsFileTab(ORDER, line = 20)
        assertEquals(CART, review.shownFile())
        assertEquals(0, asked.size)
    }

    fun testTheStepOpensOnceAndKeepsTheWalkthroughWhenThreadsLeftOpenInItsFileComeBack() {
        val ids = walkthrough(CART to 5, ORDER to 20, ORDER to 25)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        shown(review, ORDER)
        openStepInTheReview(rowOf(25))
        openStepInTheReview(rowOf(20))
        shown(review, CART)
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        waitFor("the review never showed both threads in $ORDER") { review.shownFile() == ORDER && panelRows() == listOf(rowOf(20), rowOf(25)) }
        assertEquals(listOf(rowOf(20), rowOf(25)), panelRows())
        assertEquals(rowOf(20), shownDiff().editor.caretModel.logicalPosition.line + 1)
        assertEquals(ids[1], LastStep.getInstance(project).mark?.threadId)
    }

    fun testTheStepYouMovedAwayFromStaysClosedWhenTheWalkthroughComesBackToItsFile() {
        walkthrough(CART to 5, ORDER to 20, CART to 25)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        user.press(user.panel(shownDiff().editor), "Next Step")
        assertStepOpensInTheReview(review, ORDER, rowOf(20))

        user.press(user.panel(shownDiff().editor), "Next Step")

        assertStepOpensInTheReview(review, CART, rowOf(25))
    }

    fun testStepsYouLeaveStayOpenInTheReviewWhenChosen() {
        SteppingChoice.keepTheStepYouLeaveOpen(testRootDisposable)
        walkthrough(CART to 5, ORDER to 8, CART to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")
        waitFor("the review never switched to $ORDER") { review.shownFile() == ORDER && panelRows() == listOf(rowOf(8)) }
        user.press(user.panel(shownDiff().editor), "Next Step")

        waitFor("the review never came back to $CART with both steps open: ${review.shownFile()} ${panelRows()}") {
            review.shownFile() == CART && panelRows() == listOf(rowOf(5), rowOf(20))
        }
        assertEquals("the caret moves to the step's row", rowOf(20), shownDiff().editor.caretModel.logicalPosition.line + 1)
        shown(review, ORDER)
        assertPanelRowsBecome(listOf(rowOf(8)))
    }

    fun testASecondNextDuringTheLoadLeavesBothStepsOpenInTheFileLeftWhenChosen() {
        SteppingChoice.keepTheStepYouLeaveOpen(testRootDisposable)
        val slow = slowReview(lagging = false)
        val ids = walkthrough(CART to 5, ORDER to 20, CART to 25, ORDER to 28)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))
        openStepInTheReview(rowOf(25))
        user.press(user.panels(shownDiff().editor).first(), "Next Step")

        user.press(user.panels(shownDiff().editor).last(), "Next Step")
        slow.land()

        assertStepOpensInTheReview(review, ORDER, rowOf(28))
        assertEquals(ids[3], LastStep.getInstance(project).mark?.threadId)
        shown(review, CART)
        assertPanelRowsBecome(listOf(rowOf(5), rowOf(25)))
    }

    fun testUpdatingThePluginWhileTheReviewLoadsTheStepsFileLeavesTheStepUnopened() {
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = { review ->
            val files = theReviewsFiles(review)
            files.showing { index ->
                files.show(index)
                assertNull(PluginUnload.forUpdate())
            }
        })
        walkthrough(CART to 5, ORDER to 20)
        val review = reviewOf(reviewed(CART), reviewed(ORDER))
        openStepInTheReview(rowOf(5))

        user.press(user.panel(shownDiff().editor), "Next Step")

        waitFor("the review never showed $ORDER") { review.shownFile() == ORDER && shownDiffOrNull()?.hasText() == true }
        ReviewDiffSteps.timeOutArrivalsInTests()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertEquals(emptyList<Int>(), panelRows())
        assertFalse(ReviewDiffSteps.hasStepsUnderWay())
    }

    private fun reviewFilesAsking(files: (DiffEditorViewer) -> ReviewFiles?): List<DiffEditorViewer> {
        val asked = CopyOnWriteArrayList<DiffEditorViewer>()
        val recording: (DiffEditorViewer) -> ReviewFiles? = { review -> asked += review; files(review) }
        ReviewDiffSteps.useInTests(testRootDisposable, reviewFilesOf = recording)
        return asked
    }

    private fun theReviewsFiles(review: DiffEditorViewer): ReviewFiles = checkNotNull(ReviewDiffFileList.of(review))

    private inner class SlowReview(private val lagging: Boolean) {
        private var real: ReviewFiles? = null
        private var asked: Int? = null
        var shows = 0
            private set

        fun filesOf(review: DiffEditorViewer): ReviewFiles {
            val files = theReviewsFiles(review).also { real = it }
            return object : ReviewFiles {
                override val absolutePaths = files.absolutePaths
                override val shownIndex = if (lagging) files.shownIndex else asked ?: files.shownIndex
                override fun show(index: Int) {
                    shows++
                    asked = index
                }
            }
        }

        fun land() {
            checkNotNull(real).show(checkNotNull(asked) { "the review was never asked to show a file" })
        }
    }

    private fun slowReview(lagging: Boolean): SlowReview = SlowReview(lagging).also { slow -> reviewFilesAsking(slow::filesOf) }

    private fun ReviewFiles.showing(showing: (Int) -> Unit): ReviewFiles {
        val listed = this
        return object : ReviewFiles {
            override val absolutePaths = listed.absolutePaths
            override val shownIndex = listed.shownIndex
            override fun show(index: Int) = showing(index)
        }
    }

    private fun stepsAcrossReviewFiles(on: Boolean) {
        val state = MarginalisSettings.getInstance().state
        val before = state.keepStepsInReviewAcrossFiles
        state.keepStepsInReviewAcrossFiles = on
        Disposer.register(testRootDisposable) { state.keepStepsInReviewAcrossFiles = before }
    }

    private fun warningsWhile(act: () -> Unit): List<String> {
        val warnings = CopyOnWriteArrayList<String>()
        LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                if (category.endsWith(ReviewDiffSteps::class.java.simpleName)) warnings += message
                return super.processWarn(category, message, t)
            }
        }).use { act() }
        return warnings
    }

    private fun walkthrough(vararg steps: Pair<String, Int>): List<String> {
        val items = steps.mapIndexed { index, (file, line) ->
            mapOf(
                "file" to file,
                "line" to line,
                "anchor_text" to localLines(file)[line - 1],
                "order" to index + 1,
                "walkthrough" to "SHOP-42",
                "body" to "Step ${index + 1}",
            )
        }
        return agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results")
            .map { it.asJsonObject["thread_id"].asString }
    }

    private fun reviewOf(vararg files: ReviewedFile, unified: Boolean = false, before: (ReviewProcessorTab) -> Unit = {}): ReviewProcessorTab {
        val review = ReviewProcessorTab.open(project, editors, files.toList(), unified)
        before(review)
        waitFor("the review never showed its first file") { shownDiffOrNull()?.hasText() == true }
        return review
    }

    private fun reviewed(path: String, removing: Int? = null): ReviewedFile {
        val local = localLines(path)
        val pullRequest = ADDED_ON_TOP + local.filterIndexed { index, _ -> index + 1 != removing }
        return ReviewedFile(path, text(local), text(pullRequest))
    }

    private fun rowOf(line: Int) = line + ADDED_ON_TOP.size

    private fun refreshed(review: ReviewProcessorTab) {
        val viewerBefore = shownDiff().viewer
        review.refresh()
        waitFor("the review never showed the file again") { shownDiffOrNull()?.takeIf { it.viewer !== viewerBefore }?.hasText() == true }
    }

    private fun shown(review: ReviewProcessorTab, file: String) {
        val viewerBefore = shownDiff().viewer
        review.switchTo(file)
        waitFor("the review never showed $file") {
            review.shownFile() == file && shownDiffOrNull()?.takeIf { it.viewer !== viewerBefore }?.hasText() == true
        }
    }

    private fun closeTheReview() {
        val tab = editors.allEditors.filterIsInstance<DiffEditorViewerFileEditor>().single()
        editors.closeFile(tab.file)
        assertTrue("the review is still open", tab.editorViewer.disposable.isDisposed)
    }

    private fun backToTheReview() {
        val tab = editors.allEditors.filterIsInstance<DiffEditorViewerFileEditor>().single()
        editors.openFile(tab.file, true)
        waitFor("the review never came back to the front") { shownDiffOrNull()?.hasText() == true }
    }

    private fun assertPanelRowsBecome(expected: List<Int>) {
        waitFor("the review's panels never settled on $expected: ${panelRows()}") { panelRows() == expected }
        assertEquals(expected, panelRows())
    }

    private fun ReviewDiff.hasText() = editor.document.textLength > 0

    private fun openStepInTheReview(row: Int) {
        shownDiff().clickIcon(row)
    }

    private fun assertStepOpensInTheReview(review: ReviewProcessorTab, file: String, row: Int) {
        waitFor("the step never opened on row $row of $file in the review: ${review.shownFile()} ${panelRows()}") {
            editors.selectedEditor !is DiffEditorViewerFileEditor || (review.shownFile() == file && panelRows() == listOf(row))
        }
        assertTrue("the review left the front: ${editors.selectedEditor}", editors.selectedEditor is DiffEditorViewerFileEditor)
        assertEquals(file, review.shownFile())
        assertEquals("the step's thread opens on its row", listOf(row), panelRows())
        val editor = shownDiff().editor
        assertEquals("the caret moves to the step's row", row, editor.caretModel.logicalPosition.line + 1)
    }

    private fun assertStepOpensInItsFileTab(file: String, line: Int) {
        waitFor("the step never opened in $file's tab") { editors.selectedTextEditor?.let(::fileOf) == file }
        val editor = editors.selectedTextEditor!!
        assertEquals(1, user.panels(editor).size)
        assertEquals(line, editor.caretModel.logicalPosition.line + 1)
    }

    private fun fileOf(editor: Editor): String? =
        FileDocumentManager.getInstance().getFile(editor.document)?.let { ProjectPaths.of(project, it) }

    private fun fileOf(file: VirtualFile): String? = ProjectPaths.of(project, file)

    private fun projectFile(path: String): VirtualFile = checkNotNull(project.guessProjectDir()?.findFileByRelativePath(path))

    private fun thread(id: String): CommentThread = checkNotNull(MarginalisStore.getInstance(project).threads.byId(id))

    private fun reviewContext(): DiffContext =
        editors.allEditors.filterIsInstance<DiffEditorViewerFileEditor>().single().editorViewer.context

    private fun aDiffContext(): DiffContext = object : DiffContext() {
        override fun getProject(): Project = this@ReviewDiffStepsAcrossFilesTest.project
        override fun isWindowFocused(): Boolean = true
        override fun isFocusedInWindow(): Boolean = true
        override fun requestFocusInWindow() {}
    }

    private fun panelRows(): List<Int> = shownDiffOrNull()?.panelRows().orEmpty()

    private fun shownDiff(): ReviewDiff = checkNotNull(shownDiffOrNull()) { "no diff in front: ${editors.selectedEditor}" }

    private fun shownDiffOrNull(): ReviewDiff? {
        val tab = editors.selectedEditor as? DiffEditorViewerFileEditor ?: return null
        val focus = tab.preferredFocusedComponent ?: return null
        val viewer = user.dataContextOf(focus).getData(DiffDataKeys.DIFF_VIEWER) as? DiffViewerBase ?: return null
        return ReviewDiff(project, viewer)
    }

    private fun waitFor(failure: String, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching(failure, condition, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private companion object {
        const val CART = "src/Cart.kt"
        const val ORDER = "src/Order.kt"
        const val INVOICE = "src/Invoice.kt"
        const val GONE = "src/Gone.kt"
        val ADDED_ON_TOP = listOf("// added 1", "// added 2", "// added 3")

        fun localLines(path: String): List<String> {
            val name = path.substringAfterLast('/').substringBefore('.').lowercase()
            return (1..30).map { "val $name$it = $it" }
        }

        fun text(lines: List<String>) = lines.joinToString("\n") + "\n"
    }
}
