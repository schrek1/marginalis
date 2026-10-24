package dev.marginalis.plugin.testing

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.tab.MarginFile

abstract class MarginalisTestCase : BasePlatformTestCase() {

    private val freshProject = object : LightProjectDescriptor() {}

    override fun getProjectDescriptor(): LightProjectDescriptor = freshProject

    protected val store: MarginalisStore get() = MarginalisStore.getInstance(project)

    override fun setUp() {
        super.setUp()
        awaitFirstRunTab()
    }

    // A fresh project opens the Margin tab from a queued startup step; left
    // queued, it can open after tearDown has closed the editors, which then
    // reports the tab's editor as leaked.
    private fun awaitFirstRunTab() =
        PlatformTestUtil.waitWithEventsDispatching(
            "the first-run Margin tab never opened",
            { FileEditorManager.getInstance(project).openFiles.any { it is MarginFile } },
            10,
        )
}
