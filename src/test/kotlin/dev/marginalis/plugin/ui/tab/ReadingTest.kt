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

        assertEquals(listOf("Which release name? [expanded]"), shown())
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
}
