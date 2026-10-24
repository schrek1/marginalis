package dev.marginalis.plugin.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.marginalis.core.FoldingPrefs
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.LongThreadFolding
import dev.marginalis.core.People
import dev.marginalis.core.ReadWhen
import dev.marginalis.plugin.testing.MarginalisTestCase

class SavedSettingsTest : MarginalisTestCase() {

    private fun saved(settings: MarginalisSettings): String = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))

    private fun loaded(xml: String): MarginalisSettings = MarginalisSettings().apply {
        loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), MarginalisSettings.State::class.java))
    }

    fun testEverythingOnThePageSurvivesARestart() {
        val before = MarginalisSettings().apply {
            rows = listOf(People.Row("mona", People.Kind.PERSON, "Mo", "mona.png"), People.Row("claude-main", People.Kind.AGENT, "Bard"))
            timeFormat = TimeFormat.TWELVE_HOUR
            listWhileInFront = ListWhileInFront.HOLD_STILL
            readWhen = ReadWhen.CLICKED_INTO
            state.navigationEnabled = false
            state.displayName = "Ada"
            state.githubLogin = "ada-l"
            state.userPicture = "me.png"
            state.showGithubAvatars = false
            state.walkthroughAutoAdvance = false
            state.notifyOnAgentReply = false
            state.expandOnYourMove = false
            state.groupRelayedConversation = false
            longThreadFolding = LongThreadFolding.FOLD_LONG_READ_MESSAGES
            state.keepFirstMessageOpen = true
            state.askForSummaryLine = false
        }

        val after = loaded(saved(before))

        assertEquals(before.rows, after.rows)
        assertEquals(TimeFormat.TWELVE_HOUR, after.timeFormat)
        assertEquals(before.projectTabPrefs, after.projectTabPrefs)
        assertEquals(FoldingPrefs(LongThreadFolding.FOLD_LONG_READ_MESSAGES, keepFirstOpen = true), after.foldingPrefs)
        assertFalse(after.state.askForSummaryLine)
        assertEquals(saved(before), saved(after))
    }

    fun testPeopleAreSavedOnePersonPerLine() {
        val settings = MarginalisSettings().apply {
            rows = listOf(People.Row("mona", People.Kind.PERSON, "Mo", "mona.png"), People.Row("claude-main", People.Kind.AGENT, "Bard"))
        }

        val xml = saved(settings)

        assertTrue(xml, xml.contains("<person identity=\"mona\" kind=\"person\" nickname=\"Mo\" picture=\"mona.png\" />"))
        assertTrue(xml, xml.contains("<person identity=\"claude-main\" kind=\"agent\" nickname=\"Bard\" picture=\"\" />"))
    }

    fun testNicknamesSavedByAnOlderVersionBecomePeople() {
        val settings = loaded(
            """
            <State>
              <option name="githubNicknames"><map><entry key="@mona" value="Mo" /></map></option>
            </State>
            """.trimIndent(),
        )

        assertEquals(listOf(People.Row("mona", People.Kind.PERSON, "Mo")), settings.rows)
        assertEquals(emptyMap<String, String>(), settings.state.githubNicknames)
        assertEquals("Mo", settings.people.nicknameFor("mona"))
    }

    fun testAnOlderNicknameDoesNotOverrideSomeoneAlreadyListed() {
        val settings = loaded(
            """
            <State>
              <option name="githubNicknames"><map><entry key="mona" value="Old" /></map></option>
              <people><person identity="mona" kind="person" nickname="New" picture="" /></people>
            </State>
            """.trimIndent(),
        )

        assertEquals(listOf("New"), settings.rows.map { it.nickname })
    }

    fun testAnUnknownKindIsReadAsAPersonAndAnEmptyRowIsSkipped() {
        val settings = loaded(
            """
            <State>
              <people>
                <person identity="mona" kind="Robot" nickname="Mo" picture="" />
                <person identity="claude-main" kind="AGENT" nickname="Bard" picture="" />
                <person identity="ghost" kind="person" nickname="" picture="" />
              </people>
            </State>
            """.trimIndent(),
        )

        assertEquals(
            listOf(People.Row("mona", People.Kind.PERSON, "Mo"), People.Row("claude-main", People.Kind.AGENT, "Bard")),
            settings.rows,
        )
    }

    fun testUnknownChoicesFallBackToTheDefaults() {
        val settings = loaded(
            """
            <State>
              <option name="timeFormat" value="13" />
              <option name="projectTabList" value="sideways" />
              <option name="projectTabReadWhen" value="never" />
              <option name="longThreadFolding" value="unfolded" />
            </State>
            """.trimIndent(),
        )

        assertEquals(TimeFormat.AUTO, settings.timeFormat)
        assertEquals(ListWhileInFront.LIVE, settings.listWhileInFront)
        assertEquals(ReadWhen.EXPANDED_IN_FRONT, settings.readWhen)
        assertEquals(LongThreadFolding.FOLD_EVERY_READ_MESSAGE, settings.longThreadFolding)
    }

    fun testFreshSettingsAllowNavigationNotifyAndAdvance() {
        val fresh = loaded("<State />")

        assertTrue(fresh.state.navigationEnabled)
        assertTrue(fresh.state.notifyOnAgentReply)
        assertTrue(fresh.state.walkthroughAutoAdvance)
        assertTrue(fresh.state.showGithubAvatars)
        assertEquals(TimeFormat.AUTO, fresh.timeFormat)
        assertEquals(emptyList<People.Row>(), fresh.rows)
    }

    fun testSettingsSavedBeforeLongThreadChoicesFoldEveryReadMessageAndAskForASummaryLine() {
        val older = loaded(
            """
            <State>
              <option name="navigationEnabled" value="false" />
              <option name="timeFormat" value="12" />
            </State>
            """.trimIndent(),
        )

        assertEquals(FoldingPrefs(LongThreadFolding.FOLD_EVERY_READ_MESSAGE, keepFirstOpen = false), older.foldingPrefs)
        assertTrue(older.state.askForSummaryLine)
    }
}
