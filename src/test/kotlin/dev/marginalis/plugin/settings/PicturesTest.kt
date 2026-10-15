package dev.marginalis.plugin.settings

import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.marginalis.core.AvatarGeometry
import dev.marginalis.core.FaceKey
import dev.marginalis.core.People
import dev.marginalis.plugin.testing.FakeGitHub
import dev.marginalis.plugin.testing.Pixels
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.OffCiOnly
import dev.marginalis.plugin.testing.SettingsPage
import dev.marginalis.plugin.testing.SkippedOnCi
import dev.marginalis.plugin.transport.HttpContractTestCase
import dev.marginalis.plugin.ui.AuthorColors
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import org.junit.runner.RunWith
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

@RunWith(SkippedOnCi::class)
class PicturesTest : HttpContractTestCase() {

    private lateinit var saved: MarginalisSettings.State
    private lateinit var picturesBefore: Set<String>
    private lateinit var originals: Path
    private val settings get() = MarginalisSettings.getInstance()
    private val stored: Path = PathManager.getConfigDir().resolve("marginalis/avatars")
    private val geometry get() = AvatarGeometry(JBUIScale.scale(1f).toDouble())

    override fun setUp() {
        super.setUp()
        saved = XmlSerializerUtil.createCopy(settings.state)
        picturesBefore = storedPictures()
        originals = Files.createTempDirectory("marginalis-pictures")
        sample()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    override fun tearDown() {
        try {
            settings.loadState(saved)
            (storedPictures() - picturesBefore).forEach { Files.deleteIfExists(stored.resolve(it)) }
            originals.deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun storedPictures(): Set<String> =
        if (Files.isDirectory(stored)) stored.listDirectoryEntries().map { it.name }.toSet() else emptySet()

    private fun picture(name: String, vararg stripes: Color, width: Int = 120, height: Int = 120): Path {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        val stripe = width / stripes.size
        stripes.forEachIndexed { index, colour ->
            g.color = colour
            g.fillRect(index * stripe, 0, if (index == stripes.lastIndex) width - index * stripe else stripe, height)
        }
        g.dispose()
        return originals.resolve(name).also { ImageIO.write(image, "png", it.toFile()) }
    }

    private fun tint(login: String) = ColorUtil.mix(UIUtil.getPanelBackground(), AuthorColors.of(FaceKey.GitHub(login)), 0.2)

    private fun shows(icon: Icon?, colour: Color): Boolean =
        icon != null && Pixels.of(icon).showsAt(colour, geometry.tileCentre, geometry.tileCentre) &&
            Pixels.of(icon).showsAt(colour, geometry.tileOrigin + 2, geometry.tileCentre)

    private fun waitUntilShown(what: String, icon: () -> Icon?, colour: Color) =
        PlatformTestUtil.waitWithEventsDispatching("$what never showed the picture", { shows(icon(), colour) }, 10)

    private fun relayedAvatarOf(login: String): Icon {
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From GitHub.", "relayed" to Relays.reviewComment(101, login = login))
        return user.avatars(user.open(id)).first()
    }

    private fun pageWithMona(): SettingsPage = SettingsPage.open(testRootDisposable).apply { addPerson("@mona", nickname = "Mo") }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testAPictureChosenForSomeoneShowsInTheTableAndBesideTheirComments() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))

        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        assertTrue(page.isModified)
        page.apply()

        val key = settings.rows.single().picture!!
        assertTrue(Files.isRegularFile(stored.resolve(key)))
        val avatar = relayedAvatarOf("mona")
        waitUntilShown("the comment's byline", { avatar }, Color.RED)
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testAPictureIsCroppedToItsCentreSquareAndStoredNoLargerThanNeeded() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("wide.png", Color.GREEN, Color.RED, Color.BLUE, width = 900, height = 300))

        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()

        val kept = ImageIO.read(stored.resolve(settings.rows.single().picture!!).toFile())
        assertEquals(128, kept.width)
        assertEquals(128, kept.height)
        assertTrue(Pixels.close(Color(kept.getRGB(4, 64)), Color.RED))
        assertTrue(Pixels.close(Color(kept.getRGB(123, 64)), Color.RED))
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testASmallPictureIsKeptAtItsOwnSize() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("small.png", Color.RED, width = 40, height = 60))

        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()

        val kept = ImageIO.read(stored.resolve(settings.rows.single().picture!!).toFile())
        assertEquals(40, kept.width)
        assertEquals(40, kept.height)
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testPressingChoosePictureWhileTypingKeepsWhatWasTyped() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))
        page.startTyping(0, SettingsPage.NICKNAME).text = "Mona L."

        page.select(0)
        page.press("Choose Picture…")
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()

        assertEquals("Mona L.", settings.rows.single().nickname)
        assertNotNull(settings.rows.single().picture)
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testClickingAPictureWhileTypingKeepsWhatWasTyped() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))
        page.startTyping(0, SettingsPage.NICKNAME).text = "Mona L."

        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()

        assertEquals("Mona L.", settings.rows.single().nickname)
    }

    fun testAPictureMissingFromDiskOrOutsideItsFolderShowsInitialsInstead() {
        settings.state.showGithubAvatars = false
        settings.rows = listOf(
            People.Row("mona", People.Kind.PERSON, "Mo", "gone.png"),
            People.Row("hubot", People.Kind.PERSON, "Hu", "../marginalis.xml"),
        )

        val mona = relayedAvatarOf("mona")
        val hubot = user.avatars(user.open(agent.add("file" to SAMPLE, "body" to "Bot.", "relayed" to Relays.reviewComment(102, login = "hubot")))).first()
        repeat(2) { Pixels.of(mona); Pixels.of(hubot) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(Pixels.of(mona).showsAt(tint("mona"), geometry.tileOrigin + 1.5, geometry.tileCentre))
        assertTrue(Pixels.of(hubot).showsAt(tint("hubot"), geometry.tileOrigin + 1.5, geometry.tileCentre))
    }

    fun testTheTableTellsHowToChangeAPicture() {
        val page = pageWithMona()

        assertEquals("Click to choose a picture; right-click to clear it", page.avatarTooltip(0))
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testRightClickingAPictureClearsItAndApplyDeletesTheStoredFile() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))
        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()
        val file = stored.resolve(settings.rows.single().picture!!)

        page.clickAvatar(0, button = MouseEvent.BUTTON3)
        assertFalse(shows(page.avatarOf(0), Color.RED))
        page.apply()

        assertNull(settings.rows.single().picture)
        PlatformTestUtil.waitWithEventsDispatching("the unused picture was never deleted", { !Files.exists(file) }, 10)
        assertEquals(1, page.choosersOpened)
    }

    // FixMe: Known issue - #52 tests fetch real GitHub avatars, skipped on CI
    @OffCiOnly
    fun testChooseAndClearPictureInTheToolbarActOnTheOneSelectedRow() {
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))
        assertTrue(page.isOffered("Choose Picture…"))

        page.selectNone()
        assertFalse(page.isOffered("Choose Picture…"))
        assertFalse(page.isOffered("Clear Picture"))

        page.select(0)
        assertTrue(page.isOffered("Choose Picture…"))
        page.press("Choose Picture…")
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)

        page.press("Clear Picture")
        assertFalse(shows(page.avatarOf(0), Color.RED))
    }

    fun testAFileThatIsNotAPictureIsRefusedWithItsName() {
        val page = pageWithMona()
        val notes = originals.resolve("notes.png").also { Files.writeString(it, "not a picture") }
        page.pictureChooserOffers(notes)

        val errors = page.errorsWhile { shown ->
            page.clickAvatar(0)
            PlatformTestUtil.waitWithEventsDispatching("no refusal", { shown.isNotEmpty() }, 10)
        }

        assertEquals(listOf("notes.png could not be read as a picture."), errors)
        page.apply()
        assertNull(settings.rows.single().picture)
    }

    fun testCancellingTheChooserChangesNothing() {
        val page = pageWithMona()
        page.apply()
        page.pictureChooserOffers(null)

        page.clickAvatar(0)

        assertEquals(1, page.choosersOpened)
        assertFalse(page.isModified)
    }

    fun testOnlyAPlainClickOnThePictureOpensTheChooser() {
        val page = pageWithMona()
        page.pictureChooserOffers(null)

        page.clickCell(0, SettingsPage.NICKNAME)
        page.clickAvatar(0, modifiers = InputEvent.CTRL_DOWN_MASK)
        page.clickAvatar(0, clicks = 2)

        assertEquals(0, page.choosersOpened)
    }

    fun testYourOwnPictureShowsBesideYourCommentsUntilYouClearIt() {
        val id = agent.addOnLine(5)
        val page = SettingsPage.open(testRootDisposable)
        page.pictureChooserOffers(picture("me.png", Color.RED))

        page.clickLink("Choose…")
        waitUntilShown("your preview", { page.yourPicture }, Color.RED)
        page.apply()
        val file = stored.resolve(settings.state.userPicture)
        user.reply(id, "Mine.")
        val avatar = user.avatars(user.open(id)).last()
        waitUntilShown("your byline", { avatar }, Color.RED)

        page.clickLink("Clear")
        assertFalse(shows(page.yourPicture, Color.RED))
        assertTrue(page.isModified)
        page.apply()

        assertEquals("", settings.state.userPicture)
        PlatformTestUtil.waitWithEventsDispatching("your old picture was never deleted", { !Files.exists(file) }, 10)
    }

    fun testYourGitHubAvatarIsPreviewedOnceYouLeaveTheLoginField() {
        val github = FakeGitHub(testRootDisposable)
        val login = "preview${System.nanoTime()}"
        github.serves(FakeGitHub.profileUrl(login), Color.BLUE)
        val page = SettingsPage.open(testRootDisposable)

        try {
            page.type("GitHub login:", login)
            Pixels.of(page.yourPicture)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals(emptyList<String>(), github.requests)

            page.leave("GitHub login:")
            waitUntilShown("your preview", { page.yourPicture }, Color.BLUE)
        } finally {
            FakeGitHub.forget(login)
        }
    }

    fun testAChosenPictureWinsOverTheGitHubAvatar() {
        val github = FakeGitHub(testRootDisposable)
        val page = pageWithMona()
        page.pictureChooserOffers(picture("mona.png", Color.RED))
        page.clickAvatar(0)
        waitUntilShown("the table", { page.avatarOf(0) }, Color.RED)
        page.apply()
        github.requests.clear()

        val avatar = relayedAvatarOf("mona")

        waitUntilShown("the comment's byline", { avatar }, Color.RED)
        assertEquals(emptyList<String>(), github.requests)
    }
}
