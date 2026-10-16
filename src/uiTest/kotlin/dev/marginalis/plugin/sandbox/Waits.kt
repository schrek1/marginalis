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
