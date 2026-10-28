package dev.marginalis.plugin.sandbox

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import okhttp3.OkHttpClient
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The robot-server inside the sandbox IDE, through Remote-Robot's client. Scripts are Rhino JavaScript run in the
 * IDE's JVM; in component-scoped calls `component` is the found Swing component. Only `callJs`/`runJs` and painted
 * screenshots are used: the fixtures' `click()`/`keyboard {}` drive the OS input, which the sandbox must never touch.
 *
 * A script the IDE does not answer within `timeout` fails with what `diagnose` recorded, as a timed-out wait does.
 */
class Robot(port: Int, private val timeout: Duration = Duration.ofSeconds(60), private val diagnose: ((String) -> String)? = null) {

    private val remote = RemoteRobot(
        "http://127.0.0.1:$port",
        OkHttpClient.Builder().readTimeout(timeout.seconds, TimeUnit.SECONDS).callTimeout(timeout.plusSeconds(30)).build(),
    )

    fun callJs(script: String, edt: Boolean = false, component: ComponentFixture? = null): String = answered {
        if (component == null) remote.callJs<String>(script, edt) else component.callJs<String>(script, edt)
    }

    fun runJs(script: String, edt: Boolean = false) = answered { remote.runJs(script, edt) }

    private fun <T> answered(call: () -> T): T = try {
        call()
    } catch (timedOut: InterruptedIOException) {
        val diagnostics = diagnose?.invoke("robot call unanswered") ?: throw timedOut
        throw RobotCallTimedOut("the IDE did not answer a robot call within ${timeout.seconds} s ($diagnostics)", timedOut)
    }

    class RobotCallTimedOut(message: String, cause: Throwable) : IllegalStateException(message, cause)

    fun findAll(xpath: String): List<ComponentFixture> = remote.findAll(ComponentFixture::class.java, byXpath(xpath))

    fun findOne(xpath: String): ComponentFixture =
        findAll(xpath).singleOrNull() ?: error("expected one component for $xpath, found ${findAll(xpath).size}")

    /** One component painted off-screen: works while the sandbox sits on another desktop, behind other windows. */
    fun screenshot(target: Path, component: ComponentFixture): Path {
        Files.createDirectories(target.parent)
        ImageIO.write(component.getScreenshot(true), "png", target.toFile())
        return target
    }
}
