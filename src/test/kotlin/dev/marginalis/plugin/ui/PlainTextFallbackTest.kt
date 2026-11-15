package dev.marginalis.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import dev.marginalis.plugin.testing.MarginTab
import dev.marginalis.plugin.transport.HttpContractTestCase
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class PlainTextFallbackTest : HttpContractTestCase() {

    private val spec = "api/openapi.yaml"
    private val binary = "api/logo.bin"

    private lateinit var tab: MarginTab
    private lateinit var preview: PreviewThatFailsToBuild
    private var previewFailsAtOnce = true

    override fun setUp() {
        super.setUp()
        tab = MarginTab(project, testRootDisposable)
        preview = PreviewThatFailsToBuild()
        ApplicationManager.getApplication().extensionArea
            .getExtensionPoint(FileEditorProvider.EP_FILE_EDITOR_PROVIDER)
            .registerExtension(preview, testRootDisposable)
        project.messageBus.connect(testRootDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    if (previewFailsAtOnce && source.getAllEditors(file).any { it is Preview }) source.closeFile(file)
                }
            },
        )
    }

    private fun fileAt(path: String): VirtualFile = project.guessProjectDir()!!.findFileByRelativePath(path)!!

    private fun specStep(): String {
        sample(spec, lines = 30)
        return agent.addOnLine(12, "order" to 1, "walkthrough" to "tour", "body" to "The endpoint", file = spec)
    }

    private fun navigateTo(threadId: String, onShown: () -> Unit = {}) {
        WalkthroughNavigator.navigateTo(project, user.thread(threadId), onShown = onShown)
        tab.settle()
    }

    private fun isMarked(path: String): Boolean = project.serviceIfCreated<TextOnlyFiles>()?.isMarked(fileAt(path)) == true

    private fun intoTheSpec(asSteps: Boolean): List<String> {
        sample()
        sample(spec, lines = 30)
        fun order(n: Int): Array<Pair<String, Any?>> = if (asSteps) arrayOf("order" to n, "walkthrough" to "tour") else emptyArray()
        val ids = listOf(
            agent.addOnLine(5, *order(1), "body" to "Before the spec"),
            agent.addOnLine(12, *order(2), "body" to "The endpoint", file = spec),
        )
        tab.openFileInFront(SAMPLE)
        return ids
    }

    private fun openInTheEditorBehind(threadId: String) {
        val behind = tab.editors.allEditors.filterIsInstance<TextEditor>().single { it.file?.path?.endsWith(SAMPLE) == true }.editor
        ThreadInlayManager.open(project, behind, user.thread(threadId))
    }

    private fun openingAndThenClosingTheSpec(threadId: String, onShown: () -> Unit = {}, meanwhile: () -> Unit = {}) {
        previewFailsAtOnce = false
        WalkthroughNavigator.navigateTo(project, user.thread(threadId), onShown = onShown)
        meanwhile()
        tab.editors.closeFile(fileAt(spec))
        tab.settle()
    }

    private fun shownEditorTypes(path: String): List<String> = tab.editors.getEditorsWithProviders(fileAt(path)).second.map { it.editorTypeId }

    private fun shownLine(path: String): Int = (tab.editors.getAllEditors(fileAt(path)).single() as TextEditor).editor.caretModel.logicalPosition.line + 1

    fun testAStepWhosePreviewFailsOpensAsPlainTextAlone() {
        val step = specStep()
        val shown = user.notifications()
        var shownCalls = 0

        navigateTo(step) { shownCalls++ }

        assertTrue(tab.editors.isFileOpen(fileAt(spec)))
        assertEquals(listOf("marginalis-text-only"), shownEditorTypes(spec))
        assertEquals(12, shownLine(spec))
        assertEquals(1, preview.built)
        assertEquals(1, shownCalls)
        assertEquals(emptyList<String>(), shown.map { it.content })
    }

    fun testAFileTheIdeClosesOneEventAfterItOpensOpensAgainAsPlainText() {
        val step = specStep()
        val shown = user.notifications()
        var shownCalls = 0

        openingAndThenClosingTheSpec(step, onShown = { shownCalls++ })

        assertTrue(tab.editors.isFileOpen(fileAt(spec)))
        assertEquals(listOf("marginalis-text-only"), shownEditorTypes(spec))
        assertEquals(12, shownLine(spec))
        assertEquals(1, shownCalls)
        assertEquals(emptyList<String>(), shown.map { it.content })
    }

    fun testAStepLeftBeforeItsFileClosedIsNotRetried() {
        val (before, inTheSpec) = intoTheSpec(asSteps = true)

        openingAndThenClosingTheSpec(inTheSpec, meanwhile = { openInTheEditorBehind(before) })

        assertFalse(tab.editors.isFileOpen(fileAt(spec)))
        assertFalse(isMarked(spec))
        assertEquals(before, LastStep.getInstance(project).mark?.threadId)
        assertEquals(1, preview.built)
    }

    fun testACommentLeftBeforeItsFileClosedIsNotRetried() {
        val (before, inTheSpec) = intoTheSpec(asSteps = false)

        openingAndThenClosingTheSpec(inTheSpec, meanwhile = { openInTheEditorBehind(before) })

        assertFalse(tab.editors.isFileOpen(fileAt(spec)))
        assertFalse(isMarked(spec))
        assertEquals(1, preview.built)
    }

    fun testThePlainTextMarkIsClearedWhenTheFileCloses() {
        val step = specStep()
        navigateTo(step)
        assertTrue(TextOnlyEditorProvider().accept(project, fileAt(spec)))

        tab.editors.closeFile(fileAt(spec))
        tab.settle()

        assertFalse(TextOnlyEditorProvider().accept(project, fileAt(spec)))
    }

    fun testThePlainTextMarkStaysWhileTheFileIsOpenInAnotherSplit() {
        val step = specStep()
        navigateTo(step)
        tab.splitTheFileInFront()
        tab.settle()

        tab.editors.currentWindow!!.closeFile(fileAt(spec))
        tab.settle()

        assertTrue(tab.editors.isFileOpen(fileAt(spec)))
        assertTrue(TextOnlyEditorProvider().accept(project, fileAt(spec)))
    }

    fun testABinaryFileIsNeverRetriedAsPlainText() {
        myFixture.addFileToProject(binary, "\u0000\u0001\u0002")
        val step = agent.add("file" to binary, "order" to 1, "walkthrough" to "tour", "body" to "The logo")
        val shown = user.notifications()

        navigateTo(step)

        assertFalse(isMarked(binary))
        assertEquals(1, shown.size)
    }

    fun testUnloadingThePluginClosesAFileShownAsPlainTextAndForgetsTheMark() {
        val step = specStep()
        navigateTo(step)

        tab.unloadPluginForUpdate()

        assertFalse(tab.editors.isFileOpen(fileAt(spec)))
        assertFalse(TextOnlyEditorProvider().accept(project, fileAt(spec)))
    }

    private class PreviewThatFailsToBuild : FileEditorProvider, DumbAware {
        var built = 0

        override fun accept(project: Project, file: VirtualFile): Boolean = file.extension == "yaml"

        override fun acceptRequiresReadAction(): Boolean = false

        override fun createEditor(project: Project, file: VirtualFile): FileEditor {
            built++
            return Preview(project, file)
        }

        override fun getEditorTypeId(): String = "preview-that-fails-to-build"

        override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
    }

    private class Preview(project: Project, private val shown: VirtualFile) : UserDataHolderBase(), TextEditor {
        private val editor = EditorFactory.getInstance().createEditor(FileDocumentManager.getInstance().getDocument(shown)!!, project, shown, false)

        override fun getEditor(): Editor = editor

        override fun canNavigateTo(navigatable: Navigatable): Boolean = false

        override fun navigateTo(navigatable: Navigatable) {}

        override fun getComponent(): JComponent = editor.component

        override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent

        override fun getName(): String = "Preview"

        override fun setState(state: FileEditorState) {}

        override fun isModified(): Boolean = false

        override fun isValid(): Boolean = true

        override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

        override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

        override fun getFile(): VirtualFile = shown

        override fun dispose() = EditorFactory.getInstance().releaseEditor(editor)
    }
}
