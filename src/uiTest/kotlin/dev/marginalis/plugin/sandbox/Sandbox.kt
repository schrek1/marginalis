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
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * A sandbox IntelliJ running Marginalis built straight from a checkout, on a virtual 2560x1440 screen, opening
 * [project], a copy of `sandbox/project-template`. `MARGINALIS_SANDBOX_LAUNCH` picks how it starts:
 *
 * - `docker` (default): a container of the image from `sandbox/Dockerfile`, as `sandbox.py` runs it with
 *   `MARGINALIS_SANDBOX_DOCKER=1`. With the plugins from `./gradlew sandboxPrebuilt` (system property
 *   `marginalis.sandbox.prebuilt`) the IDE starts straight from them, otherwise the container builds the checkout.
 *   Each start claims robot port 8100 or the next free one (REST at the robot port + 10000), unless
 *   `MARGINALIS_ROBOT_PORT` fixes it, so the forks of one test run each drive a sandbox of their own;
 * - `xvfb`: `sandbox/run-ide-xvfb.sh` on this machine (Linux with Xvfb and JDK 25), on `MARGINALIS_ROBOT_PORT`;
 * - `attach`: an IDE already answering on `MARGINALIS_ROBOT_PORT`; it is neither started nor stopped, so a fresh
 *   project and restarts are not available.
 */
object Sandbox {

    private enum class Launch { DOCKER, XVFB, ATTACH }

    private const val IMAGE = "marginalis-sandbox"
    private const val GRADLE_SEED = "marginalis-sandbox-build-gradle"
    private const val CONTAINER_ROBOT_PORT = 8082
    private const val CONTAINER_REST_PORT = 63342

    // Below 8100 the Python harness runs its gates; the claim stops at the first port docker can create and start.
    private val claimablePorts = 8100..8199

    private val launch = Launch.valueOf((System.getenv("MARGINALIS_SANDBOX_LAUNCH") ?: "docker").uppercase())
    private val fixedRobotPort: Int? = System.getenv("MARGINALIS_ROBOT_PORT")?.toInt()

    var robotPort: Int = fixedRobotPort ?: claimablePorts.first
        private set
    var robot = Robot(robotPort)
        private set

    val featureDir: Path = Path.of(System.getProperty("marginalis.sandbox.featureDir") ?: ".").toAbsolutePath().normalize()
    val harness: Path = featureDir.resolve("sandbox")
    val runtime: Path = Path.of(System.getenv("MARGINALIS_SANDBOX_RUNTIME") ?: featureDir.parent.resolve("marginalis-sandbox-runtime").toString())
        .toAbsolutePath()

    // A Gradle test fork drives one sandbox at a time; its project and IDE log are its own.
    private val forkDir: Path = runtime.resolve("fork-" + (System.getProperty("org.gradle.test.worker") ?: "0"))
    val project: Path = forkDir.resolve("project")
    private val logFile = forkDir.resolve("run.log")
    private val prebuilt: Path? = System.getProperty("marginalis.sandbox.prebuilt")?.let(Path::of)?.takeIf { it.resolve("plugins").exists() }

    private val projectInIde: String get() = if (launch == Launch.DOCKER) "/work/runtime/project" else project.toString().replace('\\', '/')
    private val idePorts: List<Int> get() = if (launch == Launch.DOCKER) listOf(robotPort + 10000) else (63342..63361).toList()
    private val startTimeout = Duration.ofSeconds(900)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()

    /** The claimed container: created by the first start of a scenario, kept over its restarts, removed by [stop]. */
    private var container: String? = null
    private var ide: Process? = null
    private var cachedPort: Int? = null

    init {
        // A run stopped halfway leaves no container behind.
        Runtime.getRuntime().addShutdownHook(Thread { container?.let { docker("rm", "-f", it) } })
    }

    /** The built-in server port of the IDE that has the sandbox project open. */
    val marginalisPort: Int
        get() = cachedPort ?: findMarginalisPort()?.also { cachedPort = it } ?: error("sandbox is not running; start it first")

    fun robotUp(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", robotPort), 2_000) } }.isSuccess

    /** Starts the sandbox; `prepare` runs once the project is in place, before the IDE opens it. */
    fun start(fresh: Boolean, prepare: () -> Unit = {}) {
        cachedPort = null
        if (launch == Launch.ATTACH) return awaitUp(process = null)
        Files.createDirectories(forkDir)
        if (fresh || !project.exists()) freshProject()
        prepare()
        val process = when (launch) {
            Launch.DOCKER -> startContainer()
            else -> {
                check(!robotUp()) { "a sandbox already answers on robot port $robotPort; stop it first" }
                startOnXvfb()
            }
        }
        ide = process
        println("sandbox starting ($launch${if (launch == Launch.DOCKER) ", ${if (prebuilt != null) "prebuilt" else "built in the container"}" else ""}), log $logFile")
        awaitUp(process)
    }

    /** Stops the sandbox and, in Docker, removes its container and with it the claim on its port. */
    fun stop() = stop(release = true)

    /** Restarts the sandbox; a Docker sandbox keeps its container, so its port, IDE config and caches stay. */
    fun restart(fresh: Boolean, prepare: () -> Unit = {}) {
        stop(release = false)
        start(fresh, prepare)
    }

    private fun stop(release: Boolean) {
        val started = when (launch) {
            Launch.DOCKER -> container != null
            Launch.XVFB -> ide != null
            Launch.ATTACH -> false
        }
        if (!started) return
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
            Launch.DOCKER -> container?.let { name ->
                // The shutdown often hangs after the robot server is gone (a leak it reports); everything is saved by
                // now, so a short grace period and a kill are safe.
                if (release) {
                    docker("rm", "-f", name)
                    container = null
                } else {
                    docker("stop", "-t", "20", name)
                }
            }
            Launch.XVFB -> ide?.let { process ->
                process.waitFor(20, TimeUnit.SECONDS)
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            Launch.ATTACH -> Unit
        }
        ide?.destroy()
        ide = null
        cachedPort = null
        println("sandbox stopped")
    }

    /** Starts the claimed container again, or claims one; the returned process follows its log. */
    private fun startContainer(): Process {
        docker("build", "-q", "-t", IMAGE, harness.toString(), check = true)
        val name = container?.also { docker("start", it, check = true) } ?: claimContainer()
        return ProcessBuilder("docker", "logs", "-f", name).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
    }

    // Creating the container is the claim: `docker create` refuses a name that exists, and `docker start` a port that
    // something else holds.
    private fun claimContainer(): String {
        for (port in fixedRobotPort?.let { listOf(it) } ?: claimablePorts.toList()) {
            val name = "marginalis-sandbox-$port"
            if (fixedRobotPort != null) docker("rm", "-f", name)
            if (docker(*createArgs(name, port)) != 0) continue
            if (docker("start", name) != 0) {
                docker("rm", "-f", name)
                continue
            }
            container = name
            robotPort = port
            robot = Robot(port)
            return name
        }
        error("no robot port free for a sandbox in ${fixedRobotPort ?: claimablePorts}")
    }

    private fun createArgs(name: String, port: Int): Array<String> {
        val source = prebuilt
            ?.let { listOf("-e", "MARGINALIS_SANDBOX_MODE=prebuilt", "--mount", "type=bind,source=$it,target=/prebuilt,readonly") }
            ?: listOf("--mount", "type=bind,source=$featureDir,target=/src,readonly")
        return (
            listOf(
                "create", "--name", name,
                "-p", "127.0.0.1:$port:$CONTAINER_ROBOT_PORT", "-p", "127.0.0.1:${port + 10000}:$CONTAINER_REST_PORT",
            ) + source + listOf(
                "--mount", "type=bind,source=$project,target=/work/runtime/project",
                // The IDE's config, its build output and the Gradle cache live in the sandbox's own volumes between
                // starts. A cache shared with another running sandbox stalls the build: Gradle hands its cache locks
                // over loopback, which each container has to itself. A new sandbox's cache starts as a copy of the
                // build container's, which holds the downloaded IDE (docker-entrypoint.sh).
                "--mount", "type=volume,source=$name-work,target=/work",
                "--mount", "type=volume,source=$name-gradle,target=/root/.gradle",
                "--mount", "type=volume,source=$GRADLE_SEED,target=/gradle-seed,readonly",
                IMAGE,
            )
            ).toTypedArray()
    }

    private fun startOnXvfb(): Process =
        ProcessBuilder("bash", harness.resolve("run-ide-xvfb.sh").toString())
            .directory(featureDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .apply {
                environment()["MARGINALIS_SANDBOX_RUNTIME"] = forkDir.toString()
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

    private fun docker(vararg args: String, check: Boolean = false): Int {
        val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(!check || exit == 0) { "docker ${args.first()} exited with $exit: $output" }
        return exit
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
