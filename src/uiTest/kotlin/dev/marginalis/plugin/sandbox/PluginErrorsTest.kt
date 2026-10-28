package dev.marginalis.plugin.sandbox

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class PluginErrorsTest {

    @Test
    fun `an error whose stack trace passes through the plugin is reported with the plugin's frame`() {
        // GIVEN - a SEVERE entry whose stack runs through the plugin's code
        val log = """
            2026-10-17 09:18:29,950 [  31200]   INFO - #c.i.i.p.PluginManager - Loaded plugins: Marginalis
            2026-10-17 09:18:30,100 [  31321] SEVERE - #c.i.o.a.i.ApplicationImpl - Write access is allowed inside write-action only
            java.lang.Throwable: Write access is allowed inside write-action only
            	at com.intellij.openapi.application.impl.ApplicationImpl.assertWriteAccessAllowed(ApplicationImpl.java:1050)
            	at dev.marginalis.plugin.store.MarginalisStore.save(MarginalisStore.kt:88)
            	at com.intellij.openapi.application.impl.LaterInvocator.run(LaterInvocator.java:120)
            2026-10-17 09:18:30,200 [  31421]   INFO - #c.i.o.a.i.ApplicationImpl - exiting
        """.trimIndent().lines()

        // WHEN - the log is read
        val errors = PluginErrors.inLog(log)

        // THEN - the entry is reported with the plugin's frame
        errors shouldContainExactly listOf(
            "2026-10-17 09:18:30,100 [  31321] SEVERE - #c.i.o.a.i.ApplicationImpl - Write access is allowed inside write-action only" +
                " | at dev.marginalis.plugin.store.MarginalisStore.save(MarginalisStore.kt:88)",
        )
    }

    @Test
    fun `an error the plugin logs under its own logger is reported`() {
        val log = listOf("2026-10-17 09:18:31,000 [  32000]  ERROR - #dev.marginalis.plugin.transport.MarginalisRestService - wait failed")

        PluginErrors.inLog(log) shouldContainExactly log
    }

    @Test
    fun `errors elsewhere and the plugin's warnings pass`() {
        // GIVEN - a SEVERE from another plugin and a WARN from Marginalis
        val log = """
            2026-10-17 09:18:30,100 [  31321] SEVERE - #c.i.o.a.i.ApplicationImpl - Failed to close and dispose all projects
            com.intellij.diagnostic.PluginException: Robot server version: 0.11.23
            	at com.intellij.remoterobot.RobotServer.stop(RobotServer.kt:40)
            2026-10-17 09:18:30,300 [  31521]   WARN - #dev.marginalis.plugin.ui.diff.ReviewDiffSteps - Review diff file list unavailable
        """.trimIndent().lines()

        // WHEN - the log is read
        val errors = PluginErrors.inLog(log)

        // THEN - nothing is reported
        errors.shouldBeEmpty()
    }

    @Test
    fun `only the errors written since a moment are reported when one is given`() {
        // GIVEN - a plugin error from an earlier run and one written after the moment
        val log = """
            2026-10-28 06:40:00,000 [   1000]  ERROR - #dev.marginalis.plugin.ui.AvatarIcon - from an earlier run
            2026-10-28 06:48:56,900 [  14819]  ERROR - #dev.marginalis.plugin.ui.AvatarIcon - during the update
        """.trimIndent().lines()

        // WHEN - the log is read since the update started
        val errors = PluginErrors.inLog(log, since = "2026-10-28 06:48:55,000")

        // THEN - only the later entry is reported
        errors shouldContainExactly listOf(log[1])
    }
}
