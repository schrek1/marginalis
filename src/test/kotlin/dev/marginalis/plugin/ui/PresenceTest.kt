package dev.marginalis.plugin.ui

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.marginalis.core.FaceKey
import dev.marginalis.core.People
import dev.marginalis.core.PresenceGeometry
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.ui.toolwindow.ToolWindowTestCase
import java.awt.Color
import javax.swing.Icon

class PresenceTest : ToolWindowTestCase() {

    private val geometry get() = PresenceGeometry(JBUIScale.scale(1f).toDouble())
    private val claude get() = AuthorColors.of(FaceKey.Agent("claude-main"))
    private val listeningGreen = JBColor(0x2E9E45, 0x57C46B)
    private lateinit var saved: MarginalisSettings.State

    override fun setUp() {
        super.setUp()
        saved = XmlSerializerUtil.createCopy(MarginalisSettings.getInstance().state)
    }

    override fun tearDown() {
        try {
            MarginalisSettings.getInstance().loadState(saved)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun dotCentre(icon: Icon) = Pixels.of(icon).at(geometry.dotCentreX, geometry.dotCentreY)

    private fun hasFilledDot(icon: Icon) = Pixels.close(dotCentre(icon), listeningGreen)

    private fun hasHollowDot(icon: Icon) = Pixels.close(dotCentre(icon), UIUtil.getPanelBackground())

    private fun ringShows(icon: Icon, colour: Color) =
        Pixels.of(icon).showsAt(colour, geometry.dotCentreX + geometry.dotRadius - geometry.arcStroke / 2, geometry.dotCentreY)

    private fun spinnerSides(icon: Icon): Int {
        val pixels = Pixels.of(icon)
        val centre = geometry.arcOrigin + geometry.arcSize / 2
        val radius = geometry.arcSize / 2
        return listOf(centre to centre - radius, centre + radius to centre, centre to centre + radius, centre - radius to centre)
            .count { (x, y) -> pixels.anyIn(claude, around(x, pixels.width), around(y, pixels.height)) }
    }

    private fun around(at: Double, size: Int): IntRange = (at.toInt() - 1).coerceAtLeast(0)..(at.toInt() + 1).coerceAtMost(size - 1)

    private fun working(stay: Boolean): Icon {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Over to you.")
        val waiting = if (stay) agent.startWait("stay" to true) else agent.startWait()
        window.press("Submit round")
        agent.await(waiting)
        return window.presenceIcon("Claude")!!
    }

    fun testAnAgentThatStaysListeningShowsItsAvatarWithAGreenDot() {
        agent.startWait("stay" to true)

        assertEquals(listOf("Claude · listening · just now"), window.presence())
        val icon = window.presenceIcon("Claude")!!
        assertEquals(geometry.size, icon.iconWidth)
        assertEquals(geometry.size, icon.iconHeight)
        assertTrue(hasFilledDot(icon))
        assertEquals(0, spinnerSides(icon))
    }

    fun testAnAgentListeningForOneRoundWearsAHollowGreenRing() {
        agent.startWait()

        assertEquals(listOf("Claude · listening · just now · one-shot"), window.presence())
        val icon = window.presenceIcon("Claude")!!
        assertFalse(hasFilledDot(icon))
        assertTrue(hasHollowDot(icon))
        assertTrue(ringShows(icon, listeningGreen))
        assertEquals(0, spinnerSides(icon))
    }

    fun testAWorkingAgentSpinsInItsColourWithoutTheGreenDot() {
        val icon = working(stay = true)

        assertEquals(listOf("Claude · working · just now"), window.presence())
        assertInstanceOf(icon, AnimatedIcon::class.java)
        assertTrue(spinnerSides(icon) >= 3)
        assertFalse(hasFilledDot(icon))
        assertFalse(hasHollowDot(icon))
    }

    fun testAOneShotAgentAtWorkSpinsAndWearsAHollowRingInItsColour() {
        val icon = working(stay = false)

        assertEquals(listOf("Claude · working · just now · one-shot"), window.presence())
        assertTrue(spinnerSides(icon) >= 3)
        assertTrue(hasHollowDot(icon))
        assertTrue(ringShows(icon, claude))
    }

    // FixMe: Known issue - #35 while an agent works on a round, Live is greyed out as "No agent is listening" although the toolbar shows it working
    fun testWhileTheAgentWorksOnARoundLiveIsGreyedOutAsIfNobodyListened() {
        working(stay = true)
        val other = agent.addOnLine(9, "body" to "Another question")

        val toolbar = user.toolbar(user.open(other))

        assertEquals(listOf("Claude · working · just now"), window.presence())
        val live = toolbar.getValue("No agent is listening — live needs Claude waiting")
        assertTrue(live.isVisible)
        assertFalse(live.isEnabled)
    }

    fun testAnAvatarOnScreenHidesOnceItsAgentHasGone() {
        val waiting = agent.startWait("stay" to true)
        val avatar = window.presenceActions().single()

        window.stop("Claude")
        agent.await(waiting)

        assertFalse(window.presenceShowing(avatar).isVisible)
    }

    fun testEachListeningAgentHasItsOwnAvatarAndStopAllWakesThemAll() {
        val mine = agent.startWait("stay" to true)
        val theirs = reviewer.startWait("stay" to true)
        assertEquals(listOf("Claude · listening · just now", "Claude · review · listening · just now"), window.presence())
        assertEquals(
            listOf("Stop Claude", "Stop Claude · review", null, "Stop all agents"),
            window.group("Stop agents").map { it.templatePresentation.text },
        )

        window.stop("all agents")

        assertEquals("stopped", agent.await(mine)["reason"]!!.asString)
        assertEquals("stopped", reviewer.await(theirs)["reason"]!!.asString)
        assertEquals(emptyList<String>(), window.presence())
        assertFalse(window.isEnabled("Stop agents"))
        assertEquals(emptyList<Any>(), window.group("Stop agents"))
    }

    fun testAnAgentsNicknameNamesItsAvatarAndItsStopAction() {
        MarginalisSettings.getInstance().rows = listOf(People.Row("claude-main", People.Kind.AGENT, "Bard"))

        agent.startWait("stay" to true)

        assertEquals(listOf("Bard · listening · just now"), window.presence())
        assertEquals("Stop Bard", window.group("Stop agents").first().templatePresentation.text)
    }

    fun testSubmitRoundSaysWhyItIsOffAndWhatItSends() {
        assertEquals("No agent is listening", window.presentation("Submit round").description)

        agent.startWait()

        assertEquals("Send every reply you've written this round", window.presentation("Submit round").description)
    }
}
