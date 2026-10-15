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
import java.util.Locale

/**
 * One sandbox scenario: a fresh sandbox IDE on a fresh project for the class, its cases run in their declared
 * [org.junit.jupiter.api.Order] (later cases build on the threads earlier ones posted), the IDE stopped at the end.
 *
 * Every behaviour is a [check] that prints `PASS  name` or `FAIL  name  (detail)` as the Python scenarios do, into
 * the test output and `<runtime>/kotlin-logs/<scenario>.log`. A case fails when any of its checks did, or when it
 * throws: that is reported as a FAIL named after the case, like the Python scenarios' guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
abstract class SandboxScenario(private val scenario: String) {

    protected val shots: Path = Sandbox.runtime.resolve("shots").resolve(scenario)
    private val log: Path = Sandbox.runtime.resolve("kotlin-logs").resolve("$scenario.log")
    private val failures = mutableListOf<String>()
    private var passes = 0
    private var started = 0L

    @BeforeAll
    fun startSandbox() {
        Files.createDirectories(log.parent)
        Files.deleteIfExists(log)
        started = System.nanoTime()
        Sandbox.restart(fresh = true)
    }

    @AfterAll
    fun stopSandbox() {
        Sandbox.stop()
        val minutes = Duration.ofNanos(System.nanoTime() - started).toMillis() / 60_000.0
        report("== $scenario: $passes pass, ${failures.size} fail, ${"%.1f".format(Locale.ROOT, minutes)} min")
    }

    fun check(name: String, passed: Boolean, detail: Any? = "") {
        report(if (passed) "PASS  $name" else "FAIL  $name  ($detail)")
        if (passed) passes++ else failures += name
    }

    /** Runs one case of the scenario; `name` is the Python scenario's method name, which a crash is reported under. */
    protected fun case(name: String, body: () -> Unit) {
        val failedBefore = failures.size
        try {
            body()
        } catch (error: Exception) {
            check(name, false, "${error::class.simpleName}: ${error.message}")
            error.printStackTrace()
        }
        val failed = failures.drop(failedBefore)
        if (failed.isNotEmpty()) throw AssertionError("${failed.size} failed: ${failed.joinToString("; ")}")
    }

    protected fun shot(name: String) = SandboxUi.screenshotFrame(shots.resolve("$name.png"))

    private fun report(line: String) {
        println(line)
        Files.writeString(log, line + System.lineSeparator(), CREATE, APPEND)
    }
}

/** Polls `condition` (a failing read counts as not yet) until it holds or the timeout passes. */
fun waitUntil(timeout: Duration = Duration.ofSeconds(10), condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (System.nanoTime() < deadline) {
        if (runCatching(condition).getOrDefault(false)) return true
        Thread.sleep(500)
    }
    return false
}

/** `read()`, or the error it raised as text, for a FAIL line's detail. */
fun safe(read: () -> Any?): Any? = runCatching(read).getOrElse { "${it::class.simpleName}: ${it.message}" }
