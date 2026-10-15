package dev.marginalis.plugin.sandbox

import com.intellij.remoterobot.RemoteRobot
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * The robot-server inside the sandbox IDE, through Remote-Robot's client. Scripts are Rhino JavaScript run in the
 * IDE's JVM; in component-scoped calls `component` is the found Swing component. Only `callJs`/`runJs` and painted
 * screenshots are used: the fixtures' `click()`/`keyboard {}` drive the OS input, which the sandbox must never touch.
 */
class Robot(port: Int) {

    private val remote = RemoteRobot(
        "http://127.0.0.1:$port",
        OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(Duration.ofSeconds(90)).build(),
    )

    fun callJs(script: String, edt: Boolean = false, component: ComponentFixture? = null): String =
        if (component == null) remote.callJs<String>(script, edt) else component.callJs<String>(script, edt)

    fun runJs(script: String, edt: Boolean = false) = remote.runJs(script, edt)

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
