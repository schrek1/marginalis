package dev.marginalis.plugin.settings

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.Mark
import dev.marginalis.core.MarkSubject
import dev.marginalis.core.Intent
import dev.marginalis.core.People
import dev.marginalis.core.ReadWhen
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.SettingsPage
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.ThreadPanel

class SettingsPageTest : HttpContractTestCase() {

    private lateinit var saved: MarginalisSettings.State
    private val settings get() = MarginalisSettings.getInstance()

    override fun setUp() {
        super.setUp()
        saved = XmlSerializerUtil.createCopy(settings.state)
        sample()
    }

    override fun tearDown() {
        try {
            settings.loadState(saved)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun page() = SettingsPage.open(testRootDisposable)

    private fun bylines(panel: ThreadPanel): List<String> =
        user.shownText(panel).lines().filter { it.matches(Regex("^.+ · \\d{1,2}:\\d{2}.*$")) }.map { it.substringBefore(" · ") }

    private fun relayedBy(login: String, body: String = "From GitHub."): String =
        agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to body, "relayed" to Relays.reviewComment(101, login = login))

    private fun notifications(): List<Notification> {
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Marginalis") shown += notification
                }
            },
        )
        return shown
    }

    fun testThePageIsCalledMarginalisAndOpensUnchangedWithTheSavedSettings() {
        settings.state.navigationEnabled = false
        settings.state.displayName = "Ada"
        settings.state.githubLogin = "ada-l"

        val page = page()

        assertEquals("Marginalis", page.title)
        assertFalse(page.isTicked("Allow agent navigation"))
        assertEquals("Ada", page.text("Display name:"))
        assertEquals("ada-l", page.text("GitHub login:"))
        assertTrue(page.isTicked("Show GitHub avatars when no picture is set"))
        assertTrue(page.isTicked("Jump to the next step after resolving"))
        assertTrue(page.isTicked("Notify when the agent posts elsewhere"))
        assertEquals("Auto (system)", page.chosen(TimeFormat.AUTO))
        assertFalse(page.isModified)
    }

    fun testSwitchingAgentNavigationOffRefusesTheAgentsRequestsAndOnAgainAllowsThem() {
        val page = page()

        page.tick("Allow agent navigation", false)
        assertTrue(page.isModified)
        page.apply()
        assertFalse(page.isModified)

        assertRefused(agent.post("navigate", "file" to SAMPLE, "line" to 3), 403, "disabled")

        page.tick("Allow agent navigation", true)
        page.apply()

        val navigated = agent.post("navigate", "file" to SAMPLE, "line" to 3)
        assertEquals(navigated.body, 200, navigated.status)
        assertEquals(3, user.editor().caretModel.logicalPosition.line + 1)
    }

    fun testYourDisplayNameSignsYourRepliesForTheAgentToo() {
        val id = agent.addOnLine(5, "body" to "Which name?")
        val page = page()

        page.type("Display name:", "Ada Lovelace")
        page.apply()
        user.reply(id, "This one.")

        assertEquals(listOf("Claude", "Ada Lovelace"), bylines(user.open(id)))
        assertEquals("Ada Lovelace", agent.threads().single().messages().last().getAsJsonObject("author").string("name"))
    }

    fun testABlankDisplayNameSignsWithYourComputerAccountName() {
        val id = agent.addOnLine(5)
        val page = page()

        page.type("Display name:", "   ")
        page.apply()
        user.reply(id, "Mine.")

        val account = System.getProperty("user.name").replaceFirstChar { it.uppercaseChar() }
        assertEquals(account, bylines(user.open(id)).last())
    }

    fun testRenamingYourselfSignsNewRepliesWhileEarlierOnesKeepTheirName() {
        val id = agent.addOnLine(5)
        user.reply(id, "Before.")
        val before = bylines(user.open(id)).last()
        val page = page()

        page.type("Display name:", "Grace")
        page.apply()
        user.reply(id, "After.")

        assertEquals(listOf("Claude", before, "Grace"), bylines(user.open(id)))
        assertEquals(listOf(before, "Grace"), agent.threads().single().messages().drop(1).map { it.getAsJsonObject("author").string("name") })
    }

    fun testYourGitHubLoginMakesYourRelayedCommentsYours() {
        val page = page()
        page.type("Display name:", "Mona")
        page.type("GitHub login:", "@monalisa")
        page.apply()

        val shown = user.shownText(user.open(relayedBy("monalisa")))

        assertTrue(shown, shown.lines().contains("on GitHub"))
        assertTrue(shown, shown.lines().any { it.startsWith("Mona · via Claude · ") })
    }

    fun testANicknameForAnAgentNamesItInPanelsAndInTheStopMenu() {
        val id = agent.addOnLine(5)
        val page = page()

        page.addPerson("claude-main", nickname = "Bard", kind = "Agent")
        assertEquals(listOf(listOf("claude-main", "Bard", "Agent")), page.rows())
        page.apply()
        agent.startWait()

        assertEquals(listOf("Bard"), bylines(user.open(id)))
        assertEquals(listOf("Bard"), user.stoppableAgents())
        assertEquals(listOf(listOf("claude-main", "Bard", "Agent")), page.rows())
    }

    fun testANicknameForAGitHubLoginNamesTheirRelayedComments() {
        val page = page()

        page.addPerson("@mona", nickname = "Mo")
        page.apply()

        val shown = user.shownText(user.open(relayedBy("mona")))
        assertTrue(shown, shown.lines().any { it.startsWith("Mo · via Claude · ") })
        assertEquals(listOf(listOf("mona", "Mo", "Person")), page.rows())
    }

    fun testANicknameReachesPanelsAlreadyOpen() {
        val id = agent.addOnLine(5)
        user.open(id)
        val page = page()

        page.addPerson("claude-main", nickname = "Bard", kind = "Agent")
        page.apply()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf("Bard"), bylines(user.panel()))
    }

    fun testListingSomeoneTwiceIsRefusedAndNothingIsSaved() {
        val page = page()
        page.addPerson("mona", nickname = "Mo")
        page.addPerson("@Mona", nickname = "Lisa")

        val refusal = runCatching { page.apply() }.exceptionOrNull()

        assertInstanceOf(refusal, ConfigurationException::class.java)
        assertEquals("'Mona' is listed twice as a person", refusal!!.localizedMessage)
        assertEquals(emptyList<Any>(), settings.rows)
        assertTrue(page.isModified)
    }

    fun testAnAgentListedTwiceIsRefusedAsAnAgent() {
        val page = page()
        page.addPerson("claude-main", nickname = "Claude", kind = "Agent")
        page.addPerson("claude-main", nickname = "Bard", kind = "Agent")

        val refusal = runCatching { page.apply() }.exceptionOrNull() as ConfigurationException

        assertEquals("'claude-main' is listed twice as an agent", refusal.localizedMessage)
    }

    fun testTheSameNameMayBeAPersonAndAnAgent() {
        val page = page()
        page.addPerson("mona", nickname = "Mo")
        page.addPerson("mona", nickname = "Mo-bot", kind = "Agent")

        page.apply()

        assertEquals(2, settings.rows.size)
    }

    fun testARowLeftWithoutAnIdentityIsDroppedOnApply() {
        val page = page()
        page.addPerson("", nickname = "Nobody")

        page.apply()

        assertEquals(emptyList<Any>(), settings.rows)
        assertEquals(emptyList<List<String>>(), page.rows())
    }

    fun testRemovingSomeoneForgetsTheirNickname() {
        val id = agent.addOnLine(5)
        settings.rows = listOf(People.Row("claude-main", People.Kind.AGENT, "Bard"))
        val page = page()
        assertEquals(listOf(listOf("claude-main", "Bard", "Agent")), page.rows())

        page.select(0)
        page.removeSelected()
        page.apply()

        assertEquals(emptyList<Any>(), settings.rows)
        assertEquals(listOf("Claude"), bylines(user.open(id)))
    }

    fun testANewRowShowsNoPictureUntilItNamesSomeone() {
        val page = page()

        page.startAdding()
        assertNull(page.avatarOf(0))

        page.typeInCell(0, SettingsPage.IDENTITY, "mona", editing = true)
        assertNotNull(page.avatarOf(0))
    }

    fun testRemovingARowWhileTypingInItDropsTheRow() {
        val page = page()
        page.addPerson("mona", nickname = "Mo")
        page.startTyping(0, SettingsPage.NICKNAME).text = "Half"

        page.select(0)
        page.removeSelected()
        page.apply()

        assertEquals(emptyList<List<String>>(), page.rows())
        assertEquals(emptyList<Any>(), settings.rows)
    }

    fun testAnEmptyPeopleTableExplainsWhatShowsInstead() {
        assertEquals("No one added yet: everyone shows their GitHub picture or initials", page().peopleEmptyText)
    }

    fun testAnEditInProgressCountsAsAChangeAndResetDiscardsIt() {
        val page = page()

        page.startAdding().text = "half-typ"
        assertTrue(page.isModified)

        page.reset()

        assertEquals(emptyList<List<String>>(), page.rows())
        assertFalse(page.isModified)
    }

    fun testApplyFinishesTheCellBeingEdited() {
        val page = page()
        page.addPerson("claude-main", kind = "Agent")

        page.startTyping(0, SettingsPage.NICKNAME).text = "Bard"
        page.apply()

        assertEquals(listOf("Bard"), settings.rows.map { it.nickname })
    }

    // FixMe: Known issue - #63 a person or agent added without a nickname or picture vanishes from the table on Apply, without a word
    fun testSomeoneAddedWithoutNicknameOrPictureVanishesOnApply() {
        val page = page()
        page.addPerson("claude-main", kind = "Agent")
        assertEquals(listOf(listOf("claude-main", "", "Agent")), page.rows())

        page.apply()

        assertEquals(emptyList<Any>(), settings.rows)
        assertEquals(emptyList<List<String>>(), page.rows())
        assertFalse(page.isModified)
    }

    fun testResetBringsBackWhatWasSaved() {
        val page = page()
        page.tick("Allow agent navigation", false)
        page.type("Display name:", "Unsaved")
        page.addPerson("mona")

        page.reset()

        assertTrue(page.isTicked("Allow agent navigation"))
        assertEquals("", page.text("Display name:"))
        assertEquals(emptyList<List<String>>(), page.rows())
        assertFalse(page.isModified)
    }

    fun testTurningNotificationsOffSilencesAgentMessagesInOtherFiles() {
        val shown = notifications()
        val page = page()

        agent.addOnLine(12, "body" to "Look here.")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, shown.size)

        page.tick("Notify when the agent posts elsewhere", false)
        page.apply()
        agent.addOnLine(14, "body" to "And here.")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, shown.size)
    }

    fun testTheAutoAdvanceSwitchDecidesWhetherResolvingAStepOpensTheNext() {
        val first = agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "body" to "Step one")
        val second = agent.addOnLine(15, "order" to 2, "walkthrough" to "tour", "body" to "Step two")
        agent.addOnLine(25, "order" to 3, "walkthrough" to "tour", "body" to "Step three")
        val page = page()

        page.tick("Jump to the next step after resolving", false)
        page.apply()
        user.press(user.open(first), "Resolve")

        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals(5, user.editor().caretModel.logicalPosition.line + 1)

        page.tick("Jump to the next step after resolving", true)
        page.apply()
        user.press(user.open(second), "Resolve")

        assertTrue(user.shownText(user.panel()).contains("Step three"))
        assertEquals(25, user.editor().caretModel.logicalPosition.line + 1)
    }

    fun testTheClockChoiceReachesEveryByline() {
        val id = agent.addOnLine(5)
        val page = page()
        assertEquals(listOf("Auto (system)", "12-hour", "24-hour"), page.options(TimeFormat.AUTO))

        page.choose(TimeFormat.AUTO, "12-hour")
        page.apply()
        val twelve = user.shownText(user.open(id)).lines().single { it.startsWith("Claude · ") }
        assertTrue(twelve, twelve.matches(Regex("Claude · \\d{1,2}:\\d{2}\\s?[AaPp]\\.?[Mm]\\.?")))

        page.choose(TimeFormat.AUTO, "24-hour")
        page.apply()
        val twentyFour = user.shownText(user.open(id)).lines().single { it.startsWith("Claude · ") }
        assertTrue(twentyFour, twentyFour.matches(Regex("Claude · \\d{2}:\\d{2}")))
        assertEquals(TimeFormat.TWENTY_FOUR_HOUR, settings.timeFormat)
    }

    fun testTheProjectTabChoicesOfferTheirPlainWordLabels() {
        val page = page()

        assertEquals(listOf("Live", "Hold still"), page.options(ListWhileInFront.LIVE))
        assertEquals(listOf("Expanded while the tab is in front", "Only when you click into the thread"), page.options(ReadWhen.EXPANDED_IN_FRONT))

        page.choose(ListWhileInFront.LIVE, "Hold still")
        page.choose(ReadWhen.EXPANDED_IN_FRONT, "Only when you click into the thread")
        page.tick("Expand a collapsed thread when it becomes your move", false)
        page.tick("Group a pull request's relayed conversation", false)
        page.apply()

        assertEquals(ListWhileInFront.HOLD_STILL, settings.listWhileInFront)
        assertEquals(ReadWhen.CLICKED_INTO, settings.readWhen)
        assertFalse(settings.state.expandOnYourMove)
        assertFalse(settings.state.groupRelayedConversation)
    }

    fun testTheIntentLegendShowsEachGlyphWithWhatItMeans() {
        val legend = page().legend()

        assertEquals(
            listOf(
                "Finding: something to fix",
                "Guidance: how to write the code around here",
                "Question: an answer is wanted",
                "FYI: praise, context, a heads-up; nothing is owed once you've read it",
                "Ordinary: a comment with no stated intent",
            ),
            legend.map { it.first },
        )
        assertEquals(
            listOf(Intent.FINDING, Intent.GUIDANCE, Intent.QUESTION, Intent.FYI, null).map { MarginalisIcons.mark(Mark(MarkSubject.LINE, it)) },
            legend.map { it.second },
        )
    }
}
