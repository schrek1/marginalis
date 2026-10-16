package dev.marginalis.plugin.sandbox

import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A sandbox IntelliJ running Marginalis built straight from a checkout, as `sandbox/sandbox.py` starts it: the
 * checkout's `gradlew runIdeForUiTests` with `sandbox/init.gradle`, its own config/system dirs under [runtime], and
 * [project], a copy of `sandbox/project-template`. The window is moved to a virtual desktop as soon as it appears.
 *
 * Shares the runtime layout and `gradle.pid` with the Python harness, so either can stop what the other started.
 */
object Sandbox {

    val featureDir: Path = Path.of(System.getProperty("marginalis.sandbox.featureDir") ?: ".").toAbsolutePath().normalize()
    val harness: Path = featureDir.resolve("sandbox")
    val runtime: Path = Path.of(System.getenv("MARGINALIS_SANDBOX_RUNTIME") ?: featureDir.parent.resolve("marginalis-sandbox-runtime").toString())
    val project: Path = runtime.resolve("project")
    val robotPort: Int = (System.getenv("MARGINALIS_ROBOT_PORT") ?: "8082").toInt()
    val robot = Robot(robotPort)

    private val pidFile = runtime.resolve("gradle.pid")
    private val logFile = runtime.resolve("run.log")
    private val localIde = System.getProperty("marginalis.localIde")
        ?: Path.of(System.getenv("LOCALAPPDATA"), "Programs", "IntelliJ IDEA Ultimate").toString()
    private val idePorts = 63342..63361
    private val desktop: Int? = (System.getenv("MARGINALIS_SANDBOX_DESKTOP") ?: "2").let { if (it.equals("none", true)) null else it.toInt() }
    private val startTimeout = Duration.ofSeconds(600)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()

    private var cachedPort: Int? = null

    /** The built-in server port of the IDE that has the sandbox project open. */
    val marginalisPort: Int
        get() = cachedPort ?: findMarginalisPort()?.also { cachedPort = it } ?: error("sandbox is not running; start it first")

    fun robotUp(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", robotPort), 2_000) } }.isSuccess

    fun start(fresh: Boolean) {
        check(!robotUp()) { "a sandbox already answers on robot port $robotPort; stop it first" }
        Files.createDirectories(runtime)
        if (fresh || !project.exists()) freshProject()
        cachedPort = null
        val gradle = ProcessBuilder(
            featureDir.resolve("gradlew.bat").toString(), "--no-daemon", "--init-script", harness.resolve("init.gradle").toString(),
            "runIdeForUiTests", "-Pmarginalis.localIde=$localIde",
        ).directory(featureDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .apply {
                environment()["JAVA_HOME"] = findJdk21().toString()
                environment()["MARGINALIS_SANDBOX_RUNTIME"] = runtime.toString().replace('\\', '/')
                environment()["MARGINALIS_ROBOT_PORT"] = robotPort.toString()
            }
            .start()
        pidFile.writeText(gradle.pid().toString())
        println("gradle pid ${gradle.pid()}, log $logFile")
        val mover = desktop?.let {
            ProcessBuilder(
                "pwsh", "-NoProfile", "-File", harness.resolve("move_to_desktop.ps1").toString(),
                "-RuntimeMarker", runtime.name, "-Desktop", it.toString(),
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }
        val deadline = System.nanoTime() + startTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(gradle.isAlive) { "gradle exited with ${gradle.exitValue()} before the IDE came up; see $logFile" }
            val port = if (robotUp()) findMarginalisPort() else null
            if (port != null && (mover == null || !mover.isAlive)) {
                cachedPort = port
                val frame = sizeTheFrame()
                awaitProjectStartup()
                println("sandbox up: robot http://127.0.0.1:$robotPort, marginalis http://127.0.0.1:$port, frame $frame${windowNote(mover)}")
                return
            }
            Thread.sleep(1_000)
        }
        error("sandbox not up after ${startTimeout.seconds}s; see $logFile")
    }

    fun stop() {
        val pid = if (pidFile.exists()) pidFile.readText().trim() else null
        if (robotUp()) {
            // Settings are saved explicitly first: the robot server goes down early in the shutdown, and a kill after
            // that loses whatever the exit had not flushed yet (workspace.xml, app options).
            // A failed save must not keep the kill below from running: no sandbox IDE may outlive its scenario.
            runCatching {
                robot.runJs(
                    """
                    var store = com.intellij.configurationStore.StoreUtil;
                    var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                    for (var i = 0; i < projects.length; i++) store.saveSettings(projects[i], true);
                    store.saveSettings(com.intellij.openapi.application.ApplicationManager.getApplication(), true);
                    """,
                )
            }.onFailure { println("saving the sandbox's settings failed: $it") }
            // The IDE closes the connection before answering.
            runCatching { robot.runJs("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false)", edt = true) }
        }
        if (pid != null) {
            // The shutdown often hangs after the robot server is gone (a leak it reports); everything is saved by
            // now, so a short grace period and a kill are safe.
            val gradle = ProcessHandle.of(pid.toLong())
            for (second in 1..20) {
                if (!gradle.map { it.isAlive }.orElse(false)) break
                Thread.sleep(1_000)
            }
            ProcessBuilder("taskkill", "/T", "/F", "/PID", pid).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()
            Files.deleteIfExists(pidFile)
        }
        cachedPort = null
        println("sandbox stopped")
    }

    fun restart(fresh: Boolean) {
        stop()
        start(fresh)
    }

    // With the screen asleep or its layout changed, Windows opens new windows tiny (620x392 seen): the editor is then a
    // few pixels wide, inlaid thread panels get zero width, and the robot finds no panel at all.
    private fun sizeTheFrame(): String = robot.callJs(
        """
        var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(
            com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]);
        frame.setExtendedState(java.awt.Frame.NORMAL);
        frame.setBounds(0, 0, 2560, 1440);
        frame.validate();
        String(frame.getWidth() + 'x' + frame.getHeight())
        """,
        edt = true,
    )

    // The IDE restores its tool window layout during the project's startup; the Project view it shows then takes the
    // left side from a Marginalis tool window a scenario has just shown. Smart mode flickers on right after startup.
    private fun awaitProjectStartup() {
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        var settledPolls = 0
        while (settledPolls < 3 && System.nanoTime() < deadline) {
            val settled = runCatching {
                robot.callJs(
                    """
                    var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                    String(projects.length > 0
                        && com.intellij.openapi.startup.StartupManager.getInstance(projects[0]).postStartupActivityPassed()
                        && !com.intellij.openapi.project.DumbService.isDumb(projects[0]))
                    """,
                ) == "true"
            }.getOrDefault(false)
            settledPolls = if (settled) settledPolls + 1 else 0
            Thread.sleep(1_000)
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun freshProject() {
        if (project.exists()) project.deleteRecursively()
        harness.resolve("project-template").copyToRecursively(project, followLinks = false, overwrite = false)
    }

    private fun findMarginalisPort(): Int? {
        val wanted = project.toString().replace('\\', '/').lowercase()
        fun servesSandbox(port: Int): Boolean {
            val ping = getJson("http://127.0.0.1:$port/api/marginalis/ping") ?: return false
            return ping.getAsJsonArray("projects")?.any { it.asJsonObject.get("path")?.asString?.lowercase() == wanted } == true
        }
        idePorts.firstOrNull(::servesSandbox)?.let { return it }
        // With the usual range taken by other IDEs the platform falls back to a random port; the sandbox knows it.
        if (!robotUp()) return null
        val port = runCatching { robot.callJs("String(org.jetbrains.ide.BuiltInServerManager.getInstance().getPort())").toInt() }.getOrNull()
        return port?.takeIf(::servesSandbox)
    }

    private fun getJson(url: String) = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(1)).build()
        JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    }.getOrNull()

    private fun findJdk21(): Path {
        val jdks = Path.of(System.getProperty("user.home"), ".jdks")
        val candidates = jdks.childrenStartingWith("corretto-21") + Path.of("C:\\Program Files\\Java").childrenStartingWith("jdk-21")
        return candidates.firstOrNull { it.resolve("bin").resolve("java.exe").exists() }
            ?: error("no JDK 21 found in ~/.jdks or C:\\Program Files\\Java")
    }

    private fun Path.childrenStartingWith(prefix: String): List<Path> =
        if (isDirectory()) listDirectoryEntries("$prefix*").sortedDescending() else emptyList()

    private fun windowNote(mover: Process?): String = when (mover?.exitValue()) {
        null -> ""
        0 -> ", window on virtual desktop ${desktop!! + 1}"
        2 -> "; no virtual desktop ${desktop!! + 1}, the window stays where it opened"
        else -> "; moving the window failed"
    }
}
