package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.LongThreadSettings
import dev.marginalis.plugin.testing.MarginFileEditors
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.testing.ProjectTabSettings
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.SecondProject
import dev.marginalis.plugin.transport.HttpContractTestCase

abstract class MarginTabTestCase : HttpContractTestCase() {

    protected lateinit var tab: MarginTab

    private val marginFileEditors = MarginFileEditors()

    override fun setUp() {
        super.setUp()
        marginFileEditors.watch()
        tab = user.openMarginTab()
    }

    override fun tearDown() {
        try {
            ProjectTabSettings.restoreDefaults()
            LongThreadSettings.restoreDefaults()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            marginFileEditors.namingTheirOpeners { super.tearDown() }
        }
    }

    protected fun showTheTabWhenAProjectOpens() {
        MarginalisSettings.getInstance().state.showTabOnProjectOpen = true
    }

    protected fun Agent.ask(body: String, vararg fields: Pair<String, Any?>): String = add("body" to body, *fields)

    protected fun Agent.relay(id: Long, body: String): String = add("body" to body, "relayed" to Relays.conversationComment(id))

    protected fun shown(): List<String> = tab.rows()

    protected fun withTwin(body: (SecondProject, MarginTab) -> Unit) {
        val sampleText = (1..60).joinToString("\n") { lineText(it) } + "\n"
        SecondProject.open("twin", mapOf("twin/$SAMPLE" to sampleText)).use { second ->
            body(second, MarginTab(second.project, second.project).open())
        }
    }

    protected companion object {
        const val EMPTY_STATE = "Project threads are for what isn't about one file: plans, release notes, a pull request's conversation. " +
            "Agents start them here, and so can you."
        const val NEW_PROJECT_THREAD = "+ New project thread"
    }
}
