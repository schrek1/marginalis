package dev.marginalis.plugin.testing

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.marginalis.core.MarginTabShows
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore

abstract class MarginalisTestCase : BasePlatformTestCase() {

    private val freshProject = object : LightProjectDescriptor() {}

    override fun getProjectDescriptor(): LightProjectDescriptor = freshProject

    protected val store: MarginalisStore get() = MarginalisStore.getInstance(project)

    override fun setUp() {
        super.setUp()
        startFromKnownSettings()
        awaitStartup()
    }

    // Settings are app-wide, so a test class that changes "Margin tab" would carry its choice into the next one: every
    // test starts from the fork's default. Steps in a review across files are off, as upstream's tests are written.
    private fun startFromKnownSettings() {
        val settings = MarginalisSettings.getInstance()
        settings.marginTabShows = MarginTabShows.WITH_SOMETHING_IN_IT
        settings.state.keepStepsInReviewAcrossFiles = false
    }

    // The startup step decides about the Margin tab from a queued call, which can run after the activities count as
    // passed. Left queued, it closes the empty tab a test has opened since, or opens one after tearDown closed the
    // editors (reported as a leak). The decision marks the project's first open, so wait for that mark.
    private fun awaitStartup() {
        PlatformTestUtil.waitWithEventsDispatching(
            "the startup decision about the Margin tab never ran",
            { PropertiesComponent.getInstance(project).getBoolean(STARTUP_DECIDED_THE_TAB) },
            10,
        )
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private companion object {
        const val STARTUP_DECIDED_THE_TAB = "marginalis.projectTab.introduced"
    }
}
