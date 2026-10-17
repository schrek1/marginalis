package dev.marginalis.plugin.sandbox

import java.time.Duration

/*
 * The one way a scenario waits for the IDE: until a condition holds, for a bounded time. A read that throws counts as
 * "not yet" when `retryOn` accepts its error and ends the wait otherwise. A timeout records the sandbox's diagnostics
 * (a thread dump in its IDE log, a shot of its frame) before it reports.
 */

private val usualWait: Duration = Duration.ofSeconds(10)
private val pollEvery: Duration = Duration.ofMillis(500)

/** @return whether `condition` held within `timeout`; a timeout also records diagnostics. */
fun waitUntil(
    what: String,
    timeout: Duration = usualWait,
    every: Duration = pollEvery,
    retryOn: (Throwable) -> Boolean = { true },
    condition: () -> Boolean,
): Boolean = poll(what, timeout, every, retryOn) { if (condition()) true else null }.value != null

/** Like [waitUntil], but a timeout fails the step. */
fun awaitUntil(
    what: String,
    timeout: Duration = usualWait,
    every: Duration = pollEvery,
    retryOn: (Throwable) -> Boolean = { true },
    condition: () -> Boolean,
) {
    awaitValue(what, timeout, every, retryOn) { if (condition()) true else null }
}

/** The first value `read` gives within `timeout`; a timeout fails the step. */
fun <T : Any> awaitValue(
    what: String,
    timeout: Duration = usualWait,
    every: Duration = pollEvery,
    retryOn: (Throwable) -> Boolean = { true },
    read: () -> T?,
): T {
    val polled = poll(what, timeout, every, retryOn, read)
    return polled.value ?: throw IllegalStateException(
        "$what: not within ${timeout.seconds}s${polled.lastError?.let { "; last ${it::class.simpleName}: ${it.message}" }.orEmpty()}" +
            " (${polled.diagnostics})",
    )
}

/**
 * The first value `read` gives that `done` accepts within `timeout`. A timeout returns the last value read, so the
 * step's own check fails on what the IDE really shows, and records diagnostics; when every read threw, it rethrows the
 * last error.
 */
fun <T> readUntil(
    what: String,
    timeout: Duration = Duration.ofSeconds(20),
    every: Duration = pollEvery,
    read: () -> T,
    done: (T) -> Boolean,
): T {
    var last: Read<T>? = null
    val polled = poll(what, timeout, every, retryOn = { true }) {
        Read(read()).also { last = it }.takeIf { done(it.value) }
    }
    return (polled.value ?: last ?: throw checkNotNull(polled.lastError)).value
}

private class Read<T>(val value: T)

private class Polled<T>(val value: T?, val lastError: Throwable?, val diagnostics: String?)

private fun <T : Any> poll(what: String, timeout: Duration, every: Duration, retryOn: (Throwable) -> Boolean, read: () -> T?): Polled<T> {
    val deadline = System.nanoTime() + timeout.toNanos()
    var lastError: Throwable? = null
    while (true) {
        val value = try {
            read()
        } catch (error: Exception) {
            if (!retryOn(error)) throw error
            lastError = error
            null
        }
        if (value != null) return Polled(value, null, null)
        if (System.nanoTime() >= deadline) return Polled(null, lastError, Sandbox.recordDiagnostics(what))
        Thread.sleep(every.toMillis())
    }
}
