package dev.marginalis.plugin.ui.diff

import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.IgnorePolicy
import dev.marginalis.plugin.testing.Gutter
import dev.marginalis.plugin.testing.PluginUnload
import dev.marginalis.plugin.testing.ReviewDiffs
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.ThreadInlayManager

class ReviewDiffTest : HttpContractTestCase() {

    private val diffs by lazy { ReviewDiffs(project, testRootDisposable) }

    private fun text(lines: List<String>) = lines.joinToString("\n") + "\n"

    private val base get() = text((1..60).map(::lineText))

    private val pullRequest get() = text(
        (1..60).flatMap { line ->
            when (line) {
                5 -> emptyList()
                10 -> listOf("// added 1", "// added 2", "// added 3", lineText(line))
                else -> listOf(lineText(line))
            }
        },
    )

    fun testASideBySideReviewShowsTheThreadOnTheLineItAnchorsToInThePullRequest() {
        sample()
        agent.addOnLine(20, "body" to "Check this")

        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)

        assertEquals(listOf(22), diff.iconRows())
        assertEquals(lineText(20), diff.textOfRow(22))
    }

    fun testAUnifiedReviewShowsTheThreadOnTheRowShowingItsAnchoredLine() {
        sample()
        agent.addOnLine(20, "body" to "Check this")

        val diff = diffs.unified(SAMPLE, base, pullRequest)

        val row = diff.iconRows().single()
        assertEquals(lineText(20), diff.textOfRow(row))
    }

    fun testOpeningAThreadFromAUnifiedReviewPutsItsPanelUnderItsAnchoredRow() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        val diff = diffs.unified(SAMPLE, base, pullRequest)
        val row = diff.iconRows().single()

        diff.clickIcon(row)

        assertEquals(1, diff.panels().size)
        assertEquals(lineText(20), diff.textOfRow(diff.panelRows().single()))
    }

    fun testAnOpenPanelStaysUnderItsRowWhenAUnifiedReviewIgnoresWhitespace() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        val reindented = text((1..60).flatMap { if (it == 10) listOf("// added", "  " + lineText(it)) else listOf(lineText(it)) })
        val diff = diffs.unified(SAMPLE, base, reindented)
        diff.clickIcon(diff.iconRows().single())
        val rowBefore = diff.panelRows().single()
        val settings = (diff.viewer as UnifiedDiffViewer).textSettings
        val policy = settings.ignorePolicy
        try {
            settings.ignorePolicy = IgnorePolicy.IGNORE_WHITESPACES
            diff.rediff()

            val row = diff.panelRows().single()
            assertTrue("the review should have moved its rows", row != rowBefore)
            assertEquals(lineText(20), diff.textOfRow(row))
            assertEquals(listOf(row), diff.iconRows())
            diff.clickIcon(row)
            assertEquals(emptyList<Any>(), diff.panels())
        } finally {
            settings.ignorePolicy = policy
        }
    }

    fun testAnAddedFileShowsItsThreads() {
        sample()
        agent.addOnLine(20, "body" to "Check this")

        val diff = diffs.added(SAMPLE, pullRequest)

        assertEquals(listOf(22), diff.iconRows())
    }

    fun testAnOrphanWhoseAnchorIsInThePullRequestShowsInASideBySideReview() {
        sample()
        agent.addOnLine(20, "body" to "Gone locally")
        eraseLine(20)
        agent.threads()

        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)

        assertEquals(listOf(22), diff.iconRows())
        assertTrue(diff.tooltipAt(22), diff.tooltipAt(22).contains("orphaned"))
    }

    fun testAnOrphanWhoseAnchorIsInThePullRequestShowsInAUnifiedReview() {
        sample()
        agent.addOnLine(20, "body" to "Gone locally")
        eraseLine(20)
        agent.threads()

        val diff = diffs.unified(SAMPLE, base, pullRequest)

        val row = diff.iconRows().single()
        assertEquals(lineText(20), diff.textOfRow(row))
    }

    fun testAResolvedThreadShowsInNoReview() {
        sample()
        val id = agent.addOnLine(20, "body" to "Done")
        agent.post("comment_resolve", "thread_id" to id)

        assertEquals(emptyList<Int>(), diffs.sideBySide(SAMPLE, base, pullRequest).iconRows())
        assertEquals(emptyList<Int>(), diffs.unified(SAMPLE, base, pullRequest).iconRows())
    }

    fun testAThreadWhoseAnchorIsNotInAUnifiedReviewOpensNoPanelThere() {
        sample()
        val id = agent.addOnLine(5, "body" to "Removed by the pull request")
        val diff = diffs.unified(SAMPLE, base, pullRequest)

        ThreadInlayManager.open(project, diff.editor, user.thread(id))

        assertEquals(emptyList<Any>(), diff.panels())
    }

    fun testAThreadWhoseAnchorIsNotInASideBySideReviewOpensNoPanelThere() {
        sample()
        val id = agent.addOnLine(5, "body" to "Removed by the pull request")
        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)

        ThreadInlayManager.open(project, diff.editor, user.thread(id))

        assertEquals(emptyList<Any>(), diff.panels())
    }

    fun testAUnifiedDiffOfLocalChangesGetsNoIconsOfItsOwn() {
        sample()
        agent.addOnLine(20, "body" to "Check this")

        val diff = diffs.localChanges(SAMPLE, pullRequest, unified = true)

        assertEquals(listOf(20), Gutter.linesShowing(project, document(), "Check this"))
        assertEquals(emptyList<Int>(), diff.iconRows())
    }

    fun testASideBySideDiffOfLocalChangesGetsNoIconsOfItsOwn() {
        sample()
        agent.addOnLine(20, "body" to "Check this")

        val diff = diffs.localChanges(SAMPLE, pullRequest, unified = false)

        assertEquals(listOf(20), Gutter.linesShowing(project, document(), "Check this"))
        assertEquals(emptyList<Int>(), diff.iconRows())
    }

    fun testANewThreadAppearsInAnOpenReview() {
        sample()
        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)

        agent.addOnLine(20, "body" to "Late")

        assertEquals(listOf(22), diff.iconRows())
    }

    fun testClosingAReviewWhereAThreadWasOpenedLeavesNothingListeningToTheMargin() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        val listening = store.threads.listenerCount
        val diff = diffs.unified(SAMPLE, base, pullRequest)
        diff.clickIcon(diff.iconRows().single())
        assertEquals(1, diff.panels().size)

        diff.close()

        assertEquals(listening, store.threads.listenerCount)
    }

    fun testClosingAReviewLetsGoOfItsIconsAndStillTellsTheViewersOtherListeners() {
        sample()
        agent.addOnLine(20, "body" to "Check this")
        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)
        diff.clickIcon(22)
        val icons = diff.iconHighlighters()
        assertEquals(1, icons.size)

        diff.close()

        assertTrue(diff.laterListenersToldOfClose)
        assertTrue(icons.none { it.isValid })
        assertNull(diff.editor.getUserData(ThreadInlayManager.THREAD_LINE))
    }

    fun testAThreadAddedAfterAReviewClosedStillReachesTheEditor() {
        sample()
        val diff = diffs.sideBySide(SAMPLE, base, pullRequest)
        diff.close()

        agent.addOnLine(30, "body" to "After the diff closed")

        assertNotNull(user.gutterIcon(SAMPLE, 30))
    }

    fun testUpdatingThePluginWithAReviewOpenTakesItsIconsPanelsAndListenersOutOfTheDiff() {
        sample()
        agent.addOnLine(20, "body" to "Open in the review")
        val listening = store.threads.listenerCount
        val diff = diffs.unified(SAMPLE, base, pullRequest)
        diff.clickIcon(diff.iconRows().single())
        assertEquals(1, diff.panels().size)

        val failure = PluginUnload.forUpdate()

        assertNull(failure)
        assertEquals(emptyList<Int>(), diff.iconRows())
        assertEquals(emptyList<Any>(), diff.panels())
        assertNull(diff.editor.getUserData(ThreadInlayManager.THREAD_LINE))
        assertEquals(listening, store.threads.listenerCount)
    }
}
