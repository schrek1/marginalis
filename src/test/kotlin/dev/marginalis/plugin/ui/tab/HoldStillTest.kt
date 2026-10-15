package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.ui.WalkthroughNavigator

class HoldStillTest : TabInFrontTestCase() {

    override fun setUp() {
        super.setUp()
        user.changeProjectTabSettings { listWhileInFront("Hold still") }
        sample()
        lookAgain()
    }

    private fun lookAgain() {
        tab.openFileInFront(SAMPLE)
        tab.bringToFront()
    }

    fun testARepliedThreadKeepsItsPlaceWhileYouRead() {
        tab.sortBy("Recent activity")
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        lookAgain()

        agent.reply(alpha, "More on alpha")

        assertEquals(listOf("Bravo question [expanded]", "Alpha question [expanded]"), shown())
    }

    fun testNewThreadsWaitBehindAPillBelowUntilClicked() {
        agent.ask("Alpha question")
        lookAgain()

        agent.ask("Bravo question")
        agent.ask("Charlie question")

        assertEquals("↓ 2 new threads", tab.pill())
        assertEquals(listOf("Alpha question [expanded]"), shown())
        assertEquals("✉ 2", tab.title())
        tab.clickPill()
        assertNull(tab.pill())
        assertEquals(
            listOf("Alpha question [expanded]", "Bravo question [expanded]", "Charlie question [expanded]"),
            shown(),
        )
    }

    // FixMe: Known issue - #63 under Hold still the first thread in an empty tab waits behind the pill, leaving a blank tab with no empty-state text
    fun testTheFirstThreadInAnEmptyTabLeavesItBlankBehindThePill() {
        agent.ask("Alpha question")

        assertEquals("↓ 1 new thread", tab.pill())
        assertFalse(shown().contains(EMPTY_STATE))
        assertEquals(emptyList<String>(), shown())
    }

    fun testAThreadResolvedWhileYouReadStaysInPlaceDimmed() {
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        lookAgain()

        agent.post("comment_resolve", "thread_id" to alpha)

        assertEquals(listOf("Alpha question resolved just now [expanded]", "Bravo question [expanded]"), shown())
    }

    fun testLookingAwayAndBackLetsTheListCatchUp() {
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        lookAgain()
        agent.reply(alpha, "More on alpha")
        agent.ask("Charlie question")
        agent.post("comment_resolve", "thread_id" to alpha)

        lookAgain()

        assertNull(tab.pill())
        assertEquals(listOf("Bravo question [expanded]", "Charlie question [expanded]", "▸ 1 resolved"), shown())
    }

    fun testAThreadYouStartIsShownAtOnce() {
        agent.ask("Alpha question")
        lookAgain()

        tab.newProjectThread()
        user.type(tab.draft()!!, "My own topic")
        user.submit(tab.draft()!!)

        assertNull(tab.pill())
        assertEquals(listOf("Alpha question [expanded]", "✈ My own topic [expanded]"), shown())
    }

    fun testARelayedCommentJoinsItsHeldConversation() {
        agent.add("body" to "Looks good", "relayed" to Relays.conversationComment(1))
        lookAgain()

        agent.add("body" to "One more nit", "relayed" to Relays.conversationComment(2))

        assertNull(tab.pill())
        assertEquals(listOf("From GitHub · PR #7  ▸ 2 comments · 2 new"), shown())
    }

    fun testAConversationResolvedWhileYouReadSaysSo() {
        val looks = agent.add("body" to "Looks good", "relayed" to Relays.conversationComment(1))
        lookAgain()

        agent.post("comment_resolve", "thread_id" to looks)

        assertEquals(listOf("From GitHub · PR #7  ▸ 1 comment · 1 new · resolved just now"), shown())
    }

    fun testSwitchingBackToLiveLetsTheListFollowAtOnce() {
        tab.sortBy("Recent activity")
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        lookAgain()
        agent.reply(alpha, "More on alpha")

        user.changeProjectTabSettings { listWhileInFront("Live") }

        assertEquals(listOf("Alpha question [expanded]", "Bravo question [expanded]"), shown())
    }

    fun testARevealedThreadIsLetInPastThePillBelowTheOnesYouRead() {
        agent.ask("Alpha question")
        lookAgain()
        val bravo = agent.ask("Bravo question")

        WalkthroughNavigator.navigateTo(project, user.thread(bravo))
        tab.settle()

        assertEquals(listOf("Alpha question [expanded]", "Bravo question [expanded]"), shown())
        assertNull(tab.pill())
    }
}
