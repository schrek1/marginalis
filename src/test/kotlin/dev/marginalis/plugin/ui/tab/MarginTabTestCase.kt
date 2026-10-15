package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.ProjectTabSettings
import dev.marginalis.plugin.transport.HttpContractTestCase

abstract class MarginTabTestCase : HttpContractTestCase() {

    protected lateinit var tab: MarginTab

    override fun setUp() {
        super.setUp()
        tab = user.openMarginTab()
    }

    override fun tearDown() {
        try {
            ProjectTabSettings.restoreDefaults()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    protected fun Agent.ask(body: String, vararg fields: Pair<String, Any?>): String = add("body" to body, *fields)

    protected fun shown(): List<String> = tab.rows()

    protected companion object {
        const val EMPTY_STATE = "Project threads are for what isn't about one file: plans, release notes, a pull request's conversation. " +
            "Agents start them here, and so can you."
        const val NEW_PROJECT_THREAD = "+ New project thread"
    }
}
