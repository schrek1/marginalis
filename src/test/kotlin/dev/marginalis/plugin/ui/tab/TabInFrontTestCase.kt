package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.NeedsADisplay
import org.junit.runner.RunWith

@RunWith(NeedsADisplay::class)
abstract class TabInFrontTestCase : MarginTabTestCase() {

    override fun setUp() {
        super.setUp()
        tab.windowActive = true
    }
}
