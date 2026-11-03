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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.readText

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

    private const val CONTAINER_ROBOT_PORT = 8082
    private const val CONTAINER_REST_PORT = 63342
    private const val CONTAINER_PROJECT = "/work/runtime/project"
    private const val CONTAINER_IDE_LOGS = "/work/runtime/ide/log_runIdeForUiTests"
    private val logClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS")

    // Below 8100 the manual sandboxes of sandbox.py run (8082 by default); the claim stops at the first port docker can
    // create and start.
    private val claimablePorts = 8100..8199

    private val launch = Launch.valueOf((System.getenv("MARGINALIS_SANDBOX_LAUNCH") ?: "docker").uppercase())
    private val fixedRobotPort: Int? = System.getenv("MARGINALIS_ROBOT_PORT")?.toInt()

    val featureDir: Path = Path.of(System.getProperty("marginalis.sandbox.featureDir") ?: ".").toAbsolutePath().normalize()
    val harness: Path = featureDir.resolve("sandbox")
    val runtime: Path = Path.of(System.getenv("MARGINALIS_SANDBOX_RUNTIME") ?: featureDir.parent.resolve("marginalis-sandbox-runtime").toString())
        .toAbsolutePath()
    private val prebuilt: Path? = System.getProperty("marginalis.sandbox.prebuilt")?.let(Path::of)?.takeIf { it.resolve("plugins").exists() }

    // `sandboxImage` tags the image by what it is built from (build.gradle.kts).
    private val image: String = System.getProperty("marginalis.sandbox.image") ?: "marginalis-sandbox"
    private val startTimeout = Duration.ofSeconds(900)
    // A clean exit takes about 2 s; the margin is for a loaded CI runner, not for a hang.
    private val exitTimeout = Duration.ofSeconds(60)
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

    /** Stops the calling thread's sandbox and lets go of it, also when the stop fails. */
    fun close() {
        try {
            current.get()?.stop(release = true)
        } finally {
            current.remove()
        }
    }

    private val sandbox: Instance get() = current.get() ?: error("no sandbox on this thread; a scenario opens one")

    val robotPort: Int get() = sandbox.robotPort
    val robot: Robot get() = sandbox.robot
    val project: Path get() = sandbox.project

    /**
     * Where the sandbox's stop for good saves the IDE's own log, from the sandbox's first start on, restarts included;
     * null for an attached IDE, whose log the harness does not know.
     */
    val ideLog: Path? get() = sandbox.savedIdeLog.takeIf { launch != Launch.ATTACH }

    /** The `handed_back_at` of the last round that woke the agent in this sandbox; the next `comment_wait` waits after it. */
    var lastRoundAt: String?
        get() = sandbox.lastRoundAt
        set(value) {
            sandbox.lastRoundAt = value
        }

    /** The built-in server port of the IDE that has the sandbox project open. */
    val marginalisPort: Int get() = sandbox.marginalisPort

    fun robotUp(): Boolean = sandbox.robotUp()

    /** Starts the sandbox; `prepare` runs once the project is in place, before the IDE opens it. */
    fun start(fresh: Boolean, prepare: () -> Unit = {}) = sandbox.start(fresh, prepare)

    /** Stops the sandbox and, in Docker, removes its container and with it the claim on its port. */
    fun stop() = sandbox.stop(release = true)

    /**
     * A file of the test project as the IDE has it now, or null when it does not exist. A Docker sandbox's IDE works on
     * the container's own copy, which the host folder catches up with only when the sandbox stops.
     */
    fun projectText(relative: String): String? = sandbox.projectText(relative)

    /** Hands what the harness wrote into [project] to the running IDE's copy; `.idea` stays the IDE's. */
    fun pushProject() = sandbox.pushProject()

    /** The Marginalis folder sandboxes start from (`./gradlew sandboxPrebuilt`); null when the container builds the checkout. */
    val prebuiltPlugin: Path? get() = prebuilt?.resolve("plugins")?.resolve("marginalis")

    /** Puts a file of this machine where the sandbox IDE reads it; returns its path there. */
    fun fileForIde(file: Path): String = sandbox.fileForIde(file)

    /** The sandbox IDE's own log; a Docker sandbox's volume keeps earlier runs' entries above this run's. */
    fun ideLogLines(): List<String> = sandbox.ideLogLines()

    /** Records what the calling thread's sandbox is doing: a thread dump in its IDE log and a shot of its frame. */
    fun recordDiagnostics(what: String): String = current.get()?.recordDiagnostics(what) ?: "no sandbox on this thread to diagnose"

    /** Caps the CPU share of the sandbox's container, to widen a timing window as a busy machine does; null lifts it. */
    fun limitCpus(cpus: Double?) = sandbox.limitCpus(cpus)

    /** Restarts the sandbox; a Docker sandbox keeps its container, so its port, IDE config and caches stay. */
    fun restart(fresh: Boolean, prepare: () -> Unit = {}) {
        val begun = System.nanoTime()
        sandbox.stop(release = false)
        val stopped = System.nanoTime()
        sandbox.start(fresh, prepare)
        println("sandbox restarted in %.1f s (stop %.1f s)".format(Locale.ROOT, (System.nanoTime() - begun) / 1e9, (stopped - begun) / 1e9))
    }

    private class IdeExited(message: String) : IllegalStateException(message)

    private class Instance(private val dir: Path) {

        var robotPort: Int = fixedRobotPort ?: claimablePorts.first
            private set
        var robot = Robot(robotPort, diagnose = ::recordDiagnostics)
            private set
        val project: Path = dir.resolve("project")
        val logFile: Path = dir.resolve("run.log")
        val savedIdeLog: Path = dir.resolve("idea.log").also { Files.deleteIfExists(it) }

        /** The first start's moment by the IDE's clock, in its log's format: the log holds earlier runs' entries above. */
        private var ideLogSince: String? = null

        /** The claimed container: created by the first start, kept over restarts, removed by a releasing stop. */
        private var container: String? = null
        private var ide: Process? = null
        private var cachedPort: Int? = null

        @Volatile
        var lastRoundAt: String? = null

        /** The last stop could not copy the IDE's project back, so the host folder may be incomplete. */
        private var hostProjectStale = false

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
            // The container seeds its project from the host folder: an incomplete copy there would replace the IDE's
            // state and the scenario would check the wrong thing, so it fails here instead.
            check(fresh || !hostProjectStale) {
                "the last stop could not copy the IDE's project back to the host; a restart that keeps the project " +
                    "would start from an incomplete copy (see $logFile)"
            }
            if (fresh || !project.exists()) freshProject()
            hostProjectStale = false
            prepare()
            val process = when (launch) {
                Launch.DOCKER -> startContainer()
                else -> {
                    check(!robotUp()) { "a sandbox already answers on robot port $robotPort; stop it first" }
                    ideLogSince = ideLogSince ?: LocalDateTime.now().format(logClock)
                    startOnXvfb()
                }
            }
            ide = process
            println("sandbox starting ($launch${if (launch == Launch.DOCKER) ", ${if (prebuilt != null) "prebuilt" else "built in the container"}" else ""}), log $logFile")
            awaitUp(process)
        }

        fun limitCpus(cpus: Double?) {
            val name = checkNotNull(container) { "a CPU cap needs a Docker sandbox" }
            // `--cpus 0` keeps the old cap; the machine's whole count lifts it.
            val limit = cpus?.toString() ?: checkNotNull(dockerOutput("info", "--format", "{{.NCPU}}")).trim()
            docker("update", "--cpus", limit, name, check = true)
        }

        fun stop(release: Boolean) {
            val started = when (launch) {
                Launch.DOCKER -> container != null
                Launch.XVFB -> ide != null
                Launch.ATTACH -> false
            }
            if (!started) return
            // An IDE whose robot answers has to exit when asked, whatever the request itself returned.
            val asked = robotUp()
            if (asked) runCatching { requestExit() }.onFailure { println("asking the sandbox IDE to exit failed: $it") }
            val exited = if (asked) ideExited(exitTimeout) else !ideRunning()
            // An IDE that could not be asked has failed already, in what the scenario saw; it is only killed here.
            val hang = if (exited) null else recordExitHang(asked).takeIf { asked }
            var pullFailure: String? = null
            when (launch) {
                Launch.DOCKER -> container?.let { name ->
                    if (!exited) docker("kill", name)
                    // After the exit: closing the project writes it once more (the editor tabs, the last save), and
                    // the next start seeds the container from the host folder.
                    pullFailure = pullProject(name)
                    if (release) {
                        saveIdeLog()
                        docker("rm", "-f", name)
                        liveContainers.remove(name)
                        container = null
                    }
                }
                Launch.XVFB -> ide?.let { process ->
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                    if (release) saveIdeLog()
                }
                Launch.ATTACH -> Unit
            }
            ide?.destroy()
            ide = null
            cachedPort = null
            println("sandbox stopped${if (exited) "" else " (killed)"}")
            val failures = listOfNotNull(hang, pullFailure)
            check(failures.isEmpty()) { failures.joinToString("; ") }
        }

        // From a background call, not as the robot's own EDT script: the robot server's HTTP thread would wait for
        // that script while the exit, on the EDT, waits for the server's shutdown hook, which waits for that thread.
        // The application's invokeLater also gives the exit the write-intent lock a user's Exit has; without it,
        // closing the project fails and no close listener runs.
        private fun requestExit() = robot.runJs(
            """
            var app = com.intellij.openapi.application.ApplicationManager.getApplication();
            app.invokeLater(new java.lang.Runnable({ run: function () { app.exit(true, true, false); } }),
                com.intellij.openapi.application.ModalityState.any());
            """,
        )

        /** Whether the IDE process ended within [timeout]; a Docker IDE is the container's main process. */
        private fun ideExited(timeout: Duration): Boolean = when (launch) {
            Launch.DOCKER -> {
                val wait = ProcessBuilder("docker", "wait", container!!).redirectErrorStream(true).start()
                wait.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS).also { if (!it) wait.destroyForcibly() }
            }
            Launch.XVFB -> ide!!.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            Launch.ATTACH -> true
        }

        private fun ideRunning(): Boolean = when (launch) {
            Launch.DOCKER -> containerRunning()
            Launch.XVFB -> ide?.isAlive == true
            Launch.ATTACH -> false
        }

        /** Records what the still running IDE is doing, before it is killed; returns what happened. */
        private fun recordExitHang(asked: Boolean): String {
            val what = if (asked) "did not exit within ${exitTimeout.seconds} s of being asked" else "could not be asked to exit (robot not answering)"
            val dump = dir.resolve("diagnostics").resolve(LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-ide-exit-threads.txt")
            // jcmd answers once the dump is complete; a SIGQUIT dump would race the kill that follows. No quotes in the
            // script: Java on Windows passes an argument's inner quotes to docker unescaped.
            val threads = if (launch == Launch.DOCKER && containerRunning()) {
                dockerOutput(
                    "exec", container!!, "sh", "-c",
                    "pid=\$(pgrep -f com.intellij.idea.Main | head -n 1) && \$(dirname \$(readlink /proc/\$pid/exe))/jcmd \$pid Thread.print -l",
                )
            } else {
                null
            }
            val dumpNote = if (threads != null) {
                Files.createDirectories(dump.parent)
                Files.writeString(dump, threads)
                "thread dump $dump"
            } else {
                "thread dump ${if (threadDump()) "in $logFile" else "not taken"}"
            }
            return "the sandbox IDE $what; $dumpNote".also { println("DIAG  $it") }
        }

        /** Starts the claimed container again, or claims one; the returned process follows its log. */
        private fun startContainer(): Process {
            // `uiSandboxTest` built the image already (`sandboxImage`).
            if (prebuilt == null) docker("build", "-q", "-t", image, harness.toString(), check = true)
            val name = container?.also { docker("start", it, check = true) } ?: claimContainer()
            // The container's clock and zone are the IDE's; its JVM takes seconds before its first log entry.
            ideLogSince = ideLogSince
                ?: checkNotNull(dockerOutput("exec", name, "date", "+%Y-%m-%d %H:%M:%S,%3N")) { "the clock of $name could not be read" }.trim()
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
                robot = Robot(port, diagnose = ::recordDiagnostics)
                return name
            }
            error("no robot port free for a sandbox in ${fixedRobotPort ?: claimablePorts}")
        }

        private fun createArgs(name: String, port: Int): Array<String> {
            // Run mode builds the checkout with Gradle, whose cache lives in the sandbox's own volume between starts: a
            // cache shared with another running sandbox stalls the build, since Gradle hands its cache locks over
            // loopback, which each container has to itself.
            val source = prebuilt
                ?.let { listOf("-e", "MARGINALIS_SANDBOX_MODE=prebuilt", "--mount", "type=bind,source=$it,target=/prebuilt,readonly") }
                ?: listOf(
                    "--mount", "type=bind,source=$featureDir,target=/src,readonly",
                    "--mount", "type=volume,source=$name-gradle,target=/root/.gradle",
                )
            return (
                listOf(
                    "create", "--name", name,
                    "-p", "127.0.0.1:$port:$CONTAINER_ROBOT_PORT", "-p", "127.0.0.1:${port + 10000}:$CONTAINER_REST_PORT",
                    "-e", "MARGINALIS_SANDBOX_APP_CONFIG_PER_CONTAINER=1",
                ) + source + listOf(
                    // The entrypoint copies it onto the container's own disk, where the IDE gets file events and
                    // no 9p stalls; the stop copies the IDE's state back.
                    "--mount", "type=bind,source=$project,target=/project-host",
                    // The IDE's config, system and log directories live in the sandbox's own volume between starts.
                    "--mount", "type=volume,source=$name-work,target=/work",
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
            val launched = System.nanoTime()
            val port = awaitValue(
                "the sandbox IDE answers on robot port $robotPort", startTimeout, Duration.ofSeconds(1), retryOn = { it !is IdeExited },
            ) {
                if (process != null && !process.isAlive) throw IdeExited("the sandbox IDE exited before it came up; see $logFile")
                if (robotUp()) findMarginalisPort() else null
            }
            cachedPort = port
            val frame = sizeTheFrame()
            awaitProjectStartup()
            println(
                "sandbox up in ${seconds(Duration.ofNanos(System.nanoTime() - launched))}: robot http://127.0.0.1:$robotPort, " +
                    "marginalis http://127.0.0.1:$port, frame $frame",
            )
        }

        private fun containerRunning(): Boolean = container?.let { name ->
            dockerOutput("inspect", "-f", "{{.State.Running}}", name)?.trim() == "true"
        } ?: false

        fun projectText(relative: String): String? {
            if (launch == Launch.DOCKER && containerRunning()) {
                return dockerOutput("exec", container!!, "cat", "$CONTAINER_PROJECT/$relative")
            }
            val file = project.resolve(relative)
            return if (file.exists()) file.readText() else null
        }

        fun pushProject() {
            if (launch == Launch.DOCKER && containerRunning()) {
                docker("exec", container!!, "rsync", "-a", "--exclude", "/.idea", "/project-host/", "$CONTAINER_PROJECT/", check = true)
            }
        }

        /**
         * Replaces the host folder's content with the stopped container's project. `docker cp` writes as the calling
         * user, so a Linux host can delete what the IDE (root in the container) wrote.
         *
         * @return why the copy failed, with what `docker cp` printed, or null once the host folder holds the project
         */
        @OptIn(ExperimentalPathApi::class)
        private fun pullProject(name: String): String? {
            // Into the folder itself, emptied, never replaced: it is the container's bind-mount source, which
            // `docker cp` from the stopped container needs in place. A staging folder renamed into place failed now
            // and then on a Windows host: an on-access scanner opens freshly written files for a few milliseconds,
            // and a folder with an open file inside cannot be renamed.
            Files.createDirectories(project)
            project.forEachDirectoryEntry { it.deleteRecursively() }
            val copy = dockerRun("cp", "$name:$CONTAINER_PROJECT/.", project.toString())
            hostProjectStale = copy.exit != 0
            if (!hostProjectStale) return null
            return "the IDE's project could not be copied back to the host (docker cp exit ${copy.exit}: ${copy.output})"
                .also { println("DIAG  $it") }
        }

        /** `idea.1.log` first: a rotation at 10 MB moves the older entries there. */
        fun ideLogLines(): List<String> =
            (ideLogFile("idea.1.log") ?: emptyList()) + checkNotNull(ideLogFile("idea.log")) { "the sandbox IDE has no idea.log" }

        /** A file of the IDE's log folder, running or stopped, as lines; null when it does not exist. */
        private fun ideLogFile(name: String): List<String>? = when (launch) {
            Launch.DOCKER -> {
                val inContainer = "${checkNotNull(container) { "the sandbox has no container" }}:$CONTAINER_IDE_LOGS/$name"
                val copy = Files.createTempFile(dir, "ide-log-", ".log")
                try {
                    val run = dockerRun("cp", inContainer, copy.toString())
                    when {
                        run.exit == 0 -> copy.readText().lines()
                        "Could not find" in run.output -> null
                        else -> error("docker cp $inContainer exited with ${run.exit}: ${run.output}")
                    }
                } finally {
                    Files.deleteIfExists(copy)
                }
            }
            Launch.XVFB -> dir.resolve("ide/log_runIdeForUiTests/$name").takeIf { it.exists() }?.readText()?.lines()
            Launch.ATTACH -> error("an attached IDE's log is not known")
        }

        /** Keeps the IDE's log since the first start in [savedIdeLog]; a log it cannot save fails the plugin-error check. */
        private fun saveIdeLog() {
            val since = ideLogSince ?: return
            runCatching { Files.write(savedIdeLog, PluginErrors.writtenSince(ideLogLines(), since)) }
                .onFailure { println("DIAG  the IDE's log could not be saved to $savedIdeLog: ${it.message}") }
        }

        fun fileForIde(file: Path): String {
            if (launch != Launch.DOCKER) return file.toString().replace('\\', '/')
            val inContainer = "/tmp/${file.fileName}"
            docker("cp", file.toString(), "${container!!}:$inContainer", check = true)
            return inContainer
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
            return "thread dump ${if (dumped) "in $logFile" else "not taken"}; $shotNote; ${otherWindows()}".also { println("DIAG  $what: $it") }
        }

        // A dialog the IDE opened on its own (a first-start or promotion prompt) blocks the user's steps, and a shot of
        // the frame does not show it. Read off the EDT, which the dialog's modal loop holds.
        private fun otherWindows(): String = runCatching {
            Robot(robotPort, Duration.ofSeconds(15)).callJs(
                """
                function texts(c, out) {
                    if (c instanceof javax.swing.AbstractButton || c instanceof javax.swing.JLabel || c instanceof javax.swing.text.JTextComponent) {
                        var text = c.getText();
                        if (text != null && String(text).trim().length > 0) {
                            out.push(String(text).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().substring(0, 200));
                        }
                    }
                    var children = c.getComponents ? c.getComponents() : [];
                    for (var i = 0; i < children.length; i++) texts(children[i], out);
                    return out;
                }
                var windows = java.awt.Window.getWindows();
                var shown = [];
                for (var i = 0; i < windows.length; i++) {
                    var window = windows[i];
                    if (!window.isShowing() || window instanceof com.intellij.openapi.wm.impl.IdeFrameImpl) continue;
                    var title = window.getTitle ? String(window.getTitle()) : '';
                    shown.push(window.getClass().getName() + ' "' + title + '": ' + texts(window, []).join(' | '));
                }
                String(shown.length == 0 ? 'no other window' : 'other windows: ' + shown.join(' ;; '))
                """,
            )
        }.getOrElse { "windows not read (${it::class.simpleName}: ${it.message})" }

        private fun threadDump(): Boolean = when (launch) {
            Launch.DOCKER -> container?.let { docker("exec", it, "pkill", "-QUIT", "-f", "com.intellij.idea.Main") == 0 } ?: false
            Launch.XVFB -> ide?.descendants()?.toList().orEmpty()
                .filter { it.info().commandLine().orElse("").contains("com.intellij.idea.Main") }
                .map { ProcessBuilder("kill", "-QUIT", it.pid().toString()).start().waitFor() == 0 }
                .any { it }
            Launch.ATTACH -> false
        }

        // A frame sized like a real screen: a window manager or a sleeping monitor may open it tiny, and inlaid
        // thread panels then get zero width, so the robot finds none. The servers answer before the project's frame
        // exists; on a loaded machine that gap ran seconds.
        private fun sizeTheFrame(): String = awaitValue("the project's frame exists", Duration.ofSeconds(60), Duration.ofSeconds(1)) {
            robot.callJs(
                """
                var frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(
                    com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]);
                if (frame == null) String('none');
                else {
                    frame.setExtendedState(java.awt.Frame.NORMAL);
                    frame.setBounds(0, 0, 2560, 1440);
                    frame.validate();
                    String(frame.getWidth() + 'x' + frame.getHeight());
                }
                """,
                edt = true,
            ).takeIf { it != "none" }
        }

        // The IDE restores its tool window layout during the project's startup; the Project view it shows then takes
        // the left side from a Marginalis tool window a scenario has just shown. And while the platform's own startup
        // write (the JPS global model reloaded from disk 5 s after a start from its cache; a Docker sandbox drops the
        // cache, so there it loads at once) waits for the lock, a file opened on the EDT blocks the editor providers'
        // reads until their 30 s timeout. The Margin tab's startup decision waits for the project's configuration and
        // comes after the platform's post-startup mark; a scenario must not race it. Smart mode flickers on right after
        // startup, so it must hold three samples in a row.
        private fun awaitProjectStartup() {
            var settledPolls = 0
            var noted = false
            val started = System.nanoTime()
            // Since when each condition has held without a break: the last one to hold is what the start waited for.
            val heldSince = linkedMapOf<String, Duration?>()
            awaitUntil(
                "the project has started up, is out of dumb mode, has its global model from disk, no write pending and " +
                    "the Margin tab's startup decision made, 3 s in a row",
                Duration.ofSeconds(180), Duration.ofSeconds(1),
            ) {
                // A read that fails counts as unsettled too.
                val before = settledPolls
                settledPolls = 0
                val probe = JsonParser.parseString(
                    robot.callJs(
                        """
                        var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
                        // The global workspace model is read from a cache first and reloaded from disk seconds later,
                        // in a background write. The private flag is one Boolean up to 2025.x and a Boolean per
                        // environment in 2026.x (loaded once there is one and all are true). Without it (another
                        // platform version) the condition is skipped, and the reason reported.
                        var globalModel = true;
                        var globalModelUnavailable = null;
                        try {
                            var app = com.intellij.openapi.application.ApplicationManager.getApplication();
                            var type = java.lang.Class.forName('com.intellij.workspaceModel.ide.JpsGlobalModelSynchronizer', true,
                                app.getClass().getClassLoader());
                            var sync = app.getService(type);
                            var field = sync.getClass().getDeclaredField('loadedFromDisk');
                            field.setAccessible(true);
                            var loaded = field.get(sync);
                            if (loaded instanceof java.util.Map) {
                                var values = new java.util.ArrayList(loaded.values());
                                globalModel = values.size() > 0 && !values.contains(java.lang.Boolean.FALSE);
                            } else {
                                globalModel = loaded.booleanValue();
                            }
                        } catch (e) {
                            globalModelUnavailable = String(e);
                        }
                        // The decision marks a project introduced and clears the mark of a tab open at shutdown. Until
                        // both hold, the decision is pending or could not open the tab anyway.
                        var tabDecided = projects.length > 0 && (function (properties) {
                            return properties.getBoolean('marginalis.projectTab.introduced')
                                && !properties.isValueSet('marginalis.projectTab.reopen');
                        })(com.intellij.ide.util.PropertiesComponent.getInstance(projects[0]));
                        String(JSON.stringify({
                            conditions: {
                                'post-startup': projects.length > 0
                                    && com.intellij.ide.startup.StartupManagerEx.getInstanceEx(projects[0]).postStartupActivityPassed(),
                                'smart mode': projects.length > 0 && !com.intellij.openapi.project.DumbService.isDumb(projects[0]),
                                'no write pending': !com.intellij.openapi.application.ApplicationManager.getApplication().isWriteActionPending(),
                                'global model from disk': globalModel,
                                'Margin tab decided': tabDecided
                            },
                            globalModelUnavailable: globalModelUnavailable
                        }))
                        """,
                    ),
                ).asJsonObject
                probe.get("globalModelUnavailable")?.takeUnless { it.isJsonNull || noted }?.let { reason ->
                    println("note  the start does not wait for the global workspace model loaded from disk: ${reason.asString}")
                    noted = true
                }
                val elapsed = Duration.ofNanos(System.nanoTime() - started)
                val conditions = probe.getAsJsonObject("conditions").entrySet().associate { (name, held) -> name to held.asBoolean }
                conditions.forEach { (name, held) -> heldSince[name] = if (held) heldSince[name] ?: elapsed else null }
                settledPolls = if (conditions.values.all { it }) before + 1 else 0
                settledPolls >= 3
            }
            println(
                "project settled in ${seconds(Duration.ofNanos(System.nanoTime() - started))}; each condition held from " +
                    heldSince.entries.joinToString { (name, since) -> "$name ${since?.let(::seconds)}" },
            )
        }

        private fun seconds(duration: Duration) = "%.1f s".format(Locale.ROOT, duration.toMillis() / 1000.0)

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

    private class DockerRun(val exit: Int, val output: String)

    /** Runs a docker command; its output holds stdout and stderr together. */
    private fun dockerRun(vararg args: String): DockerRun {
        val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return DockerRun(process.waitFor(), output.trim())
    }

    private fun docker(vararg args: String, check: Boolean = false): Int {
        val run = dockerRun(*args)
        check(!check || run.exit == 0) { "docker ${args.first()} exited with ${run.exit}: ${run.output}" }
        return run.exit
    }

    /** What a docker command printed, or null when it failed. */
    private fun dockerOutput(vararg args: String): String? {
        val process = ProcessBuilder("docker", *args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        return output.takeIf { process.waitFor() == 0 }
    }

    private fun getJson(url: String) = runCatching {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(1)).build()
        JsonParser.parseString(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).asJsonObject
    }.getOrNull()
}
