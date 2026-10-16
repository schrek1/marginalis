package dev.marginalis.plugin.testing

import com.intellij.ide.startup.StartupManagerEx
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.marginalis.plugin.store.MarginalisStore

abstract class MarginalisTestCase : BasePlatformTestCase() {

    private val freshProject = object : LightProjectDescriptor() {}

    override fun getProjectDescriptor(): LightProjectDescriptor = freshProject

    protected val store: MarginalisStore get() = MarginalisStore.getInstance(project)

    override fun setUp() {
        super.setUp()
        awaitStartup()
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
