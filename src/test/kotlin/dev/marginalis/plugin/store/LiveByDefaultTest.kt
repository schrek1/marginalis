package dev.marginalis.plugin.store

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.AllowReflection
import dev.marginalis.plugin.ui.toolwindow.ToolWindowTestCase

class LiveByDefaultTest : ToolWindowTestCase() {

    private val settings get() = MarginalisSettings.getInstance().state
    private lateinit var settingsBefore: MarginalisSettings.State

    override fun setUp() {
        super.setUp()
        settingsBefore = XmlSerializerUtil.createCopy(settings)
        sample()
    }

    override fun tearDown() {
        try {
            MarginalisSettings.getInstance().loadState(settingsBefore)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testWithoutTheDefaultAReplyDoesNotWakeTheAgent() {
        val thread = agent.addOnLine(5, "body" to "Does a reply wake me?")

        val ended = howTheWaitEndsAfterAReply(thread, "Before live by default")

        assertEquals("hand_back", ended["reason"]!!.asString)
    }

    fun testTheToolbarSwitchesTheProjectsDefaultOn() {
        window.press("Live by default")

        assertTrue(store.liveByDefault)
        assertTrue(LiveDefault.getInstance(project).enabled)
    }

    fun testUnderTheDefaultEveryOpenThreadIsLive() {
        val first = agent.addOnLine(5, "body" to "First thread")
        val second = agent.addOnLine(9, "body" to "Second thread")

        window.press("Live by default")

        assertEquals(listOf(true, true), listOf(first, second).map(user::isLive))
    }

    fun testUnderTheDefaultAReplyInAnyThreadWakesTheAgentWithThatThread() {
        agent.addOnLine(5, "body" to "First thread")
        val second = agent.addOnLine(9, "body" to "Second thread")
        agent.threads()
        window.press("Live by default")
        val waiting = agent.startWait("timeout" to 60)

        user.reply(second, "After live by default")

        val wake = agent.await(waiting)
        assertEquals("live", wake["reason"]!!.asString)
        assertEquals(listOf(second), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testAThreadsOwnSwitchTurnsItOffUnderTheDefault() {
        val thread = agent.addOnLine(5, "body" to "Thread to switch off")
        window.press("Live by default")

        user.stopLive(thread)

        assertFalse(user.isLive(thread))
    }

    fun testAThreadSwitchedOffUnderTheDefaultStaysQuiet() {
        val thread = agent.addOnLine(5, "body" to "Thread to switch off")
        window.press("Live by default")
        user.stopLive(thread)

        val ended = howTheWaitEndsAfterAReply(thread, "Switched off")

        assertEquals("hand_back", ended["reason"]!!.asString)
    }

    fun testFlippingTheDefaultClearsTheThreadsOwnSwitch() {
        val thread = agent.addOnLine(5, "body" to "Thread to switch off")
        window.press("Live by default")
        user.stopLive(thread)

        window.press("Live by default")
        window.press("Live by default")

        assertTrue(user.isLive(thread))
    }

    fun testANewProjectTakesTheSettingsDefault() {
        settings.liveByDefault = true

        val newProject = LiveDefault().apply { noStateLoaded() }

        assertTrue(newProject.enabled)
    }

    fun testChangingSettingsLeavesAnExistingProjectAlone() {
        settings.liveByDefault = true
        val existing = LiveDefault().apply { noStateLoaded() }

        settings.liveByDefault = false

        assertTrue(existing.enabled)
    }

    fun testTheProjectsOwnChoiceIsSavedAndWinsOverSettingsWhenReadBack() {
        settings.liveByDefault = true
        val switchedOff = LiveDefault().apply {
            noStateLoaded()
            enabled = false
        }

        val saved = JDOMUtil.writeElement(XmlSerializer.serialize(switchedOff.state))
        val reopened = LiveDefault().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(saved), LiveDefault.State::class.java))
        }

        assertFalse(reopened.enabled)
    }

    @AllowReflection("the storage name and file are what keeps the choice apart from other settings; only the @State annotation states them")
    fun testTheChoiceIsKeptInTheWorkspaceFileUnderItsOwnName() {
        val storage = LiveDefault::class.java.getAnnotation(State::class.java)

        assertEquals("MarginalisLiveDefault", storage.name)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), storage.storages.map { it.value })
    }

    // A wait a reply did not wake stays open until the round is submitted, so its reason says which of the two ended it.
    private fun howTheWaitEndsAfterAReply(thread: String, body: String): Agent.Response {
        agent.threads()
        val waiting = agent.startWait("timeout" to 60)
        user.reply(thread, body)
        user.submitRound()
        return agent.await(waiting)
    }
}
