package dev.marginalis.plugin.ui.tab

import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.testing.Relays

class ReadingTest : TabInFrontTestCase() {

    fun testUnreadMessagesAddANewCountToTheHeaderUntilRead() {
        tab.windowActive = false
        agent.ask("First")
        agent.ask("Second")

        assertEquals("2 open · 2 your move · 0 agent's move · 2 new", tab.counts())
        tab.windowActive = true
        tab.click("First")
        assertEquals("2 open · 2 your move · 0 agent's move · 1 new", tab.counts())
    }

    fun testMessagesArrivingWhileYouLookElsewhereAreMarkedNew() {
        tab.windowActive = false

        agent.ask("Heads-up: CI moved", "intent" to "fyi")

        assertEquals(listOf("✉ Heads-up: CI moved ● [expanded]"), shown())
        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())
        assertTrue(user.shownText(tab.panel("Heads-up")).lines().contains("new"))
        assertEquals("✉ 1", tab.title())
    }

    fun testAnOpenThreadIsReadWhenYouComeBackToTheTabAndAnFyiThenWaitsOnNobody() {
        tab.windowActive = false
        agent.ask("Heads-up: CI moved", "intent" to "fyi")

        tab.windowActive = true
        tab.openFileInFront(sample())
        tab.bringToFront()

        assertEquals(listOf("Heads-up: CI moved [expanded]"), shown())
        assertEquals("1 open · 0 your move · 0 agent's move", tab.counts())
        assertEquals("Margin", tab.title())
    }

    fun testNewMarkersStayForTheLookThatFoundThemAndGoWithTheNextOne() {
        tab.windowActive = false
        agent.ask("Heads-up: CI moved", "intent" to "fyi")
        tab.windowActive = true
        tab.openFileInFront(sample())
        tab.bringToFront()
        assertTrue(user.shownText(tab.panel("Heads-up")).lines().contains("new"))

        tab.openFileInFront(SAMPLE)
        tab.bringToFront()

        assertFalse(user.shownText(tab.panel("Heads-up")).lines().contains("new"))
    }

    fun testAFoldedMessageIsNotReadByBeingOnScreen() {
        tab.windowActive = false
        val id = agent.ask("Heads-up: CI moved")
        agent.reply(id, "Details follow.")
        user.fold(tab.panel("Heads-up"), "Details follow.")

        tab.windowActive = true
        tab.openFileInFront(sample())
        tab.bringToFront()

        assertEquals(listOf(true, false), user.thread(id).messages.map { it.readByUser })
    }

    fun testOpeningAFoldedMessageReadsItOnceItIsOnScreen() {
        tab.windowActive = false
        val id = agent.ask("Heads-up: CI moved")
        agent.reply(id, "Details follow.")
        user.fold(tab.panel("Heads-up"), "Details follow.")
        tab.windowActive = true

        user.expand(tab.panel("Heads-up"), "Details follow.")

        assertEquals(listOf(true, true), user.thread(id).messages.map { it.readByUser })
    }

    fun testWhileTheTabIsBehindAFileNothingInItIsRead() {
        tab.openFileInFront(sample())

        agent.ask("Heads-up: CI moved", "intent" to "fyi")

        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())
        assertEquals("✉ 1", tab.title())
    }

    fun testWhenOnlyClickingCountsAnOpenThreadStaysUnreadUntilYouClickIntoIt() {
        user.changeProjectTabSettings { whatCountsAsRead("Only when you click into the thread") }

        agent.ask("Heads-up: CI moved", "intent" to "fyi")
        assertEquals(listOf("✉ Heads-up: CI moved ● [expanded]"), shown())

        tab.click("Heads-up")
        tab.click("Heads-up")

        assertEquals(listOf("Heads-up: CI moved [expanded]"), shown())
        assertEquals("Margin", tab.title())
    }

    fun testFoldingAThreadEndsItsNewMarkers() {
        tab.windowActive = false
        agent.ask("Heads-up: CI moved", "intent" to "fyi")
        tab.windowActive = true
        tab.openFileInFront(sample())
        tab.bringToFront()

        tab.click("Heads-up")
        tab.click("Heads-up")

        assertFalse(user.shownText(tab.panel("Heads-up")).lines().contains("new"))
    }

    fun testAFoldedThreadKeepsItsUnreadDotUntilOpened() {
        tab.windowActive = false
        val alpha = agent.ask("Which release name?")
        tab.click("Which release name?")
        agent.reply(alpha, "Any thoughts?")
        assertEquals(listOf("✉ Which release name? ● — Claude: Any thoughts?"), shown())
        tab.windowActive = true

        tab.click("Which release name?")

        assertEquals(listOf("✉ Which release name? [expanded]"), shown())
        assertEquals("1 open · 1 your move · 0 agent's move", tab.counts())
    }

    fun testComingBackToTheIdeReadsWhatTheTabShows() {
        tab.switchToAnotherApp()
        agent.ask("Heads-up: CI moved", "intent" to "fyi")
        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())

        tab.switchToTheIde()

        assertEquals("1 open · 0 your move · 0 agent's move", tab.counts())
        assertEquals("Margin", tab.title())
    }

    fun testLeavingTheIdeEndsTheLookSoNewMarkersGo() {
        tab.switchToAnotherApp()
        agent.ask("Heads-up: CI moved", "intent" to "fyi")
        tab.switchToTheIde()
        assertTrue(user.shownText(tab.panel("Heads-up")).lines().contains("new"))

        tab.switchToAnotherApp()
        tab.switchToTheIde()

        assertFalse(user.shownText(tab.panel("Heads-up")).lines().contains("new"))
    }

    fun testOpenThreadsFollowTheWidthOfTheTab() {
        agent.ask("Which release name?")
        val wide = tab.panel("Which release name?").preferredSize.width

        tab.resize(700, 900)

        PlatformTestUtil.waitWithEventsDispatching("the thread kept its width", { tab.panel("Which release name?").preferredSize.width < wide }, 5)
    }

    fun testAConversationsNewCountGoesOnceEachCommentIsRead() {
        tab.switchToAnotherApp()
        agent.add("body" to "Looks good", "relayed" to Relays.conversationComment(1))
        agent.add("body" to "One nit", "relayed" to Relays.conversationComment(2))
        tab.switchToTheIde()
        tab.clickConversation("PR #7")

        tab.click("Looks good")
        tab.click("One nit")

        assertEquals("From GitHub · PR #7  ▾ 2 comments", shown().first())
    }

    fun testAReplyWhileTheTabIsBehindAFileWaitsUnreadAndShowsAsNewWhenYouComeBack() {
        val id = agent.ask("Which release name?")
        tab.click("Which release name?")
        tab.click("Which release name?")
        tab.openFileInFront(sample())

        agent.reply(id, "Any thoughts?")
        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())
        tab.bringToFront()

        val shown = user.shownText(tab.panel("Which release name?")).lines()
        assertTrue(shown.toString(), shown.contains("Any thoughts?"))
        assertTrue(shown.toString(), shown.contains("new"))
        assertEquals("1 open · 1 your move · 0 agent's move", tab.counts())
    }

    fun testWhenOnlyClickingCountsAReplyShownOnComingBackStaysUnread() {
        val id = agent.ask("Which release name?")
        tab.click("Which release name?")
        tab.click("Which release name?")
        user.changeProjectTabSettings { whatCountsAsRead("Only when you click into the thread") }
        tab.openFileInFront(sample())

        agent.reply(id, "Any thoughts?")
        tab.bringToFront()

        assertTrue(user.shownText(tab.panel("Which release name?")).lines().contains("Any thoughts?"))
        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())
    }

    fun testAReplyShownWhenTheTabComesBackInAnInactiveWindowStaysUnread() {
        val id = agent.ask("Which release name?")
        tab.click("Which release name?")
        tab.click("Which release name?")
        tab.openFileInFront(sample())
        agent.reply(id, "Any thoughts?")
        tab.switchToAnotherApp()

        tab.bringToFront()

        assertTrue(user.shownText(tab.panel("Which release name?")).lines().contains("Any thoughts?"))
        assertEquals("1 open · 1 your move · 0 agent's move · 1 new", tab.counts())
    }

    fun testAThreadOpenInAFileShowsAReplyThatArrivedWhileTheFileWasBehindTheTab() {
        sample()
        val id = agent.addOnLine(5)
        tab.openFileInFront(SAMPLE)
        val panel = user.open(id)
        tab.bringToFront()
        assertFalse(panel.isDisplayable)

        agent.reply(id, "Posted while you were away.")
        tab.openFileInFront(SAMPLE)

        assertTrue(user.shownText(panel).contains("Posted while you were away."))
    }
}
