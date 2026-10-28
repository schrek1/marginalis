package dev.marginalis.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.FileIconPatcher
import com.intellij.openapi.editor.Inlay
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.AggregateState
import dev.marginalis.core.Intent
import dev.marginalis.core.Mark
import dev.marginalis.core.MarkSubject
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.testing.LiveBadge
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.transport.HttpContractTestCase

class GutterTest : HttpContractTestCase() {

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    private fun tooltipAt(line: Int, file: String = SAMPLE): String = user.gutterIcon(file, line)!!.tooltipText

    private fun iconAt(line: Int, file: String = SAMPLE) = user.gutterIcon(file, line)!!.icon

    fun testTheTooltipNamesTheAuthorStatusAndMessageCountAndPreviewsTheFirstMessage() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop.")
        agent.reply(id, "And name it.")
        settle()

        val tooltip = tooltipAt(5)

        assertTrue(tooltip, tooltip.contains("<b>Claude</b> · open · 2 message(s)"))
        assertTrue(tooltip, tooltip.contains("Extract this loop."))
        assertFalse(tooltip, tooltip.contains("And name it."))
        assertTrue(tooltip, tooltip.contains("Click to open thread"))
    }

    fun testTheTooltipPreviewDropsMarkdownEscapesMarkupAndShortensLongBodies() {
        sample()
        agent.addOnLine(5, "body" to "**Bold** `code` <i>tag " + "word ".repeat(60))
        settle()

        val tooltip = tooltipAt(5)

        assertTrue(tooltip, tooltip.contains("Bold code &lt;i&gt;tag word"))
        assertFalse(tooltip, tooltip.contains("**"))
        assertTrue(tooltip, tooltip.contains("...<br/>"))
        assertFalse(tooltip, tooltip.contains("word ".repeat(30)))
    }

    fun testTheTooltipPreviewKeepsUnderscoresAndAngleBracketsInPlainText() {
        sample()
        agent.addOnLine(5, "body" to "Rename max_retry_count; keep List<String> when a > b")
        settle()

        assertTrue(tooltipAt(5), tooltipAt(5).contains("Rename max_retry_count; keep List&lt;String&gt; when a &gt; b"))
    }

    fun testAThreadTheUserStartedNamesTheUserInTheTooltip() {
        sample()
        user.comment(SAMPLE, 7, "Is this needed?")
        settle()

        assertTrue(tooltipAt(7), tooltipAt(7).contains("<b>${Authors.user.displayName}</b> · open · 1 message(s)"))
    }

    fun testThreadsSharingALineListEveryAuthorAndOfferAChoice() {
        sample()
        agent.addOnLine(5, "body" to "first thought")
        reviewer.addOnLine(5, "body" to "second thought")
        settle()

        val tooltip = tooltipAt(5)

        assertTrue(tooltip, tooltip.contains("<b>2 Marginalis threads</b>"))
        assertTrue(tooltip, tooltip.contains("<b>Claude</b> · first thought"))
        assertTrue(tooltip, tooltip.contains("<b>Claude · review</b> · second thought"))
        assertTrue(tooltip, tooltip.contains("Click to choose"))
    }

    fun testClickingTheIconOpensTheThreadAndClickingAgainClosesIt() {
        sample()
        agent.addOnLine(5, "body" to "Extract this loop.")
        settle()

        user.clickGutter(SAMPLE, 5)
        assertTrue(user.shownText(user.panel()).contains("Extract this loop."))

        user.clickGutter(SAMPLE, 5)
        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testClickingASharedIconOpensTheThreadPickedFromTheChooser() {
        sample()
        agent.addOnLine(5, "body" to "first thought")
        reviewer.addOnLine(5, "body" to "second thought")
        settle()
        val offered = mutableListOf<String>()
        user.choose(offered, "second thought")

        user.clickGutter(SAMPLE, 5)

        assertEquals(listOf("Claude · first thought", "Claude · review · second thought"), offered)
        val shown = user.shownText(user.panel())
        assertTrue(shown, shown.contains("second thought"))
        assertFalse(shown, shown.contains("first thought"))
    }

    fun testChoosingAnOpenThreadFromASharedIconFoldsItAsASingleIconDoes() {
        sample()
        agent.addOnLine(5, "body" to "first thought")
        reviewer.addOnLine(5, "body" to "second thought")
        settle()
        user.choose(mutableListOf(), "second thought")
        user.clickGutter(SAMPLE, 5)
        user.choose(mutableListOf(), "second thought")

        user.clickGutter(SAMPLE, 5)

        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testTheChooserQuotesTheSelectionASpanThreadIsAbout() {
        sample()
        agent.addOnLine(12, "body" to "whole line")
        user.comment(SAMPLE, 12, "rename?", selecting = "line12")
        user.closePanels()
        val offered = mutableListOf<String>()
        user.choose(offered, "line12")

        user.clickGutter(SAMPLE, 12)

        assertEquals(listOf("Claude · whole line", "${Authors.user.displayName} · “line12”"), offered)
        val shown = user.shownText(user.panel())
        assertTrue(shown, shown.contains("rename?"))
    }

    fun testTheIconCarriesTheThreadsIntent() {
        sample()
        agent.addOnLine(5, "intent" to "question")
        agent.addOnLine(9, "intent" to "fyi")
        settle()

        assertSame(MarginalisIcons.mark(Mark(MarkSubject.LINE, Intent.QUESTION)), iconAt(5))
        assertSame(MarginalisIcons.mark(Mark(MarkSubject.LINE, Intent.FYI)), iconAt(9))
    }

    fun testASharedLineWithDifferentIntentsShowsThePlainMark() {
        sample()
        agent.addOnLine(5, "intent" to "question")
        agent.addOnLine(5, "intent" to "finding")
        settle()

        assertSame(MarginalisIcons.LineMark, iconAt(5))
    }

    fun testAFileLevelThreadShowsTheFileMarkOnTheFirstLineAndOpensAboveIt() {
        sample()
        agent.add("file" to SAMPLE, "body" to "split this file")
        settle()

        assertSame(MarginalisIcons.mark(Mark(MarkSubject.FILE, null)), iconAt(1))
        user.clickGutter(SAMPLE, 1)

        val panel = user.panel()
        assertTrue(user.shownText(panel).contains("split this file"))
        val editor = user.editor()
        val inlay = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).single()
        assertEquals(Inlay.Placement.ABOVE_LINE, inlay.placement)
        assertEquals(0, editor.document.getLineNumber(inlay.offset))
    }

    fun testAFileLevelAndALineThreadOnTheFirstLineKeepSeparateIcons() {
        sample()
        agent.add("file" to SAMPLE, "body" to "split this file")
        agent.addOnLine(1, "body" to "rename line one")
        settle()

        val icons = user.gutterIconsOnLine(SAMPLE, 1)

        assertEquals(2, icons.size)
        assertTrue(icons.any { it.tooltipText.contains("split this file") })
        assertTrue(icons.any { it.tooltipText.contains("rename line one") })
    }

    fun testTheFileIconCarriesWhoseTurnItIsUntilNothingIsOwed() {
        sample()
        val file = project.guessProjectDir()!!.findFileByRelativePath(SAMPLE)!!
        val patcher = FileIconPatcher.EP_NAME.extensionList.filterIsInstance<TurnSignalIconPatcher>().single()
        val base = AllIcons.FileTypes.Text
        assertSame(base, patcher.patchIcon(base, file, 0, project))

        val id = agent.addOnLine(5)
        settle()
        val yourMove = patcher.patchIcon(base, file, 0, project)
        assertNotSame(base, yourMove)

        user.reply(id, "done")
        settle()
        val agentsMove = patcher.patchIcon(base, file, 0, project)
        assertNotSame(base, agentsMove)
        assertFalse(yourMove == agentsMove)

        agent.post("comment_resolve", "thread_id" to id)
        settle()
        assertSame(base, patcher.patchIcon(base, file, 0, project))
    }

    fun testFoldersOtherFilesAndOtherProjectsKeepTheirPlainIcons() {
        sample()
        val other = sample("src/Other.kt")
        agent.addOnLine(5)
        settle()
        val base = AllIcons.FileTypes.Text
        val patcher = TurnSignalIconPatcher()
        val root = project.guessProjectDir()!!

        assertSame(base, patcher.patchIcon(base, root.findFileByRelativePath("src")!!, 0, project))
        assertSame(base, patcher.patchIcon(base, root.findFileByRelativePath(other)!!, 0, project))
        assertSame(base, patcher.patchIcon(base, root.findFileByRelativePath(SAMPLE)!!, 0, null))
    }

    fun testAFileOutsideTheProjectKeepsItsPlainIconWhileTurnsAreTracked() {
        sample()
        agent.addOnLine(5)
        settle()
        val base = AllIcons.FileTypes.Text

        assertSame(base, TurnSignalIconPatcher().patchIcon(base, LightVirtualFile("Loose.kt", "val x = 1"), 0, project))
    }

    private fun stateDrawnFor(threadId: String): AggregateState = AggregateState.of(listOf(user.thread(threadId)))

    fun testTheUsersCommentIsDrawnUnreadUntilAnAgentListsIt() {
        sample()
        user.comment(SAMPLE, 7, "Why a val here?")
        settle()
        val id = agent.ids("summary" to true).single()
        assertNotNull(user.gutterIcon(SAMPLE, 7))
        assertEquals(AggregateState.UNREAD, stateDrawnFor(id))

        agent.threads()
        settle()

        assertEquals(AggregateState.OPEN, stateDrawnFor(id))
    }

    // FixMe: Known issue - #61 the gutter's unread badge tracks messages no agent has read, so a new agent reply never badges the line for the user
    fun testANewAgentReplyIsNotDrawnUnread() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop.")

        agent.reply(id, "And name it well.")
        settle()

        assertEquals(AggregateState.OPEN, stateDrawnFor(id))
    }

    // FixMe: Known issue - #61 when an agent reads the user's comment the editor is never told the line's icon changed, so the unread badge lingers until something else repaints the gutter
    fun testTheEditorIsNotToldTheIconChangedWhenAnAgentReadsTheUsersComment() {
        sample()
        user.comment(SAMPLE, 7, "Why a val here?")
        settle()
        val whileUnread = user.gutterIcon(SAMPLE, 7)!!

        agent.threads()
        settle()

        assertEquals(whileUnread, user.gutterIcon(SAMPLE, 7))
    }

    private fun showsTheLiveBadge(line: Int): Boolean {
        settle()
        return LiveBadge.shownIn(iconAt(line))
    }

    fun testNoMarkCarriesTheLiveBadgeWhileNothingIsLive() {
        sample()
        agent.addOnLine(5)

        assertFalse(showsTheLiveBadge(5))
    }

    fun testEveryOpenThreadsMarkCarriesTheLiveBadgeWhileTheProjectIsLive() {
        sample()
        agent.addOnLine(5)
        agent.addOnLine(9)

        user.goProjectLive()

        assertTrue(showsTheLiveBadge(5))
        assertTrue(showsTheLiveBadge(9))
    }

    fun testAThreadSwitchedOffInALiveProjectLosesTheLiveBadge() {
        sample()
        val switchedOff = agent.addOnLine(5)
        agent.addOnLine(9)
        user.goProjectLive()

        user.stopLive(switchedOff)

        assertFalse(showsTheLiveBadge(5))
        assertTrue(showsTheLiveBadge(9))
    }

    fun testTurningTheProjectOffTakesTheLiveBadgeOffEveryMark() {
        sample()
        agent.addOnLine(5)
        user.goProjectLive()

        user.stopProjectLive()

        assertFalse(showsTheLiveBadge(5))
    }

    fun testAThreadSwitchedLiveOnItsOwnCarriesTheLiveBadge() {
        sample()
        val switchedOn = agent.addOnLine(5)
        agent.addOnLine(9)
        agent.startWait("timeout" to 60)

        user.goLive(switchedOn)

        assertTrue(showsTheLiveBadge(5))
        assertFalse(showsTheLiveBadge(9))
    }

    fun testAResolvedThreadLeavesNoLiveMarkBehind() {
        sample()
        val id = agent.addOnLine(5)
        user.goProjectLive()

        user.resolve(id)
        settle()

        assertNull(user.gutterIcon(SAMPLE, 5))
    }

    fun testAnUnreadThreadsMarkCarriesTheLiveBadge() {
        sample()
        user.comment(SAMPLE, 7, "Why a val here?")

        user.goProjectLive()

        assertTrue(showsTheLiveBadge(7))
    }

    fun testTheLiveBadgeLeavesThePlatformDotWhole() {
        val dotted = LiveBadge.withPlatformDot(MarginalisIcons.LineMark)
        val dot = LiveBadge.platformDotArea(dotted.iconWidth, dotted.iconHeight, scale = 4)
        val before = Pixels.onTransparent(dotted, scale = 4)
        assertTrue(dot.any { (x, y) -> before.alphaAt(x, y) > 0 && Pixels.close(before.at(x.toDouble(), y.toDouble()), LiveBadge.DOT) })

        val live = Pixels.onTransparent(MarginalisIcons.withGutterLiveBadge(dotted), scale = 4)

        assertTrue(live.sameAs(before, dot))
    }

    fun testTheLiveBadgeSitsInAGapCutAlongTheBoltsOutline() {
        sample()
        agent.addOnLine(5)
        settle()
        val mark = Pixels.onTransparent(iconAt(5), scale = 4)

        user.goProjectLive()
        settle()

        val cleared = Pixels.onTransparent(iconAt(5), scale = 4).clearedFrom(mark)
        val bolt = LiveBadge.InTheCorner(box = 16, size = 8)
        val reach = bolt.distancesOf(cleared, scale = 4)
        assertFalse(cleared.isEmpty())
        assertTrue("cut reaches ${reach.max()}", reach.max() <= LiveBadge.GAP + bolt.strokeHalf + 0.1)
        assertTrue("cut reaches only ${reach.max()}", reach.max() >= LiveBadge.GAP + bolt.strokeHalf - 0.4)
    }
}
