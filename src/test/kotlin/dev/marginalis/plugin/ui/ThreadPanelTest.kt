package dev.marginalis.plugin.ui

import com.intellij.openapi.editor.markup.HighlighterLayer
import dev.marginalis.plugin.settings.TimeFormat
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JLabel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.RenderedBody
import dev.marginalis.plugin.transport.HttpContractTestCase

class ThreadPanelTest : HttpContractTestCase() {

    private val you get() = Authors.user.displayName

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    private fun bylines(panel: ThreadPanel): List<String> =
        user.shownText(panel).lines().filter { it.matches(Regex("^.+ · \\d{1,2}:\\d{2}.*$")) }

    fun testTheConversationShowsEachMessageUnderItsAuthorInOrder() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop.")
        user.reply(id, "Into what?")
        agent.reply(id, "A `sumOf` call.")

        val panel = user.open(id).also { user.press(it, "Expand All") }
        val shown = user.shownText(panel)

        assertTrue(shown, shown.indexOf("Extract this loop.") < shown.indexOf("Into what?"))
        assertTrue(shown, shown.indexOf("Into what?") < shown.indexOf("A sumOf call."))
        assertEquals(listOf("Claude", you, "Claude"), bylines(panel).map { it.substringBefore(" · ") })
    }

    fun testAnAgentsFollowUpSharesItsBylineAndCarriesItInATooltip() {
        sample()
        val id = agent.addOnLine(5, "body" to "First.")
        agent.reply(id, "Second.")

        val panel = user.open(id)

        assertEquals(1, bylines(panel).size)
        assertTrue(user.tooltips(panel).any { it.startsWith("Claude · ") })
    }

    fun testTheHeaderSaysTheThreadIsOpenOrWhereItIsInAWalkthrough() {
        sample()
        val loose = agent.addOnLine(5)
        assertTrue(user.shownText(user.open(loose)).lines().contains("open"))

        val steps = (1..3).map { agent.addOnLine(10 + it, "order" to it, "walkthrough" to "tour") }

        assertTrue(user.shownText(user.open(steps[1])).lines().contains("open · step 2/3"))
    }

    fun testUnorderedThreadsCountTheirPlaceAmongTheOpenThreads() {
        sample()
        agent.addOnLine(5)
        val second = agent.addOnLine(9)

        assertTrue(user.shownText(user.open(second)).lines().contains("open · step 2/2"))
    }

    fun testAStepKeepsItsTotalWhileEarlierStepsResolve() {
        sample()
        val steps = (1..3).map { agent.addOnLine(10 + it, "order" to it, "walkthrough" to "tour") }
        agent.post("comment_resolve", "thread_id" to steps[0])

        assertTrue(user.shownText(user.open(steps[2])).lines().contains("open · step 3/3"))
    }

    fun testAResolvedThreadSaysWhoResolvedItAndOffersReopen() {
        sample()
        val id = agent.addOnLine(5)
        agent.post("comment_resolve", "thread_id" to id)

        val panel = user.open(id)

        assertTrue(user.shownText(panel).lines().contains("resolved by Claude"))
        assertTrue(user.toolbar(panel).getValue("Reopen").isEnabledAndVisible)
    }

    fun testAnOrphanSaysItsAnchorWasDeleted() {
        sample()
        val id = agent.addOnLine(5)
        eraseLine(5)
        agent.threads()

        val panel = user.open(id)

        assertTrue(user.shownText(panel).lines().contains("orphaned (anchor deleted)"))
    }

    fun testIntentSeverityAndLabelShowAsChips() {
        sample()
        val blocker = agent.addOnLine(5, "severity" to "blocker", "intent" to "finding")
        val nit = agent.addOnLine(6, "severity" to "nit")
        val praise = agent.addOnLine(7, "intent" to "fyi", "label" to "praise")
        val fyi = agent.addOnLine(8, "intent" to "fyi")

        val blockerPanel = user.open(blocker)
        assertTrue(user.shownText(blockerPanel).lines().contains("blocker"))
        assertTrue(user.tooltips(blockerPanel).contains("finding"))
        assertTrue(user.shownText(user.open(nit)).lines().contains("nit"))
        assertTrue(user.tooltips(user.open(nit)).contains("ordinary comment"))
        assertTrue(user.shownText(user.open(praise)).lines().contains("praise"))
        assertFalse(user.shownText(user.open(praise)).lines().contains("fyi"))
        assertTrue(user.shownText(user.open(fyi)).lines().contains("fyi"))
    }

    // FixMe: Known issue - #62 "Seen by" names an agent that has only read the thread by its id, although every read carries its author_name
    fun testTheUsersMessageIsMarkedSeenOnceAnAgentReadsIt() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Why?")
        assertFalse(user.shownText(user.open(id)).contains("✓ seen"))

        agent.threads()
        reviewer.threads()

        val panel = user.open(id)
        assertTrue(user.shownText(panel).lines().contains("✓ seen"))
        assertTrue(user.tooltips(panel).toString(), user.tooltips(panel).contains("Seen by Claude, claude-review"))
        assertFalse(user.visibleLinks(panel).contains("Edit"))
    }

    fun testSeenByNamesAnAgentOnceItHasWrittenInTheMargin() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Why?")
        reviewer.addOnLine(9)

        reviewer.threads()

        assertTrue(user.tooltips(user.open(id)).contains("Seen by Claude · review"))
    }

    fun testAMessageAddressedToAnAgentCarriesItsNameAsAChip() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.reply(id, "I can look.")

        user.reply(id, "Please do.", to = "claude-review")

        assertTrue(user.shownText(user.open(id)).lines().contains("@Claude · review"))
    }

    fun testAnAgentMessageAddressedToTheUserCarriesTheirName() {
        sample()
        val id = agent.addOnLine(5)

        agent.reply(id, "Your call.", "to" to "user")

        assertTrue(user.shownText(user.open(id)).lines().contains("@$you"))
    }

    fun testRelayedCommentsAreGroupedUnderTheirPullRequestWithTheirAuthor() {
        sample()
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "Use a constant.", "relayed" to Relays.reviewComment(101))
        agent.reply(id, "Agreed with Mona.", "relayed" to Relays.reviewComment(102, login = "ci[bot]", name = "ci[bot]"))
        agent.reply(id, "My own view.")

        val panel = user.open(id)
        val shown = user.shownText(panel)

        assertEquals(1, shown.lines().count { it == "From GitHub · PR #7" })
        assertTrue(shown, bylines(panel).any { it.startsWith("Mona Lisa · via Claude · ") })
        assertTrue(shown, bylines(panel).any { it.startsWith("ci[bot] · via Claude · ") })
        assertTrue(shown.lines().contains("bot"))
        assertTrue(user.tooltips(panel).contains("@mona on GitHub"))
        assertTrue(user.tooltips(panel).contains("Open on GitHub: https://github.com/acme/shop/pull/7#discussion_r101"))
        assertEquals(2, user.visibleLinks(panel).count { it == "↗" })
        assertTrue(bylines(panel).last().startsWith("Claude · "))
    }

    fun testARelayOfTheUsersOwnGitHubCommentIsTaggedOnGitHub() {
        val settings = MarginalisSettings.getInstance().state
        val before = settings.githubLogin
        settings.githubLogin = "mona"
        try {
            sample()
            val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "Use a constant.", "relayed" to Relays.reviewComment(101))

            val shown = user.shownText(user.open(id))

            assertTrue(shown, shown.lines().contains("on GitHub"))
        } finally {
            settings.githubLogin = before
        }
    }

    fun testAgreeingShowsAnAgreementLineAndTellsTheAgent() {
        sample()
        val id = agent.addOnLine(5, "body" to "Inline this.")
        assertTrue(user.canAgree(id))

        user.agree(id)

        val panel = user.open(id)
        assertTrue(user.shownText(panel).lines().any { it.startsWith("$you agreed · ") })
        assertFalse(user.visibleLinks(panel).contains("Agree"))
        val last = agent.threads().single().messages().last()
        assertEquals("Agreed.", last.string("body"))
        assertEquals("claude-main", last.string("to"))
    }

    fun testAnFyiStopsOfferingAgreeOnceItIsRead() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi")
        val panel = user.open(id)
        assertTrue(user.visibleLinks(panel).contains("Agree"))

        panel.markRead()
        settle()

        assertFalse(user.visibleLinks(panel).contains("Agree"))
    }

    fun testAgreeIsOfferedOnlyWhenAnAgentSpokeLast() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Hm.")

        assertFalse(user.canAgree(id))
    }

    fun testReplyOpensTheComposerAndSubmitAddsTheMessage() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        assertTrue(user.visibleLinks(panel).contains("Reply…"))

        user.clickLink(panel, "Reply…")
        assertEquals("Reply", user.submitLabel(panel))
        user.type(panel, "  Fine by me.  ")
        user.submit(panel)

        assertEquals("Fine by me.", agent.threads().single().messages().last().string("body"))
        assertTrue(user.shownText(panel).contains("Fine by me."))
        assertTrue(user.visibleLinks(panel).contains("Reply…"))
    }

    fun testABlankReplyIsNotSubmitted() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.type(panel, "   \n ")
        user.submit(panel)

        assertEquals(1, agent.threads().single().messages().size)
    }

    fun testTheSubmitShortcutSubmitsTheReply() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Shortcut reply.")

        user.pressSubmitShortcut(panel)

        assertEquals("Shortcut reply.", agent.threads().single().messages().last().string("body"))
    }

    fun testEscapeOnAThreadWithItsReplyBoxClosedFoldsIt() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.pressEscape(panel)

        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testEscapeOnTheThreadClosesAnOpenReplyBoxFirst() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "half a thought")

        user.pressEscape(panel)

        assertEquals(listOf(panel), user.panels())
        assertTrue(user.visibleLinks(panel).contains("Reply…"))
        assertEquals("half a thought", user.composer(panel).text)
        assertEquals(1, agent.threads().single().messages().size)
    }

    fun testADraftAndItsAddresseeSurviveClosingThePanel() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.reply(id, "Here too.")
        user.addresseeShownWhileMentioning(id, "claude-review")
        user.type(user.panel(), "for you")

        user.press(user.panel(), "Close")
        val reopened = user.open(id)

        assertEquals("for you", user.composer(reopened).text)
        assertTrue(user.visibleLinks(reopened).contains("@Claude · review ✕"))
    }

    fun testClearingTheAddresseeAddressesEveryone() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.reply(id, "Here too.")
        user.addresseeShownWhileMentioning(id, "claude-review")
        val panel = user.panel()

        user.clickLink(panel, "@Claude · review ✕")
        user.type(panel, "for everyone")
        user.submit(panel)

        assertNull(agent.threads().single().messages().last().string("to"))
    }

    fun testEditingAnUnreadMessageShowsSaveAndCancelRestoresIt() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Typo hre.")
        val panel = user.open(id)

        user.clickLink(panel, "Edit")
        assertEquals("Save", user.submitLabel(panel))
        assertEquals("Typo hre.", user.composer(panel).text)
        assertTrue(user.shownText(panel).lines().contains("editing below ↓"))
        assertEquals(emptyList<String>(), user.sendOptions(panel))

        user.clickLink(panel, "Cancel")

        assertTrue(user.visibleLinks(panel).contains("Reply…"))
        assertFalse(user.visibleLinks(panel).contains("Cancel"))
        assertEquals("Reply", user.submitLabel(panel))
        assertEquals("", user.composer(panel).text)
        assertEquals("Typo hre.", agent.threads().single().messages().last().string("body"))
    }

    fun testSavingAnEditRevisesTheMessageInPlace() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Typo hre.")

        user.edit(id, "Typo here.")

        val messages = agent.threads().single().messages()
        assertEquals(2, messages.size)
        assertEquals("Typo here.", messages.last().string("body"))
    }

    fun testTypingAnAtWhileEditingDoesNotAskForAnAddressee() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Ask ")
        val panel = user.open(id)
        user.clickLink(panel, "Edit")

        user.type(panel, "Ask @")

        assertFalse(user.visibleLinks(panel).any { it.startsWith("@") })
    }

    fun testQuoteCodeInsertsTheAnchoredLineAsAFence() {
        sample()
        val id = agent.addOnLine(12)
        val panel = user.open(id)
        user.clickLink(panel, "Reply…")

        user.clickLinkWithTooltip(panel, "Quote code")

        assertEquals("```kt\nval line12 = 12\n```\n", user.composer(panel).text)
    }

    fun testQuoteCodeQuotesTheEditorSelectionAfterWhatIsTyped() {
        sample()
        val id = agent.addOnLine(12)
        val panel = user.open(id)
        user.type(panel, "Compare")
        val editor = user.editor()
        val start = editor.document.getLineStartOffset(2)
        editor.selectionModel.setSelection(start, editor.document.getLineEndOffset(3))

        user.clickLinkWithTooltip(panel, "Quote code")

        assertEquals("Compare\n```kt\nval line3 = 3\nval line4 = 4\n```\n", user.composer(panel).text)
    }

    fun testQuoteCodeQuotesTheSelectionACommentIsAbout() {
        sample()
        user.comment(SAMPLE, 12, "rename?", selecting = "line12")
        val panel = user.panel()
        user.editor().selectionModel.removeSelection()
        user.clickLink(panel, "Reply…")

        user.clickLinkWithTooltip(panel, "Quote code")

        assertEquals("```kt\nline12\n```\n", user.composer(panel).text)
    }

    fun testACodeFenceInTheComposerIsHighlightedInItsLanguage() {
        sample()
        val panel = user.open(agent.addOnLine(5))
        val composer = user.composer(panel)

        user.type(panel, "Try:\n```java\nclass A { int x = 1; }\n```\n")
        val painted = composer.editor!!.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.SYNTAX + 1 }
        assertFalse(painted.isEmpty())
        assertTrue(painted.all { it.startOffset >= composer.text.indexOf("class") && it.endOffset <= composer.text.lastIndexOf("```") })

        user.type(panel, "No code any more.")
        assertEquals(emptyList<Any>(), composer.editor!!.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.SYNTAX + 1 })
    }

    fun testAFenceWithoutAKnownLanguageStaysPlain() {
        sample()
        val panel = user.open(agent.addOnLine(5))
        val composer = user.composer(panel)

        user.type(panel, "```\nclass A {}\n```\n```nosuchlanguage\nclass B {}\n```\n```java\n```\n")

        assertEquals(emptyList<Any>(), composer.editor!!.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.SYNTAX + 1 })
    }

    fun testCodeInAMessageShowsAsACodeBlock() {
        sample()
        val id = agent.addOnLine(5, "body" to "Like this:\n```kotlin\nval total = items.sumOf { it.price }\n```\nThen done.")

        val shown = user.shownText(user.open(id))

        assertTrue(shown, shown.lines().contains("val total = items.sumOf { it.price }"))
        assertTrue(shown, shown.lines().contains("Like this:"))
        assertTrue(shown, shown.lines().contains("Then done."))
    }

    fun testAnAgentReplyAppearsInAnOpenPanel() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        agent.reply(id, "Late addition.")
        settle()

        assertTrue(user.shownText(panel).contains("Late addition."))
    }

    fun testAThreadTheAgentDeletesClosesItsOpenPanel() {
        sample()
        val id = agent.addOnLine(5)
        user.open(id)

        agent.post("comment_clear_all", "file" to SAMPLE)
        settle()

        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testAReplyThatArrivedWhileThePanelWasInABackgroundTabShowsWhenItComesBack() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.whileInABackgroundTab(panel) { agent.reply(id, "Posted while you were away.") }

        assertTrue(user.shownText(panel).contains("Posted while you were away."))
    }

    fun testAReopenWhileThePanelWasInABackgroundTabShowsWhenItComesBack() {
        sample()
        val id = agent.addOnLine(5)
        agent.post("comment_resolve", "thread_id" to id)
        val panel = user.open(id)

        user.whileInABackgroundTab(panel) { agent.post("comment_reopen", "thread_id" to id) }

        assertTrue(user.shownText(panel).lines().contains("open"))
    }

    fun testAReadReceiptWhileThePanelWasInABackgroundTabShowsWhenItComesBack() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Why?")
        val panel = user.open(id)

        user.whileInABackgroundTab(panel) { agent.threads() }

        assertTrue(user.shownText(panel).lines().contains("✓ seen"))
    }

    fun testANewCommentsDraftAndItsAddresseeSurviveABackgroundTab() {
        sample()
        reviewer.addOnLine(9)
        user.comment(SAMPLE, 12)
        val draft = user.panel()
        user.mention(draft, "claude-review")
        user.type(draft, "for you")
        val other = agent.addOnLine(5)

        user.whileInABackgroundTab(draft) { agent.reply(other, "Posted on another thread.") }

        assertEquals(listOf(draft), user.panels())
        assertTrue(user.shownText(draft).lines().contains("new comment — unsent"))
        assertEquals("for you", user.composer(draft).text)
        assertTrue(user.visibleLinks(draft).contains("@Claude · review ✕"))
    }

    fun testAnUnsentReplySurvivesCatchingUpOnAnAgentsReply() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "half a thought")

        user.whileInABackgroundTab(panel) { agent.reply(id, "Posted while you were away.") }

        assertTrue(user.shownText(panel).contains("Posted while you were away."))
        assertEquals("half a thought", user.composer(panel).text)
    }

    fun testAThreadDeletedWhileItsPanelWasInABackgroundTabLeavesNoPanel() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.whileInABackgroundTab(panel) { agent.post("comment_clear_all", "file" to SAMPLE) }

        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testAThreadAddedAboveWhileThePanelWasInABackgroundTabMovesItsStepWhenItComesBack() {
        sample()
        agent.addOnLine(5)
        val panel = user.open(agent.addOnLine(9))
        assertTrue(user.shownText(panel).lines().contains("open · step 2/2"))

        user.whileInABackgroundTab(panel) { agent.addOnLine(3) }

        assertTrue(user.shownText(panel).lines().contains("open · step 3/3"))
    }

    fun testAThreadResolvedWhileThePanelWasInABackgroundTabDropsItsStepWhenItComesBack() {
        sample()
        val earlier = agent.addOnLine(5)
        val panel = user.open(agent.addOnLine(9))

        user.whileInABackgroundTab(panel) { agent.post("comment_resolve", "thread_id" to earlier) }

        assertTrue(user.shownText(panel).lines().contains("open"))
    }

    fun testAPanelSentBackBeforeItCaughtUpStaysAsItWas() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        panel.removeNotify()
        agent.reply(id, "Posted while you were away.")

        panel.addNotify()
        panel.removeNotify()
        settle()

        assertFalse(user.shownText(panel).contains("Posted while you were away."))
        assertTrue(panel in UIUtil.findComponentsOfType(user.editor().contentComponent, ThreadPanel::class.java))
        assertFalse(user.thread(id).messages.last().readByUser)
    }

    fun testComingBackFromABackgroundTabAgainAndAgainAddsNoListeners() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        val listening = MarginalisStore.getInstance(project).threads.listenerCount

        repeat(3) { user.whileInABackgroundTab(panel) {} }

        assertEquals(listening, MarginalisStore.getInstance(project).threads.listenerCount)
    }

    fun testASelectionInAMessageSurvivesABackgroundTabWhenNothingChanged() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop.")
        val panel = user.open(id)
        RenderedBody.of(panel).select("Extract")

        user.whileInABackgroundTab(panel) {}

        assertEquals("Extract", RenderedBody.of(panel).selection("Extract"))
    }

    fun testASelectionInAMessageSurvivesABackgroundTabWhileAnotherThreadChanged() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract this loop.")
        val other = agent.addOnLine(9)
        val panel = user.open(id)
        RenderedBody.of(panel).select("Extract")

        user.whileInABackgroundTab(panel) { agent.reply(other, "Posted on another thread.") }

        assertEquals("Extract", RenderedBody.of(panel).selection("Extract"))
    }

    fun testAPanelWhoseHostKeepsItClosesOnceItSeesItsThreadWasDeletedMeanwhile() {
        sample()
        val id = agent.addOnLine(5)
        var closed = false
        val panel = ThreadPanel(project, null, user.thread(id), ensureStored = {}, onClose = { closed = true }, hostWidth = { 400 })
        agent.post("comment_clear_all", "file" to SAMPLE)

        panel.addNotify()
        settle()
        panel.removeNotify()

        assertTrue(closed)
    }

    fun testCommandEnterInTheComposerSubmits() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Keyed in.")

        user.pressKeyInComposer(panel, KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK)

        assertEquals("Keyed in.", agent.threads().single().messages().last().string("body"))
    }

    fun testControlEnterInTheComposerSubmitsToo() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Keyed in.")

        user.pressKeyInComposer(panel, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)

        assertEquals("Keyed in.", agent.threads().single().messages().last().string("body"))
    }

    fun testControlShiftEnterInTheComposerSubmitsToo() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Keyed in.")

        user.pressKeyInComposer(panel, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)

        assertEquals("Keyed in.", agent.threads().single().messages().last().string("body"))
    }

    fun testTheControlEnterShortcutSubmitsTheReply() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Shortcut reply.")

        user.pressSubmitShortcut(panel, "control ENTER")

        assertEquals("Shortcut reply.", agent.threads().single().messages().last().string("body"))
    }

    fun testAPlainEnterInTheComposerOnlyTypes() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "Line one")

        user.pressKeyInComposer(panel, KeyEvent.VK_ENTER)

        assertEquals(1, agent.threads().single().messages().size)
    }

    fun testEscapeInTheComposerClosesTheReplyBoxAndKeepsTheThreadOpen() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "keep me")

        user.pressKeyInComposer(panel, KeyEvent.VK_ESCAPE)

        assertEquals(listOf(panel), user.panels())
        assertTrue(user.visibleLinks(panel).contains("Reply…"))
        assertEquals("keep me", user.composer(panel).text)
    }

    fun testASecondEscapeFoldsTheThreadAndKeepsTheDraft() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        user.type(panel, "keep me")
        user.pressKeyInComposer(panel, KeyEvent.VK_ESCAPE)

        user.pressEscape(panel)

        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals("keep me", user.composer(user.open(id)).text)
        assertEquals(1, agent.threads().single().messages().size)
    }

    fun testEscapeWhileEditingAMessageCancelsTheEditAndKeepsTheThreadOpen() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Typo hre.")
        val panel = user.open(id)
        user.clickLink(panel, "Edit")
        user.type(panel, "Typo here, half-fixed")

        user.pressKeyInComposer(panel, KeyEvent.VK_ESCAPE)

        assertEquals(listOf(panel), user.panels())
        assertTrue(user.visibleLinks(panel).contains("Reply…"))
        assertEquals("Reply", user.submitLabel(panel))
        assertEquals("Typo hre.", agent.threads().single().messages().last().string("body"))
    }

    fun testBylinesFollowTheChosenClock() {
        sample()
        val id = agent.addOnLine(5)
        val settings = MarginalisSettings.getInstance()
        val before = settings.state.timeFormat
        try {
            settings.state.timeFormat = TimeFormat.TWELVE_HOUR.stored
            val twelve = bylines(user.open(id)).single()
            assertTrue(twelve, twelve.matches(Regex("Claude · \\d{1,2}:\\d{2}\\s?[AaPp]\\.?[Mm]\\.?")))

            settings.state.timeFormat = TimeFormat.TWENTY_FOUR_HOUR.stored
            val twentyFour = bylines(user.open(id)).single()
            assertTrue(twentyFour, twentyFour.matches(Regex("Claude · \\d{2}:\\d{2}")))
        } finally {
            settings.state.timeFormat = before
        }
    }

    fun testAgreeingWithAPanelThatFellBehindAgreesWithNothing() {
        sample()
        val id = agent.addOnLine(5, "body" to "Inline this.")
        val panel = user.open(id)
        agent.reply(id, "Actually, wait.")

        user.clickLink(panel, "Agree")

        assertEquals(listOf("Inline this.", "Actually, wait."), agent.threads().single().messages().map { it.string("body") })
        assertTrue(user.shownText(panel).contains("Actually, wait."))
    }

    fun testAnAgreementTheAgentReadIsMarkedSeen() {
        sample()
        val id = agent.addOnLine(5, "body" to "Inline this.")
        user.agree(id)

        agent.threads()

        assertTrue(user.tooltips(user.open(id)).contains("Seen by Claude"))
    }

    fun testAWorkingAgentsNamePulsesWhileTheThreadIsOnScreen() {
        sample()
        val id = agent.addOnLine(5)
        val waiting = agent.startWait()
        val panel = user.open(id)
        user.press(panel, "Go Live: each Submit wakes Claude with this thread")
        user.type(panel, "Go.")
        user.submit(panel)
        agent.await(waiting)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val working = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text == "Claude is working…" }
        val shades = mutableSetOf(working.foreground)

        PlatformTestUtil.waitWithEventsDispatching("the name never pulsed", { shades.add(working.foreground); shades.size > 1 }, 5)
    }
}
