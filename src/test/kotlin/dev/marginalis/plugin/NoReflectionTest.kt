package dev.marginalis.plugin

import dev.marginalis.plugin.testing.ReflectionGuard
import org.junit.Test

class NoReflectionTest {

    @Test
    fun `every module reaches code only through public API`() {
        ReflectionGuard.check()
    }
}
