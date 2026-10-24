package dev.marginalis.plugin.store

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.IconUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.core.StripeBadge
import com.intellij.openapi.util.ScalableIcon
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.LiveBadge
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.testing.SettingsPage
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.MarginalisIcons
import javax.swing.Icon

class ProjectLiveTest : HttpContractTestCase() {

    private lateinit var savedSettings: MarginalisSettings.State

    override fun setUp() {
        super.setUp()
        savedSettings = XmlSerializerUtil.createCopy(MarginalisSettings.getInstance().state)
        sample()
    }

    override fun tearDown() {
        try {
            MarginalisSettings.getInstance().loadState(savedSettings)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun liveByDefaultInSettings(on: Boolean) {
        val page = SettingsPage.open(testRootDisposable)
        page.tick("Live by default", on)
        page.apply()
    }

    private fun awaiting(woken: Agent.Response): List<String> =
        woken.json.getAsJsonArray("awaiting").map { it.asJsonObject.id }

    fun testMakingTheProjectLiveMakesEveryOpenThreadLive() {
        val first = agent.addOnLine(5)
        val second = agent.addOnLine(9)

        user.goProjectLive()

        assertTrue(user.isProjectLive())
        assertTrue(user.isLive(first))
        assertTrue(user.isLive(second))
    }

    fun testASubmitInAThreadOfALiveProjectWakesTheWaitingAgentWithThatThreadAlone() {
        val answered = agent.addOnLine(5)
        agent.addOnLine(9)
        agent.threads()
        user.goProjectLive()
        val waiting = agent.startWait("timeout" to 60)

        user.reply(answered, "Go ahead.")

        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(answered), awaiting(woken))
    }

    fun testAThreadSwitchedOffStaysQuietWhileTheProjectIsLive() {
        val id = agent.addOnLine(5)
        agent.threads()
        user.goProjectLive()
        user.stopLive(id)
        val waiting = agent.startWait("timeout" to 60)

        user.reply(id, "Not now.")

        assertFalse(user.isLive(id))
        assertFalse(waiting.isDone)
        user.submitRound()
        assertEquals("hand_back", agent.await(waiting)["reason"]!!.asString)
    }

    fun testFlippingTheProjectClearsEveryThreadsOwnSwitch() {
        val switchedOff = agent.addOnLine(5)
        val switchedOn = agent.addOnLine(9)
        user.goProjectLive()
        user.stopLive(switchedOff)

        user.stopProjectLive()
        agent.startWait("timeout" to 60)
        user.goLive(switchedOn)
        user.goProjectLive()
        assertTrue(user.isLive(switchedOff))

        user.stopProjectLive()
        assertFalse(user.isLive(switchedOn))
    }

    fun testAThreadSwitchedOffInALiveProjectCannotBeSwitchedBackOnWithNoAgentWaiting() {
        val id = agent.addOnLine(5)
        user.goProjectLive()
        user.stopLive(id)

        assertFalse(user.canGoLive(id))
        assertTrue(user.toolbar(user.open(id)).containsKey("No agent is listening — live needs Claude waiting"))
    }

    fun testAThreadSwitchedOffInALiveProjectCanBeSwitchedBackOnWhileAnAgentWaits() {
        val id = agent.addOnLine(5)
        user.goProjectLive()
        user.stopLive(id)
        agent.startWait("timeout" to 60)

        assertTrue(user.canGoLive(id))
        user.goLive(id)
        assertTrue(user.isLive(id))
    }

    fun testTheProjectToggleIsGreyedOutWhileNoAgentIsListening() {
        val window = user.openToolWindow()

        assertFalse(window.canTurnProjectLive())
        assertEquals("No agent is listening — live needs an agent waiting", window.projectLiveText())
    }

    fun testTheProjectToggleCanGoLiveWhileAnAgentIsListening() {
        val window = user.openToolWindow()

        agent.startWait("timeout" to 60)

        assertTrue(window.canTurnProjectLive())
        assertEquals("Make every open thread live", window.projectLiveText())
    }

    fun testALiveProjectCanBeTurnedOffWithNoAgentListening() {
        val window = user.openToolWindow()
        agent.await(agent.startWait("timeout" to 1))
        liveByDefaultInSettings(true)

        assertTrue(window.canTurnProjectLive())
        window.turnProjectLive(false)
        assertFalse(user.isProjectLive())
    }

    fun testSettingsSayLiveByDefaultNeedsAListeningAgent() {
        val page = SettingsPage.open(testRootDisposable)

        assertTrue(
            page.comments().toString(),
            "Live needs an agent listening. While one is, every open thread is live and each Submit wakes the agent at once, " +
                "without a Submit round. A project follows this until you flip ⚡ in its Marginalis tool window." in page.comments(),
        )
    }

    fun testMakingTheProjectLiveWakesTheAgentForAThreadHoldingMessagesItHasNotSeen() {
        val followedUp = agent.addOnLine(5)
        agent.addOnLine(9)
        agent.threads()
        user.reply(followedUp, "One more thing.")
        val waiting = agent.startWait("timeout" to 60)

        user.goProjectLive()

        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(followedUp), awaiting(woken))
    }

    fun testAThreadReopenedWhileTheProjectIsLiveIsLiveAndWakesTheAgentForWhatItHasNotSeen() {
        val id = agent.addOnLine(5)
        agent.threads()
        user.reply(id, "One more thing.")
        user.resolve(id)
        user.goProjectLive()
        val waiting = agent.startWait("timeout" to 60)

        user.reopen(id)

        assertTrue(user.isLive(id))
        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(id), awaiting(woken))
    }

    fun testACommentMovedUpToTheFileInALiveProjectWakesTheWaitingAgent() {
        assertAMovedUpCommentWakesTheWaitingAgent("Comment on file instead")
    }

    fun testACommentMovedUpToTheProjectInALiveProjectWakesTheWaitingAgent() {
        assertAMovedUpCommentWakesTheWaitingAgent("Comment on project instead")
    }

    private fun assertAMovedUpCommentWakesTheWaitingAgent(option: String) {
        agent.addOnLine(9)
        agent.threads()
        user.goProjectLive()
        val waiting = agent.startWait("timeout" to 60)

        user.comment(SAMPLE, 5)
        user.type(user.panel(), "Bigger than this line.")
        user.sendInstead(user.panel(), option)

        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(store.threads.all().last().id), awaiting(woken))
    }

    fun testAProjectNeverSwitchedFollowsLiveByDefaultInSettingsAndItsLaterChanges() {
        val id = agent.addOnLine(5)

        liveByDefaultInSettings(true)
        assertTrue(user.isProjectLive())
        assertTrue(user.isLive(id))

        liveByDefaultInSettings(false)
        assertFalse(user.isProjectLive())
        assertFalse(user.isLive(id))
    }

    fun testAProjectTheUserSwitchedKeepsItsChoiceWhenSettingsChange() {
        val id = agent.addOnLine(5)
        user.goProjectLive()

        liveByDefaultInSettings(true)
        liveByDefaultInSettings(false)

        assertTrue(user.isProjectLive())
        assertTrue(user.isLive(id))
    }

    fun testSettingsLeaveTheThreadSwitchesOfAProjectTheUserSwitched() {
        val switchedOff = agent.addOnLine(5)
        user.goProjectLive()
        user.stopLive(switchedOff)

        liveByDefaultInSettings(true)

        assertTrue(user.isProjectLive())
        assertFalse(user.isLive(switchedOff))
    }

    fun testLiveByDefaultInSettingsWakesTheAgentForAThreadHoldingMessagesItHasNotSeen() {
        val id = agent.addOnLine(5)
        agent.threads()
        user.reply(id, "One more thing.")
        val waiting = agent.startWait("timeout" to 60)

        liveByDefaultInSettings(true)

        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(id), awaiting(woken))
    }

    fun testASubmitMadeWhileNoAgentWaitsReachesTheNextWait() {
        val id = agent.addOnLine(5)
        agent.threads()
        val cursor = agent.summaryOf(id).string("updated_at")
        user.goProjectLive()

        user.reply(id, "Now, please.")

        val woken = agent.get("comment_wait", "since" to cursor, "timeout" to 1)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(id), awaiting(woken))
    }

    fun testMakingTheProjectLiveWhileTheAgentIsBetweenWaitsReachesItsNextWait() {
        assertGoingLiveBetweenWaitsReachesTheNextWait { user.goProjectLive() }
    }

    fun testLiveByDefaultInSettingsWhileTheAgentIsBetweenWaitsReachesItsNextWait() {
        assertGoingLiveBetweenWaitsReachesTheNextWait { liveByDefaultInSettings(true) }
    }

    private fun assertGoingLiveBetweenWaitsReachesTheNextWait(goLive: () -> Unit) {
        val followedUp = agent.addOnLine(5)
        val answered = agent.addOnLine(9)
        agent.threads()
        user.reply(followedUp, "One more thing.")
        agent.reply(answered, "Done.")
        val cursor = agent.summaryOf(answered).string("updated_at")

        goLive()

        val woken = agent.get("comment_wait", "since" to cursor, "timeout" to 1)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(followedUp), awaiting(woken))
    }

    fun testTurningTheProjectOffWithdrawsASubmitTheAgentHasNotPickedUp() {
        assertTurningOffWithdrawsAnUnpickedSubmit(goLive = { user.goProjectLive() }, stopLive = { user.stopProjectLive() })
    }

    fun testLiveByDefaultTurnedOffInSettingsWithdrawsASubmitTheAgentHasNotPickedUp() {
        assertTurningOffWithdrawsAnUnpickedSubmit(goLive = { liveByDefaultInSettings(true) }, stopLive = { liveByDefaultInSettings(false) })
    }

    private fun assertTurningOffWithdrawsAnUnpickedSubmit(goLive: () -> Unit, stopLive: () -> Unit) {
        val id = agent.addOnLine(5)
        agent.threads()
        val cursor = agent.summaryOf(id).string("updated_at")
        goLive()
        user.reply(id, "Now, please.")

        stopLive()

        assertEquals("timeout", agent.get("comment_wait", "since" to cursor, "timeout" to 1)["reason"]!!.asString)
    }

    fun testASubmitInAThreadNoAgentCanBeToldFromWaitsForTheRound() {
        user.goProjectLive()
        val id = user.commentOnSelection(SAMPLE, 5, "line5", "Anyone?")

        user.reply(id, "Still here.")

        val waiting = agent.startWait("timeout" to 60)
        assertFalse(waiting.isDone)
        user.submitRound()
        val woken = agent.await(waiting)
        assertEquals("hand_back", woken["reason"]!!.asString)
        assertEquals(listOf(id), awaiting(woken))
    }

    fun testAnOpenThreadHearsAtOnceThatTheProjectWentLive() {
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        agent.startWait("timeout" to 60)
        assertFalse(user.shownText(panel).contains("Claude is listening"))

        user.goProjectLive()

        PlatformTestUtil.waitWithEventsDispatching("the panel never showed Claude listening", { user.shownText(panel).contains("Claude is listening") }, 5)
    }

    fun testAnOpenThreadReopenedInALiveProjectShowsTheAgentListening() {
        val id = agent.addOnLine(5)
        user.resolve(id)
        user.goProjectLive()
        agent.startWait("timeout" to 60)

        user.reopen(id)

        val panel = user.panels().single()
        PlatformTestUtil.waitWithEventsDispatching("the reopened panel never showed Claude listening", { user.shownText(panel).contains("Claude is listening") }, 5)
    }

    fun testTheStripeIconCarriesALiveBadgeWhileTheProjectIsLive() {
        val window = user.openToolWindow()
        assertSame(MarginalisIcons.toolWindow(null, projectLive = false), window.window.shownIcon)

        window.turnProjectLive(true)
        assertSame(MarginalisIcons.toolWindow(null, projectLive = true), window.window.shownIcon)

        window.turnProjectLive(false)
        assertSame(MarginalisIcons.toolWindow(null, projectLive = false), window.window.shownIcon)

        liveByDefaultInSettings(true)
        assertSame(MarginalisIcons.toolWindow(null, projectLive = false), window.window.shownIcon)
    }

    fun testTheStripeIconFollowsSettingsForAProjectNeverSwitched() {
        val window = user.openToolWindow()

        liveByDefaultInSettings(true)

        assertSame(MarginalisIcons.toolWindow(null, projectLive = true), window.window.shownIcon)
        assertNotSame(MarginalisIcons.toolWindow(null, projectLive = false), MarginalisIcons.toolWindow(null, projectLive = true))
    }

    fun testALiveProjectShowsTheBadgeBeforeItsToolWindowIsOpened() {
        user.goProjectLive()

        val stripe = user.toolWindowStripe()

        assertSame(MarginalisIcons.toolWindow(null, projectLive = true), stripe.shownIcon)
    }

    fun testTheBadgeFollowsSettingsBeforeTheToolWindowIsOpened() {
        val stripe = user.toolWindowStripe()
        assertSame(MarginalisIcons.toolWindow(null, projectLive = false), stripe.shownIcon)

        liveByDefaultInSettings(true)

        PlatformTestUtil.waitWithEventsDispatching(
            "the stripe never showed the live badge",
            { stripe.shownIcon === MarginalisIcons.toolWindow(null, projectLive = true) },
            5,
        )
    }

    private fun onTheStripe(icon: Icon, scale: Int = 1): Pixels =
        Pixels.onTransparent((icon as ScalableIcon).scale(STRIPE_SIZE / icon.iconWidth * icon.scale), scale)

    private fun stripe(badge: StripeBadge?, projectLive: Boolean): Pixels = onTheStripe(MarginalisIcons.toolWindow(badge, projectLive))

    private fun List<Pair<Int, Int>>.allIn(xs: IntRange, ys: IntRange): Boolean = all { (x, y) -> x in xs && y in ys }

    fun testTheLiveBadgeIsAnAmberBoltInTheBottomRightCorner() {
        val bolt = stripe(null, projectLive = true).painted(AMBER)

        assertFalse(bolt.isEmpty())
        assertTrue(bolt.toString(), bolt.allIn(LOWER_HALF, LOWER_HALF))
    }

    fun testTheLiveBadgeSitsInAGapCutAlongTheBoltsOutline() {
        val brand = onTheStripe(MarginalisIcons.Brand, scale = 4)

        val cleared = onTheStripe(MarginalisIcons.toolWindow(null, projectLive = true), scale = 4).clearedFrom(brand)

        val bolt = LiveBadge.InTheCorner(box = STRIPE_SIZE.toInt(), size = 9)
        val reach = bolt.distancesOf(cleared, scale = 4)
        assertFalse(cleared.isEmpty())
        assertTrue("cut reaches ${reach.max()}", reach.max() <= LiveBadge.GAP + bolt.strokeHalf + 0.1)
        assertTrue("cut reaches only ${reach.max()}", reach.max() >= LiveBadge.GAP + bolt.strokeHalf - 0.4)
    }

    fun testTheLiveBadgeMovesBottomLeftWhileTheAwaitingYouBadgeHoldsTheBottomRight() {
        val bolt = stripe(StripeBadge.AWAITING_YOU, projectLive = true).painted(AMBER)

        assertFalse(bolt.isEmpty())
        assertTrue(bolt.toString(), bolt.allIn(0 until STRIPE_SIZE.toInt() / 2, LOWER_HALF))
    }

    fun testTheAwaitingYouBadgeStaysWholeBesideTheLiveBadge() {
        val awaiting = stripe(StripeBadge.AWAITING_YOU, projectLive = false)
        val envelope = awaiting.changedFrom(onTheStripe(MarginalisIcons.Brand))

        assertFalse(envelope.isEmpty())
        assertTrue(stripe(StripeBadge.AWAITING_YOU, projectLive = true).sameAs(awaiting, envelope))
    }

    fun testTheLiveBadgeKeepsToTheBottomRightBesideTheBlockerDot() {
        assertTrue(stripe(StripeBadge.BLOCKER, projectLive = true).painted(AMBER).allIn(LOWER_HALF, LOWER_HALF))
    }

    fun testTheLiveBadgeLeavesTheBlockerDotWhole() {
        val dotted = LiveBadge.withPlatformDot(MarginalisIcons.Brand)
        val dot = LiveBadge.platformDotArea(STRIPE_SIZE.toInt(), STRIPE_SIZE.toInt(), scale = 4)
        val before = Pixels.onTransparent(IconUtil.scale(dotted, null, STRIPE_SIZE / dotted.iconWidth), scale = 4)
        assertTrue(dot.any { (x, y) -> before.alphaAt(x, y) > 0 && Pixels.close(before.at(x.toDouble(), y.toDouble()), LiveBadge.DOT) })

        val live = onTheStripe(MarginalisIcons.withStripeLiveBadge(dotted), scale = 4)

        assertTrue(live.sameAs(before, dot))
    }

    fun testTheProjectToggleSaysWhatItDoes() {
        val window = user.openToolWindow()
        agent.startWait("timeout" to 60)
        assertEquals("Make every open thread live", window.projectLiveText())

        window.turnProjectLive(true)

        assertEquals("Live: each Submit in any open thread wakes the agent", window.projectLiveText())
    }

    fun testLiveByDefaultInSettingsPutsTheLiveBadgeOnAnUntouchedProjectsGutterMarks() {
        agent.addOnLine(5)
        assertFalse(gutterShowsTheLiveBadge(5))

        liveByDefaultInSettings(true)
        assertTrue(gutterShowsTheLiveBadge(5))

        liveByDefaultInSettings(false)
        assertFalse(gutterShowsTheLiveBadge(5))
    }

    private fun gutterShowsTheLiveBadge(line: Int): Boolean {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return LiveBadge.shownIn(user.gutterIcon(SAMPLE, line)!!.icon)
    }

    private companion object {
        const val STRIPE_SIZE = 20f
        val LOWER_HALF = STRIPE_SIZE.toInt() / 2 until STRIPE_SIZE.toInt()
        val AMBER = LiveBadge.AMBER
    }
}
