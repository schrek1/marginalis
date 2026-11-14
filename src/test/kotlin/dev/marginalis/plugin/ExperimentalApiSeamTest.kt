package dev.marginalis.plugin

import dev.marginalis.plugin.testing.ExperimentalApiSeam
import org.junit.Test

class ExperimentalApiSeamTest {

    @Test
    fun `experimental platform API is reached only through its one seam`() {
        ExperimentalApiSeam.check()
    }
}
