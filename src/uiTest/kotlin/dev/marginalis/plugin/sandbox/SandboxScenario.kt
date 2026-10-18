package dev.marginalis.plugin.sandbox

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
import java.time.LocalTime
import java.util.Locale

/**
 * One sandbox scenario: a fresh sandbox IDE of its own on a fresh project for the class, its cases run in their
 * declared [org.junit.jupiter.api.Order] (later cases build on the threads earlier ones posted) on one thread, the IDE
 * stopped at the end. Scenarios run side by side; each records its duration for [LongestFirst].
 *
 * Every behaviour is a [check] that prints `PASS  name` or `FAIL  name  (detail)` into the test output and
 * `<runtime>/kotlin-logs/<scenario>.log`. A case fails when any of its checks did, or when it throws: that is reported
 * as a FAIL named after the case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class SandboxScenario(private val scenario: String) {

    protected val shots: Path = Sandbox.runtime.resolve("shots").resolve(scenario)
    private val log: Path = Sandbox.runtime.resolve("kotlin-logs").resolve("$scenario.log")
    private val failures = mutableListOf<String>()
    private var passes = 0
    private var started = 0L
    private var inCase = false

    @BeforeAll
    fun startSandbox() {
        Files.createDirectories(log.parent)
        Files.deleteIfExists(log)
        started = System.nanoTime()
        report("INFO  $scenario starts on ${Thread.currentThread().name} at ${LocalTime.now().withNano(0)}")
        Sandbox.open(scenario)
        Sandbox.start(fresh = true)
    }

    @AfterAll
    fun stopSandbox() {
        val ideLog = Sandbox.ideLog
        val stopFailure = runCatching { Sandbox.close() }.exceptionOrNull()
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        Durations.record(javaClass.name, elapsed)
        val minutes = elapsed.toMillis() / 60_000.0
        report("INFO  $scenario ends at ${LocalTime.now().withNano(0)}")
        try {
            case("ide_shutdown") {
                check("the IDE exited when asked", stopFailure == null, stopFailure?.message)
                // Read after the stop: errors the plugin raises while the IDE shuts down count too.
                val errors = PluginErrors.inLog(if (Files.exists(ideLog)) Files.readAllLines(ideLog) else emptyList())
                check("the IDE logged no error from the plugin", errors.isEmpty(), errors.firstOrNull()?.let { "${errors.size} entries in $ideLog, first: $it" })
            }
        } finally {
            report("== $scenario: $passes pass, ${failures.size} fail, ${"%.1f".format(Locale.ROOT, minutes)} min")
        }
    }

    fun check(name: String, passed: Boolean, detail: Any? = "") {
        report(if (passed) "PASS  $name" else "FAIL  $name  ($detail)")
        if (passed) passes++ else failures += name
        // Outside a case (e.g. in @BeforeAll) nothing else would fail the run for it.
        if (!passed && !inCase) throw AssertionError("$name  ($detail)")
    }

    /** Runs one case of the scenario; a crash in it is reported as a FAIL under `name`. */
    protected fun case(name: String, body: () -> Unit) {
        val failedBefore = failures.size
        inCase = true
        try {
            body()
        } catch (error: Exception) {
            check(name, false, "${error::class.simpleName}: ${error.message}")
            error.printStackTrace()
        } finally {
            inCase = false
        }
        val failed = failures.drop(failedBefore)
        if (failed.isNotEmpty()) throw AssertionError("${failed.size} failed: ${failed.joinToString("; ")}")
    }

    protected fun shot(name: String) = SandboxUi.screenshotFrame(shots.resolve("$name.png"))

    /** A line that is not a check, e.g. `INFO  ...`, logged like one. */
    protected fun report(line: String) {
        println(line)
        Files.writeString(log, line + System.lineSeparator(), CREATE, APPEND)
    }
}

/** `read()`, or the error it raised as text, for a FAIL line's detail. */
fun safe(read: () -> Any?): Any? = runCatching(read).getOrElse { "${it::class.simpleName}: ${it.message}" }
