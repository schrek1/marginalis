package dev.marginalis.plugin.sandbox

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

// MARG-145 spike, never merged: a fresh project opened in a warm IDE against a cold IDE start, both timed to the
// readiness point Sandbox.awaitProjectStartup waits for. Lives in the sandbox package so CI starts it first.
class ReopenVsColdSpikeTest : SandboxScenario("reopen_vs_cold_spike") {

    private val rounds = System.getenv("MARGINALIS_SPIKE_ROUNDS")?.toInt() ?: 5
    private val reopensPerRound = 3
    private val cpus = System.getenv("MARGINALIS_SPIKE_CPUS")?.toDouble()
    private val samples = mutableMapOf<String, MutableList<Double>>()
    private var reopenCount = 0

    @Test
    fun measure() = case("measure") {
        report("SPIKE config rounds=$rounds reopensPerRound=$reopensPerRound cpus=${cpus ?: "uncapped"}")
        disableReopenLastProject()
        cpus?.let { Sandbox.limitCpus(it) }
        repeat(rounds) { round ->
            // A new container drops a CPU cap, so the capped run measures restarts of its one container only.
            if (cpus == null) coldNew(round)
            coldRestart(round)
            repeat(reopensPerRound) { reopen(round) }
        }
        samples.forEach { (kind, values) ->
            val sorted = values.sorted()
            report(
                "SPIKE summary $kind n=${sorted.size} min=${fmt(sorted.first())} median=${fmt(median(sorted))} " +
                    "max=${fmt(sorted.last())} all=${values.joinToString(",") { fmt(it) }}",
            )
        }
        cpus?.let { Sandbox.limitCpus(null) }
    }

    // --- cold ------------------------------------------------------------------------------------------------------

    /** What a scenario pays today: the previous scenario's container removed, a new one created and started. */
    private fun coldNew(round: Int) {
        val stop = timed { Sandbox.stop() }
        val start = timed { Sandbox.start(fresh = true) }
        record("cold-new", stop + start, "round=${round + 1} stop=${fmt(stop)} start=${fmt(start)} ${afterStart()}")
        disableReopenLastProject()
    }

    private fun coldRestart(round: Int) {
        val total = timed { Sandbox.restart(fresh = true) }
        record("cold-restart", total, "round=${round + 1} ${afterStart()}")
    }

    // --- reopen ----------------------------------------------------------------------------------------------------

    private fun reopen(round: Int) {
        val n = ++reopenCount
        val inContainer = "/work/runtime/reopen-$n/project"
        val close = timed { closeProject() }
        val checks = timed { cleanIdeChecks() }
        val copy = timed { copyFreshProject(n, inContainer) }
        val open = timed { openProject(inContainer) }
        val ready = timed { awaitReady(inContainer) }
        record(
            "reopen", close + checks + copy + open + ready,
            "round=${round + 1} n=$n close=${fmt(close)} checks=${fmt(checks)} copy=${fmt(copy)} open=${fmt(open)} " +
                "ready=${fmt(ready)} ${afterStart()}",
        )
        smoke(inContainer)
    }

    /** Saves project and app settings, copies the project back to the host, closes it and waits until it is gone. */
    private fun closeProject() {
        Sandbox.robot.runJs(
            """
            var store = com.intellij.configurationStore.StoreUtil;
            var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            for (var i = 0; i < projects.length; i++) store.saveSettings(projects[i], true);
            store.saveSettings(com.intellij.openapi.application.ApplicationManager.getApplication(), true);
            """,
        )
        val path = Sandbox.robot.callJs("String(com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0].getBasePath())")
        docker("exec", container(), "rsync", "-a", "--delete", "$path/", "/project-host/")
        Sandbox.robot.runJs(
            """
            var project = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0];
            java.lang.System.getProperties().remove('spike.close');
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
                var outcome;
                try { outcome = 'closed=' + com.intellij.openapi.project.ex.ProjectManagerEx.getInstanceEx().closeAndDispose(project); }
                catch (error) { outcome = 'failed: ' + error; }
                java.lang.System.getProperties().put('spike.close', outcome);
            } }));
            """,
        )
        val outcome = awaitValue("the project closes", Duration.ofSeconds(120), Duration.ofMillis(250)) {
            Sandbox.robot.callJs(
                """
                var outcome = java.lang.System.getProperty('spike.close');
                var open = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length;
                outcome == null || open > 0 ? String('') : String(outcome)
                """,
            ).ifEmpty { null }
        }
        if (outcome != "closed=true") report("SPIKE note close outcome: $outcome")
    }

    /**
     * A cheap stand-in for the plan's clean-IDE and app-state checks: the windows left, app settings saved and the
     * IDE config's options hashed.
     */
    private fun cleanIdeChecks() {
        val windows = otherWindows()
        if (windows != "only welcome frame") report("SPIKE note after close: $windows")
        docker(
            "exec", container(), "sh", "-c",
            "cd /work/runtime/ide/config_runIdeForUiTests/options && sha1sum *.xml > /tmp/options.sha1",
        )
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyFreshProject(n: Int, inContainer: String) {
        val host = Sandbox.runtime.resolve("reopen_vs_cold_spike").resolve("reopen-$n").resolve("project")
        if (host.exists()) host.deleteRecursively()
        host.parent.createDirectories()
        Sandbox.harness.resolve("project-template").copyToRecursively(host, followLinks = false, overwrite = false)
        docker("exec", container(), "mkdir", "-p", inContainer.substringBeforeLast('/'))
        docker("cp", host.toString(), "${container()}:$inContainer")
    }

    private fun openProject(inContainer: String) {
        Sandbox.robot.runJs(
            """
            java.lang.System.getProperties().remove('spike.open');
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
                var outcome;
                try {
                    var opened = com.intellij.ide.impl.ProjectUtil.openOrImport(new java.io.File('$inContainer').toPath(), null, true);
                    outcome = opened == null ? 'null' : 'opened';
                } catch (error) { outcome = 'failed: ' + error; }
                java.lang.System.getProperties().put('spike.open', outcome);
            } }));
            """,
        )
        // openOrImport runs under a modal progress on the EDT, so it returns once the project is open.
        val outcome = awaitValue("openOrImport returns", Duration.ofSeconds(300), Duration.ofMillis(250)) {
            Sandbox.robot.callJs("var o = java.lang.System.getProperty('spike.open'); String(o == null ? '' : o)").ifEmpty { null }
        }
        check("reopen: openOrImport opened the project", outcome == "opened", outcome)
    }

    /** The same readiness as a cold start: the API serves the project, the frame is sized, the startup probe holds. */
    private fun awaitReady(inContainer: String) {
        val port = Sandbox.robotPort + 10000
        awaitUntil("the Marginalis API serves $inContainer", Duration.ofSeconds(120), Duration.ofMillis(500)) {
            val ping = runCatching {
                JsonParser.parseString(java.net.URI("http://127.0.0.1:$port/api/marginalis/ping").toURL().readText()).asJsonObject
            }.getOrNull()
            ping?.getAsJsonArray("projects")?.any { it.asJsonObject.get("path")?.asString == inContainer } == true
        }
        awaitValue("the project's frame exists", Duration.ofSeconds(60), Duration.ofSeconds(1)) {
            Sandbox.robot.callJs(
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
        var settledPolls = 0
        awaitUntil("the reopened project has settled, 3 s in a row", Duration.ofSeconds(300), Duration.ofSeconds(1)) {
            val before = settledPolls
            settledPolls = 0
            val settled = Sandbox.robot.callJs(STARTUP_PROBE) == "true"
            settledPolls = if (settled) before + 1 else 0
            settledPolls >= 3
        }
    }

    /** Untimed: the reopened project is the only one, and the plugin's API works in it. */
    private fun smoke(inContainer: String) {
        val open = Sandbox.robot.callJs(
            """
            var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            var paths = [];
            for (var i = 0; i < projects.length; i++) paths.push(String(projects[i].getBasePath()));
            String(paths.join(','))
            """,
        )
        check("reopen: exactly the new project is open", open == inContainer, open)
        val thread = Agent.agentStartsProjectThread("Spike: does the API work in the reopened project?")
        val listed = Agent.apiGet("comment_list", mapOf("summary" to "true")).getAsJsonArray("threads")
            .map { it.asJsonObject.get("thread_id").asString }
        check("reopen: the API lists only the thread just posted", listed == listOf(thread), listed)
        val windows = otherWindows()
        if (windows != "only project frame") report("SPIKE note after reopen: $windows")
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun afterStart(): String {
        val projects = runCatching {
            Sandbox.robot.callJs("String(com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length)")
        }.getOrDefault("?")
        return "openProjects=$projects sandboxes=${runningSandboxes()}"
    }

    // A cold restart would otherwise reopen the projects the reopens left open next to the one it is started with.
    private fun disableReopenLastProject() {
        Sandbox.robot.runJs("com.intellij.ide.GeneralSettings.getInstance().setReopenLastProject(false)")
    }

    private fun otherWindows(): String = Sandbox.robot.callJs(
        """
        var windows = java.awt.Window.getWindows();
        var shown = [];
        var frames = 0;
        var welcome = 0;
        for (var i = 0; i < windows.length; i++) {
            var window = windows[i];
            if (!window.isShowing()) continue;
            var name = String(window.getClass().getName());
            if (window instanceof com.intellij.openapi.wm.impl.IdeFrameImpl) frames++;
            else if (name.indexOf('Welcome') >= 0) welcome++;
            else shown.push(name + ' "' + (window.getTitle ? String(window.getTitle()) : '') + '"');
        }
        if (shown.length == 0 && frames == 1 && welcome == 0) String('only project frame');
        else if (shown.length == 0 && frames == 0 && welcome == 1) String('only welcome frame');
        else String('frames=' + frames + ' welcome=' + welcome + ' other=' + shown.join(' ;; '));
        """,
    )

    private fun container() = "marginalis-sandbox-${Sandbox.robotPort}"

    private fun runningSandboxes(): Int = runCatching {
        val process = ProcessBuilder("docker", "ps", "-q", "--filter", "name=marginalis-sandbox").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readLines().count { it.isNotBlank() }.also { process.waitFor() }
    }.getOrDefault(-1)

    private fun docker(vararg args: String) {
        val process = ProcessBuilder("docker", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        kotlin.check(exit == 0) { "docker ${args.take(3).joinToString(" ")} exited with $exit: $output" }
    }

    private fun record(kind: String, seconds: Double, detail: String) {
        samples.getOrPut(kind) { mutableListOf() } += seconds
        report("SPIKE ${if (kind == "reopen") "reopen" else "cold"} ${fmt(seconds)} kind=$kind $detail")
    }

    private fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e9
    }

    private fun median(sorted: List<Double>) =
        if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2

    private fun fmt(seconds: Double) = "%.1f".format(Locale.ROOT, seconds)

    private companion object {
        // Sandbox.awaitProjectStartup's probe, unchanged but for returning the boolean alone.
        const val STARTUP_PROBE = """
            var projects = com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects();
            var globalModel = true;
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
            }
            var tabDecided = projects.length > 0 && (function (properties) {
                return properties.getBoolean('marginalis.projectTab.introduced')
                    && !properties.isValueSet('marginalis.projectTab.reopen');
            })(com.intellij.ide.util.PropertiesComponent.getInstance(projects[0]));
            String(projects.length > 0
                && com.intellij.ide.startup.StartupManagerEx.getInstanceEx(projects[0]).postStartupActivityPassed()
                && !com.intellij.openapi.project.DumbService.isDumb(projects[0])
                && !com.intellij.openapi.application.ApplicationManager.getApplication().isWriteActionPending()
                && globalModel
                && tabDecided)
        """
    }
}
