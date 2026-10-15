package dev.marginalis.plugin.avatars

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.AvatarGeometry
import dev.marginalis.core.FaceKey
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.FakeGitHub
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.AuthorColors
import dev.marginalis.plugin.ui.AvatarIcon
import java.awt.Color
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class GitHubAvatarsTest : HttpContractTestCase() {

    private lateinit var github: FakeGitHub
    private val arrivals = AtomicInteger()
    private val login = "octo${System.nanoTime()}"
    private val geometry get() = AvatarGeometry(JBUIScale.scale(1f).toDouble())

    override fun setUp() {
        super.setUp()
        github = FakeGitHub(testRootDisposable)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(AvatarsListener.TOPIC, AvatarsListener { arrivals.incrementAndGet() })
        sample()
    }

    override fun tearDown() {
        try {
            FakeGitHub.forget(login)
            MarginalisSettings.getInstance().state.showGithubAvatars = true
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun relayedAvatar(login: String = this.login, avatarUrl: String? = null): AvatarIcon {
        val relay = Relays.reviewComment(101, login = login, name = "Octo Cat") + listOfNotNull(avatarUrl?.let { "avatar_url" to it })
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From GitHub.", "relayed" to relay)
        return user.avatars(user.open(id)).first()
    }

    private fun showsPicture(avatar: AvatarIcon, colour: Color): Boolean =
        Pixels.of(avatar).showsAt(colour, geometry.tileCentre, geometry.tileCentre) &&
            Pixels.of(avatar).showsAt(colour, geometry.tileOrigin + 2, geometry.tileCentre)

    private fun showsInitials(avatar: AvatarIcon): Boolean {
        val tint = ColorUtil.mix(UIUtil.getPanelBackground(), AuthorColors.of(FaceKey.GitHub(login)), 0.2)
        return Pixels.of(avatar).showsAt(tint, geometry.tileOrigin + 1.5, geometry.tileCentre)
    }

    private fun paintedOnceLoaded(avatar: AvatarIcon, until: () -> Boolean = { true }) {
        Pixels.of(avatar)
        PlatformTestUtil.waitWithEventsDispatching("the avatar never arrived", { arrivals.get() > 0 && until() }, 10)
        Pixels.of(avatar)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun testAPersonWithoutAPictureShowsTheirGitHubAvatarAndItIsCachedOnDisk() {
        github.serves(FakeGitHub.profileUrl(login), Color.RED)
        val avatar = relayedAvatar()

        PlatformTestUtil.waitWithEventsDispatching("GitHub's picture never showed", { showsPicture(avatar, Color.RED) }, 10)

        assertEquals(listOf(FakeGitHub.profileUrl(login)), github.requests)
        assertTrue(Files.isRegularFile(FakeGitHub.cache.resolve("$login.png")))
    }

    fun testTheAvatarOfAGitHubRelayIsAskedForAtItsOwnAddressInTheSizeShown() {
        val relayed = "https://avatars.githubusercontent.com/u/583231?v=4"
        github.serves("$relayed&s=64", Color.GREEN)

        val avatar = relayedAvatar(avatarUrl = relayed)

        PlatformTestUtil.waitWithEventsDispatching("the relayed picture never showed", { showsPicture(avatar, Color.GREEN) }, 10)
        assertEquals(listOf("$relayed&s=64"), github.requests)
    }

    fun testAnAvatarAddressOffGitHubIsIgnoredForTheLoginsProfilePicture() {
        github.serves(FakeGitHub.profileUrl(login), Color.RED)

        val avatar = relayedAvatar(avatarUrl = "https://example.com/tracker.png")

        PlatformTestUtil.waitWithEventsDispatching("the profile picture never showed", { showsPicture(avatar, Color.RED) }, 10)
        assertEquals(listOf(FakeGitHub.profileUrl(login)), github.requests)
    }

    fun testAnAvatarFetchedLessThanAWeekAgoComesFromDiskWithoutAsking() {
        FakeGitHub.cached(login, Color.BLUE, age = Duration.ofDays(6))
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)

        assertTrue(showsPicture(avatar, Color.BLUE))
        assertEquals(emptyList<String>(), github.requests)
    }

    fun testAnAvatarOlderThanAWeekIsFetchedAgain() {
        val file = FakeGitHub.cached(login, Color.BLUE, age = Duration.ofDays(8))
        github.serves(FakeGitHub.profileUrl(login), Color.RED)
        val avatar = relayedAvatar()

        PlatformTestUtil.waitWithEventsDispatching("the fresh picture never showed", { showsPicture(avatar, Color.RED) }, 10)

        assertEquals(1, github.requestsFor(FakeGitHub.profileUrl(login)))
        assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(Duration.ofMinutes(1))))
    }

    fun testWhileGitHubIsUnreachableAWeekOldAvatarKeepsShowing() {
        FakeGitHub.cached(login, Color.BLUE, age = Duration.ofDays(8))
        github.isUnreachable(FakeGitHub.profileUrl(login))
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)

        assertTrue(showsPicture(avatar, Color.BLUE))
        assertEquals(1, github.requestsFor(FakeGitHub.profileUrl(login)))
    }

    fun testAFailedFetchIsNotRetriedAtOnceAndInitialsShowMeanwhile() {
        github.isUnreachable(FakeGitHub.profileUrl(login))
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)
        repeat(3) { Pixels.of(avatar) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(showsInitials(avatar))
        assertEquals(1, github.requestsFor(FakeGitHub.profileUrl(login)))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.missing")))
    }

    fun testALoginGitHubDoesNotKnowShowsInitialsAndIsRememberedAsMissing() {
        github.answers(FakeGitHub.profileUrl(login), 404)
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar) { Files.exists(FakeGitHub.cache.resolve("$login.missing")) }

        assertTrue(showsInitials(avatar))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.png")))
    }

    fun testALoginRememberedAsMissingIsNotAskedForAgainWithinAWeek() {
        FakeGitHub.cachedMissing(login, age = Duration.ofDays(3))
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)

        assertTrue(showsInitials(avatar))
        assertEquals(emptyList<String>(), github.requests)
    }

    fun testAStaleAvatarOfALoginGitHubNoLongerKnowsGivesWayToInitials() {
        FakeGitHub.cached(login, Color.BLUE, age = Duration.ofDays(8))
        github.answers(FakeGitHub.profileUrl(login), 404)
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar) { Files.exists(FakeGitHub.cache.resolve("$login.missing")) }

        assertTrue(showsInitials(avatar))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.png")))
    }

    fun testAServerErrorWithoutAStaleAvatarIsRetriedLaterNotRememberedAsMissing() {
        github.answers(FakeGitHub.profileUrl(login), 503)
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)

        assertTrue(showsInitials(avatar))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.missing")))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.png")))
    }

    fun testAnAnswerThatIsNotAPictureShowsInitialsAndCachesNothing() {
        github.servesBytes(FakeGitHub.profileUrl(login), "<html>rate limited</html>".toByteArray())
        val avatar = relayedAvatar()

        paintedOnceLoaded(avatar)

        assertTrue(showsInitials(avatar))
        assertFalse(Files.exists(FakeGitHub.cache.resolve("$login.png")))
    }

    fun testABotHasNoGitHubAvatarToFetch() {
        val avatar = relayedAvatar(login = "ci[bot]")

        repeat(2) { Pixels.of(avatar) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(emptyList<String>(), github.requests)
    }

    fun testWithGitHubAvatarsSwitchedOffNothingIsFetchedAndInitialsShow() {
        MarginalisSettings.getInstance().state.showGithubAvatars = false
        github.serves(FakeGitHub.profileUrl(login), Color.RED)
        val avatar = relayedAvatar()

        repeat(2) { Pixels.of(avatar) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(showsInitials(avatar))
        assertEquals(emptyList<String>(), github.requests)
    }
}
