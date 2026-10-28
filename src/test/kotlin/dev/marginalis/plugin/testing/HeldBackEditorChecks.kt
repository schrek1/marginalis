package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.plugin.ui.tab.MarginFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Holds back the IDE's check of which editors can show the Margin tab, as a write action pending at startup holds back
 * every provider that checks under a read lock. Gives up by itself after [giveUpAfterSeconds], so a test that waits on
 * it fails instead of hanging.
 */
class HeldBackEditorChecks private constructor(private val giveUpAfterSeconds: Long) {

    private val release = CountDownLatch(1)

    val isHolding: Boolean get() = release.count > 0

    fun release() = release.countDown()

    private inner class Provider : FileEditorProvider {

        override fun accept(project: Project, file: VirtualFile): Boolean {
            if (file is MarginFile && !release.await(giveUpAfterSeconds, TimeUnit.SECONDS)) release.countDown()
            return false
        }

        override fun acceptRequiresReadAction(): Boolean = false

        override fun createEditor(project: Project, file: VirtualFile): FileEditor = error("accepts no file")

        override fun getEditorTypeId(): String = "held-back-editor-check"

        override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.NONE
    }

    companion object {
        fun install(disposable: Disposable, giveUpAfterSeconds: Long = 5): HeldBackEditorChecks {
            val checks = HeldBackEditorChecks(giveUpAfterSeconds)
            FileEditorProvider.EP_FILE_EDITOR_PROVIDER.point.registerExtension(checks.Provider(), disposable)
            return checks
        }
    }
}
