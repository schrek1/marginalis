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

/**
 * A sandbox IntelliJ running Marginalis built straight from a checkout, on a virtual 2560x1440 screen: the checkout's
 * `runIdeForUiTests` (from `sandbox/init.gradle`) under `sandbox/run-ide-xvfb.sh`, opening [project], a copy of
 * `sandbox/project-template`. `MARGINALIS_SANDBOX_LAUNCH` picks how it starts:
 *
 * - `docker` (default): the image from `sandbox/Dockerfile`, as `sandbox.py` runs it with `MARGINALIS_SANDBOX_DOCKER=1`
 *   (container `marginalis-sandbox-<robot port>`, the checkout mounted read-only, the project from the host);
 * - `xvfb`: `run-ide-xvfb.sh` on this machine (Linux with Xvfb and JDK 25, e.g. CI);
 * - `attach`: an IDE already answering on the robot port; it is neither started nor stopped, so a fresh project and
 *   restarts are not available.
 */
object Sandbox {

    private enum class Launch { DOCKER, XVFB, ATTACH }

    private val launch = Launch.valueOf((System.getenv("MARGINALIS_SANDBOX_LAUNCH") ?: "docker").uppercase())

    val robotPort: Int = (System.getenv("MARGINALIS_ROBOT_PORT") ?: "8082").toInt()
    val featureDir: Path = Path.of(System.getProperty("marginalis.sandbox.featureDir") ?: ".").toAbsolutePath().normalize()
    val harness: Path = featureDir.resolve("sandbox")
    private val container = "marginalis-sandbox-$robotPort"
    val runtime: Path = Path.of(
        System.getenv("MARGINALIS_SANDBOX_RUNTIME")
            ?: featureDir.parent.resolve(if (launch == Launch.DOCKER) container else "marginalis-sandbox-runtime").toString(),
    ).toAbsolutePath()
    val project: Path = runtime.resolve("project")
    val robot = Robot(robotPort)

    private const val IMAGE = "marginalis-sandbox"
    private const val GRADLE_SEED = "marginalis-sandbox-build-gradle"
    private const val CONTAINER_ROBOT_PORT = 8082
    private const val CONTAINER_REST_PORT = 63342

    // Clear of the host's JetBrains IDEs (63342 and up) and of Windows' dynamic port range.
    private val dockerRestPort = robotPort + 10000
    private val projectInIde: String = if (launch == Launch.DOCKER) "/work/runtime/project" else project.toString().replace('\\', '/')
    private val idePorts = if (launch == Launch.DOCKER) listOf(dockerRestPort) else (63342..63361).toList()
    private val logFile = runtime.resolve("run.log")
    private val startTimeout = Duration.ofSeconds(900)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()

    private var ide: Process? = null
    private var cachedPort: Int? = null

    /** The built-in server port of the IDE that has the sandbox project open. */
    val marginalisPort: Int
        get() = cachedPort ?: findMarginalisPort()?.also { cachedPort = it } ?: error("sandbox is not running; start it first")

    fun robotUp(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", robotPort), 2_000) } }.isSuccess

    fun start(fresh: Boolean) {
        cachedPort = null
        if (launch == Launch.ATTACH) return awaitUp(process = null)
        check(!robotUp()) { "a sandbox already answers on robot port $robotPort; stop it first" }
        Files.createDirectories(runtime)
        if (fresh || !project.exists()) freshProject()
        val process = if (launch == Launch.DOCKER) startContainer() else startOnXvfb()
        ide = process
        println("sandbox starting ($launch), log $logFile")
        awaitUp(process)
    }

    fun stop() {
        if (launch == Launch.ATTACH) return
        if (robotUp()) {
            // Settings are saved explicitly first: the robot server goes down early in the shutdown, and a kill after
            // that loses whatever the exit had not flushed yet (workspace.xml, app options).
            // A failed or hung save must not keep the kill below from running: no sandbox IDE may outlive its scenario.
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
        when (launch) {
            Launch.DOCKER -> docker("rm", "-f", container)
            Launch.XVFB -> ide?.let { process ->
                // The shutdown often hangs after the robot server is gone (a leak it reports); everything is saved by
                // now, so a short grace period and a kill are safe.
                process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            Launch.ATTACH -> Unit
        }
        ide = null
        cachedPort = null
        println("sandbox stopped")
    }

    fun restart(fresh: Boolean) {
        stop()
        start(fresh)
    }

    /** Builds the image (a no-op once cached) and runs the container; the returned process follows its log. */
    private fun startContainer(): Process {
        docker("build", "-q", "-t", IMAGE, harness.toString(), check = true)
        docker("rm", "-f", container)
        docker(
            "run", "-d", "--name", container,
            "-p", "127.0.0.1:$robotPort:$CONTAINER_ROBOT_PORT", "-p", "127.0.0.1:$dockerRestPort:$CONTAINER_REST_PORT",
            "--mount", "type=bind,source=$featureDir,target=/src,readonly",
            "--mount", "type=bind,source=$project,target=/work/runtime/project",
            // Build output, the IDE's config and the Gradle cache live in the sandbox's own volumes between starts. A
            // cache shared with another running sandbox stalls the build: the Gradle that runs that IDE holds the cache's
            // journal lock, and Gradle's lock handoff cannot reach a process in another container. A new sandbox's cache
            // starts as a copy of the shared seed (docker-entrypoint.sh).
            "--mount", "type=volume,source=$container-work,target=/work",
            "--mount", "type=volume,source=$container-gradle,target=/root/.gradle",
            "--mount", "type=volume,source=$GRADLE_SEED,target=/gradle-seed,readonly",
            IMAGE,
            check = true,
        )
        return ProcessBuilder("docker", "logs", "-f", container).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
    }

    private fun startOnXvfb(): Process =
        ProcessBuilder("bash", harness.resolve("run-ide-xvfb.sh").toString())
            .directory(featureDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .apply {
                environment()["MARGINALIS_SANDBOX_RUNTIME"] = runtime.toString()
                environment()["MARGINALIS_ROBOT_PORT"] = robotPort.toString()
            }
            .start()

    private fun awaitUp(process: Process?) {
        val deadline = System.nanoTime() + startTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            check(process == null || process.isAlive) { "the sandbox IDE exited before it came up; see $logFile" }
            val port = if (robotUp()) findMarginalisPort() else null
            if (port != null) {
                cachedPort = port
                val frame = sizeTheFrame()
                awaitProjectStartup()
                println("sandbox up: robot http://127.0.0.1:$robotPort, marginalis http://127.0.0.1:$port, frame $frame")
                return
            }
            Thread.sleep(1_000)
        }
        error("sandbox not up after ${startTimeout.seconds}s; see $logFile")
    }

    private fun docker(vararg args: String, check: Boolean = false) {
        val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(!check || exit == 0) { "docker ${args.first()} exited with $exit: $output" }
    }

    // A frame sized like a real screen: a window manager or a sleeping monitor may open it tiny, and inlaid thread
    // panels then get zero width, so the robot finds none.
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
        val wanted = projectInIde.lowercase()
        fun servesSandbox(port: Int): Boolean {
            val ping = getJson("http://127.0.0.1:$port/api/marginalis/ping") ?: return false
            return ping.getAsJsonArray("projects")?.any { it.asJsonObject.get("path")?.asString?.lowercase() == wanted } == true
        }
        idePorts.firstOrNull(::servesSandbox)?.let { return it }
        // With the usual range taken by other IDEs the platform falls back to a random port; the sandbox knows it. A
        // container's port is fixed, so only a local IDE can be asked.
        if (launch == Launch.DOCKER || !robotUp()) return null
        val port = runCatching { robot.callJs("String(org.jetbrains.ide.BuiltInServerManager.getInstance().getPort())").toInt() }.getOrNull()
        return port?.takeIf(::servesSandbox)
    }

    private fun getJson(url: String) = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(1)).build()
        JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    }.getOrNull()
}
