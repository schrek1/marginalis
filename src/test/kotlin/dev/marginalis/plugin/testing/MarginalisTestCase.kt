package dev.marginalis.plugin.testing

import com.intellij.ide.startup.StartupManagerEx
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

    // The startup step decides about the Margin tab from a queued call; left queued, it can open the tab after
    // tearDown has closed the editors, which then reports the tab's editor as leaked. Without project threads it
    // opens nothing, so wait for the step itself, not for a tab.
    private fun awaitStartup() {
        PlatformTestUtil.waitWithEventsDispatching(
            "the project's startup activities never finished",
            { StartupManagerEx.getInstanceEx(project).postStartupActivityPassed() },
            10,
        )
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
}
