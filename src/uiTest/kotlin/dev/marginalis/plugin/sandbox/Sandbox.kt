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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
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
 *   `MARGINALIS_ROBOT_PORT` fixes it, so scenarios running at the same time each drive a sandbox of their own;
 * - `xvfb`: `sandbox/run-ide-xvfb.sh` on this machine (Linux with Xvfb and JDK 25), on `MARGINALIS_ROBOT_PORT`;
 * - `attach`: an IDE already answering on `MARGINALIS_ROBOT_PORT`; it is neither started nor stopped, so a fresh
 *   project and restarts are not available.
 *
 * A scenario [open]s its own sandbox on the thread that runs it; threads it starts inherit that sandbox, and every
 * call here goes to the calling thread's sandbox.
 */
object Sandbox {

    private enum class Launch { DOCKER, XVFB, ATTACH }

    private const val GRADLE_SEED = "marginalis-sandbox-build-gradle"
    private const val CONTAINER_ROBOT_PORT = 8082
    private const val CONTAINER_REST_PORT = 63342

    // Below 8100 the Python harness runs its gates; the claim stops at the first port docker can create and start.
    private val claimablePorts = 8100..8199

    private val launch = Launch.valueOf((System.getenv("MARGINALIS_SANDBOX_LAUNCH") ?: "docker").uppercase())
    private val fixedRobotPort: Int? = System.getenv("MARGINALIS_ROBOT_PORT")?.toInt()

    val featureDir: Path = Path.of(System.getProperty("marginalis.sandbox.featureDir") ?: ".").toAbsolutePath().normalize()
    val harness: Path = featureDir.resolve("sandbox")
    val runtime: Path = Path.of(System.getenv("MARGINALIS_SANDBOX_RUNTIME") ?: featureDir.parent.resolve("marginalis-sandbox-runtime").toString())
        .toAbsolutePath()
    private val prebuilt: Path? = System.getProperty("marginalis.sandbox.prebuilt")?.let(Path::of)?.takeIf { it.resolve("plugins").exists() }

    // `sandboxImage` tags the image by its Dockerfile and entrypoint (build.gradle.kts).
    private val image: String = System.getProperty("marginalis.sandbox.image") ?: "marginalis-sandbox"
    private val startTimeout = Duration.ofSeconds(900)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()

    private val current = InheritableThreadLocal<Instance?>()
    private val liveContainers: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // A run stopped halfway leaves no container behind.
        Runtime.getRuntime().addShutdownHook(Thread { liveContainers.forEach { docker("rm", "-f", it) } })
    }

    /** Gives the calling thread a sandbox of its own, kept in `<runtime>/<name>`; it starts with [start]. */
    fun open(name: String) {
        check(current.get() == null) { "this thread already has a sandbox" }
        current.set(Instance(runtime.resolve(name)))
    }

    /** Stops the calling thread's sandbox and lets go of it. */
    fun close() {
        current.get()?.stop(release = true)
        current.remove()
    }

    private val sandbox: Instance get() = current.get() ?: error("no sandbox on this thread; a scenario opens one")

    val robotPort: Int get() = sandbox.robotPort
    val robot: Robot get() = sandbox.robot
    val project: Path get() = sandbox.project

    /** The built-in server port of the IDE that has the sandbox project open. */
    val marginalisPort: Int get() = sandbox.marginalisPort

    fun robotUp(): Boolean = sandbox.robotUp()

    /** Starts the sandbox; `prepare` runs once the project is in place, before the IDE opens it. */
    fun start(fresh: Boolean, prepare: () -> Unit = {}) = sandbox.start(fresh, prepare)

    /** Stops the sandbox and, in Docker, removes its container and with it the claim on its port. */
    fun stop() = sandbox.stop(release = true)

    /** Records what the calling thread's sandbox is doing: a thread dump in its IDE log and a shot of its frame. */
    fun recordDiagnostics(what: String): String = current.get()?.recordDiagnostics(what) ?: "no sandbox on this thread to diagnose"

    /** Restarts the sandbox; a Docker sandbox keeps its container, so its port, IDE config and caches stay. */
    fun restart(fresh: Boolean, prepare: () -> Unit = {}) {
        sandbox.stop(release = false)
        sandbox.start(fresh, prepare)
    }

    private class IdeExited(message: String) : IllegalStateException(message)

    private class Instance(private val dir: Path) {

        var robotPort: Int = fixedRobotPort ?: claimablePorts.first
            private set
        var robot = Robot(robotPort)
            private set
        val project: Path = dir.resolve("project")
        private val logFile = dir.resolve("run.log")

        /** The claimed container: created by the first start, kept over restarts, removed by a releasing stop. */
        private var container: String? = null
        private var ide: Process? = null
        private var cachedPort: Int? = null

        private val projectInIde: String get() = if (launch == Launch.DOCKER) "/work/runtime/project" else project.toString().replace('\\', '/')
        private val idePorts: List<Int> get() = if (launch == Launch.DOCKER) listOf(robotPort + 10000) else (63342..63361).toList()

        val marginalisPort: Int
            get() = cachedPort
                ?: awaitValue("the Marginalis API answers for the sandbox project", Duration.ofSeconds(30)) { findMarginalisPort() }
                    .also { cachedPort = it }

        fun robotUp(): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", robotPort), 2_000) } }.isSuccess

        fun start(fresh: Boolean, prepare: () -> Unit) {
            cachedPort = null
            if (launch == Launch.ATTACH) return awaitUp(process = null)
            Files.createDirectories(dir)
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

        fun stop(release: Boolean) {
            val started = when (launch) {
                Launch.DOCKER -> container != null
                Launch.XVFB -> ide != null
                Launch.ATTACH -> false
            }
            if (!started) return
            if (robotUp()) {
                // Settings are saved explicitly first: the robot server goes down early in the shutdown, and a kill
                // after that loses whatever the exit had not flushed yet (workspace.xml, app options).
                // A failed or hung save must not keep the kill below from running: no sandbox may outlive its scenario.
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
                    // The shutdown often hangs after the robot server is gone (a leak it reports); everything is saved
                    // by now, so a short grace period and a kill are safe.
                    if (release) {
                        docker("rm", "-f", name)
                        liveContainers.remove(name)
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
            // `sandboxPrebuilt` built the image already.
            if (prebuilt == null) docker("build", "-q", "-t", image, harness.toString(), check = true)
            val name = container?.also { docker("start", it, check = true) } ?: claimContainer()
            return ProcessBuilder("docker", "logs", "-f", name).redirectErrorStream(true).redirectOutput(logFile.toFile()).start()
        }

        // Creating the container is the claim: `docker create` refuses a name that exists, and `docker start` a port
        // that something else holds.
        private fun claimContainer(): String {
            for (port in fixedRobotPort?.let { listOf(it) } ?: claimablePorts.toList()) {
                val name = "marginalis-sandbox-$port"
                if (fixedRobotPort != null) docker("rm", "-f", name)
                if (docker(*createArgs(name, port)) != 0) continue
                liveContainers += name
                if (docker("start", name) != 0) {
                    docker("rm", "-f", name)
                    liveContainers -= name
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
                    // starts. A cache shared with another running sandbox stalls the build: Gradle hands its cache
                    // locks over loopback, which each container has to itself. A new sandbox's cache starts as a copy
                    // of the build container's, which holds the downloaded IDE (docker-entrypoint.sh).
                    "--mount", "type=volume,source=$name-work,target=/work",
                    "--mount", "type=volume,source=$name-gradle,target=/root/.gradle",
                    "--mount", "type=volume,source=$GRADLE_SEED,target=/gradle-seed,readonly",
                    image,
                )
                ).toTypedArray()
        }

        private fun startOnXvfb(): Process =
            ProcessBuilder("bash", harness.resolve("run-ide-xvfb.sh").toString())
                .directory(featureDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .apply {
                    environment()["MARGINALIS_SANDBOX_RUNTIME"] = dir.toString()
                    environment()["MARGINALIS_ROBOT_PORT"] = robotPort.toString()
                }
                .start()

        private fun awaitUp(process: Process?) {
            val port = awaitValue(
                "the sandbox IDE answers on robot port $robotPort", startTimeout, Duration.ofSeconds(1), retryOn = { it !is IdeExited },
            ) {
                if (process != null && !process.isAlive) throw IdeExited("the sandbox IDE exited before it came up; see $logFile")
                if (robotUp()) findMarginalisPort() else null
            }
            cachedPort = port
            val frame = sizeTheFrame()
            awaitProjectStartup()
            println("sandbox up: robot http://127.0.0.1:$robotPort, marginalis http://127.0.0.1:$port, frame $frame")
        }

        /** A thread dump in the IDE log and a shot of the frame, for a wait that timed out. */
        fun recordDiagnostics(what: String): String {
            val name = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-" +
                what.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
            val dumped = threadDump()
            val shot = dir.resolve("diagnostics").resolve("$name.png")
            // A short timeout of its own: the wait may have timed out on an IDE whose UI thread is blocked.
            val shotNote = runCatching {
                val quick = Robot(robotPort, Duration.ofSeconds(15))
                quick.screenshot(shot, quick.findOne("//div[@class='IdeFrameImpl']"))
                "frame $shot"
            }.getOrElse { "no frame shot (${it::class.simpleName}: ${it.message})" }
            return "thread dump ${if (dumped) "in $logFile" else "not taken"}; $shotNote".also { println("DIAG  $what: $it") }
        }

        private fun threadDump(): Boolean = when (launch) {
            Launch.DOCKER -> container?.let { docker("exec", it, "pkill", "-QUIT", "-f", "com.intellij.idea.Main") == 0 } ?: false
            Launch.XVFB -> ide?.descendants()?.toList().orEmpty()
                .filter { it.info().commandLine().orElse("").contains("com.intellij.idea.Main") }
                .map { ProcessBuilder("kill", "-QUIT", it.pid().toString()).start().waitFor() == 0 }
                .any { it }
            Launch.ATTACH -> false
        }

        // A frame sized like a real screen: a window manager or a sleeping monitor may open it tiny, and inlaid
        // thread panels then get zero width, so the robot finds none.
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

        // The IDE restores its tool window layout during the project's startup; the Project view it shows then takes
        // the left side from a Marginalis tool window a scenario has just shown. And while the platform's own startup
        // write (the JPS model sync) waits for the lock, a file opened on the EDT blocks the editor providers' reads
        // until their 30 s timeout. Smart mode flickers on right after startup, so it must hold three samples in a row.
        private fun awaitProjectStartup() {
            var settledPolls = 0
            awaitUntil(
                "the project has started up, is out of dumb mode and has no write action pending, 3 s in a row",
                Duration.ofSeconds(180), Duration.ofSeconds(1),
            ) {
                // A read that fails counts as unsettled too.
                val before = settledPolls
                settledPolls = 0
                val settled = robot.callJs(
                    """
                    var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                    String(projects.length > 0
                        && com.intellij.ide.startup.StartupManagerEx.getInstanceEx(projects[0]).postStartupActivityPassed()
                        && !com.intellij.openapi.project.DumbService.isDumb(projects[0])
                        && !com.intellij.openapi.application.ApplicationManager.getApplication().isWriteActionPending())
                    """,
                ) == "true"
                settledPolls = if (settled) before + 1 else 0
                settledPolls >= 3
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
            // With the usual range taken by other IDEs the platform falls back to a random port; the sandbox knows
            // it. A container's port is fixed, so only a local IDE can be asked.
            if (launch == Launch.DOCKER || !robotUp()) return null
            val port = runCatching { robot.callJs("String(org.jetbrains.ide.BuiltInServerManager.getInstance().getPort())").toInt() }.getOrNull()
            return port?.takeIf(::servesSandbox)
        }
    }

    private fun docker(vararg args: String, check: Boolean = false): Int {
        val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(!check || exit == 0) { "docker ${args.first()} exited with $exit: $output" }
        return exit
    }

    private fun getJson(url: String) = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(1)).build()
        JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    }.getOrNull()
}
