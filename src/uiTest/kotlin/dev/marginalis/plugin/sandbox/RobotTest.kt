package dev.marginalis.plugin.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import kotlin.concurrent.thread

class RobotTest {

    @Test
    fun `a robot call the IDE never answers fails with the diagnostics it recorded`() {
        // GIVEN - an IDE whose robot server takes the call and never answers
        unansweringRobotServer().use { server ->
            val robot = Robot(server.localPort, Duration.ofSeconds(1), diagnose = { what -> "thread dump for $what" })

            // WHEN - a step calls it
            val failure = shouldThrow<Robot.RobotCallTimedOut> { robot.callJs("String(true)", edt = true) }

            // THEN - the failure names what was recorded about the IDE at that moment
            failure.message shouldContain "thread dump for robot call unanswered"
            failure.cause.shouldBeInstanceOf<InterruptedIOException>()
        }
    }

    @Test
    fun `a robot without diagnostics lets the timeout through as it is`() {
        unansweringRobotServer().use { server ->
            val robot = Robot(server.localPort, Duration.ofSeconds(1))

            shouldThrow<InterruptedIOException> { robot.callJs("String(true)", edt = true) }
        }
    }

    // Holds every accepted connection open, so the client sees silence rather than a closed stream.
    private fun unansweringRobotServer(): ServerSocket = ServerSocket(0).also { server ->
        val held = mutableListOf<Socket>()
        thread(isDaemon = true) {
            runCatching { while (!server.isClosed) held += server.accept() }
        }
    }
}
