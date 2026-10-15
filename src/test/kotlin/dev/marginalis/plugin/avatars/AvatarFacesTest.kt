package dev.marginalis.plugin.avatars

import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.AvatarGeometry
import dev.marginalis.core.FaceKey
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.FakeGitHub
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.ui.AuthorColors
import dev.marginalis.plugin.ui.toolwindow.ToolWindowTestCase
import java.awt.Color
import javax.swing.Icon

class AvatarFacesTest : ToolWindowTestCase() {

    private val geometry get() = AvatarGeometry(JBUIScale.scale(1f).toDouble())
    private val claude get() = AuthorColors.of(FaceKey.Agent("claude-main"))
    private val review get() = AuthorColors.of(FaceKey.Agent("claude-review"))

    override fun setUp() {
        super.setUp()
        FakeGitHub(testRootDisposable)
        MarginalisSettings.getInstance().state.showGithubAvatars = false
        sample()
    }

    override fun tearDown() {
        try {
            MarginalisSettings.getInstance().state.showGithubAvatars = true
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun tint(colour: Color): Color = ColorUtil.mix(UIUtil.getPanelBackground(), colour, 0.2)

    private fun initialsIn(icon: Icon, colour: Color, slot: Int = 0): Boolean {
        val pixels = Pixels.of(icon)
        val left = slot * geometry.stackStep
        val centre = geometry.tileCentre.toInt()
        return pixels.anyIn(colour, left + centre - 2..left + centre + 3, centre - 4..centre + 4, tolerance = 40)
    }

    private fun drawnAsInitials(icon: Icon, colour: Color): Boolean =
        Pixels.of(icon).showsAt(tint(colour), geometry.tileOrigin + 1.5, geometry.tileCentre) && initialsIn(icon, colour)

    private fun badgeShows(icon: Icon, colour: Color): Boolean {
        val reach = geometry.badge / 2 * 0.45
        return Pixels.of(icon).showsAt(colour, geometry.badgeCentre + reach, geometry.badgeCentre + reach)
    }

    private fun sparkShows(icon: Icon): Boolean =
        Pixels.of(icon).showsAt(UIUtil.getPanelBackground(), geometry.badgeCentre, geometry.badgeCentre)

    fun testAnAgentIsDrawnAsItsInitialsInItsColourWithTheSparkBadge() {
        val id = agent.addOnLine(5, "body" to "Hello.")

        val avatar = user.avatars(user.open(id)).single()

        assertEquals(geometry.size, avatar.iconWidth)
        assertEquals(geometry.size, avatar.iconHeight)
        assertTrue(drawnAsInitials(avatar, claude))
        assertTrue(badgeShows(avatar, claude))
        assertTrue(sparkShows(avatar))
    }

    fun testYouAreDrawnAsInitialsInYourBlueWithoutABadge() {
        val id = agent.addOnLine(5)
        user.reply(id, "Mine.")

        val yours = user.avatars(user.open(id)).last()

        assertTrue(drawnAsInitials(yours, AuthorColors.USER))
        assertFalse(badgeShows(yours, AuthorColors.USER))
        assertTrue(Pixels.of(yours).showsAt(tint(AuthorColors.USER), geometry.badgeCentre + 1, geometry.badgeCentre + 1))
    }

    fun testAPersonFromGitHubIsDrawnAsInitialsInTheirOwnColourWithoutABadge() {
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From GitHub.", "relayed" to Relays.reviewComment(101))

        val mona = user.avatars(user.open(id)).first()

        val colour = AuthorColors.of(FaceKey.GitHub("mona"))
        assertTrue(drawnAsInitials(mona, colour))
        assertFalse(badgeShows(mona, colour))
    }

    fun testATreeRowStacksTheFirstThreeParticipantsAndCountsTheRest() {
        val id = agent.addOnLine(5, "body" to "Start.")
        user.reply(id, "Mine.")
        reviewer.reply(id, "Review.")
        agent.reply(id, "Relayed.", "relayed" to Relays.reviewComment(102))

        val stack = window.participantsIcon("Start.")!!

        assertEquals("+1", window.participantsShown("Start."))
        assertEquals(geometry.stackWidth(3), stack.iconWidth)
        assertEquals(geometry.size, stack.iconHeight)
        assertTrue(initialsIn(stack, claude, slot = 0))
        assertTrue(initialsIn(stack, AuthorColors.USER, slot = 1))
        assertTrue(initialsIn(stack, review, slot = 2))
    }

    fun testAThreadOnlyTheAgentSpokeInShowsOneFace() {
        agent.addOnLine(5, "body" to "Alone.")

        val stack = window.participantsIcon("Alone.")!!

        assertEquals("", window.participantsShown("Alone."))
        assertEquals(geometry.size, stack.iconWidth)
        assertTrue(initialsIn(stack, claude))
    }
}
