package dev.marginalis.plugin.testing

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.editor.SimpleDiffVirtualFile
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.ui.ThreadGutterIconRenderer
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.diff.ReviewDiffMarkers
import java.awt.Component
import javax.swing.Icon
import javax.swing.JComponent

class ReviewDiffs(private val project: Project, private val disposable: Disposable) {

    fun sideBySide(path: String, before: String, after: String): ReviewDiff =
        show(revision(path, before), revision(path, after)) { context, request -> SimpleDiffViewer(context, request) }

    fun unified(path: String, before: String, after: String): ReviewDiff =
        show(revision(path, before), revision(path, after)) { context, request -> UnifiedDiffViewer(context, request) }

    fun added(path: String, after: String): ReviewDiff =
        show(DiffContentFactory.getInstance().createEmpty(), revision(path, after)) { context, request ->
            SimpleOnesideDiffViewer(context, request)
        }

    fun deleted(path: String, before: String): ReviewDiff =
        show(revision(path, before), DiffContentFactory.getInstance().createEmpty()) { context, request ->
            SimpleOnesideDiffViewer(context, request)
        }

    fun localChanges(path: String, before: String, unified: Boolean): ReviewDiff =
        show(revision(path, before), DiffContentFactory.getInstance().create(project, file(path))) { context, request ->
            if (unified) UnifiedDiffViewer(context, request) else SimpleDiffViewer(context, request)
        }

    fun inATab(
        editors: FileEditorManagerImpl,
        path: String,
        before: String,
        after: String,
        unified: Boolean,
        dataContextOf: (Component) -> DataContext,
    ): ReviewDiff {
        val request = SimpleDiffRequest("Review", revision(path, before), revision(path, after), "Base", "Pull request")
        request.putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, if (unified) UnifiedDiffTool.INSTANCE else SimpleDiffTool.INSTANCE)
        return inATab(editors, request, dataContextOf)
    }

    fun clipboardInATab(editors: FileEditorManagerImpl, before: String, after: String, dataContextOf: (Component) -> DataContext): ReviewDiff {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest("Clipboard", factory.create(before), factory.create(after), "Clipboard", "Selection")
        request.putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, SimpleDiffTool.INSTANCE)
        return inATab(editors, request, dataContextOf)
    }

    private fun inATab(editors: FileEditorManagerImpl, request: SimpleDiffRequest, dataContextOf: (Component) -> DataContext): ReviewDiff {
        editors.openFile(SimpleDiffVirtualFile(request), true, true)
        val tab = editors.selectedEditor!!
        val viewer = { tab.preferredFocusedComponent?.let { dataContextOf(it).getData(DiffDataKeys.DIFF_VIEWER) } as? DiffViewerBase }
        PlatformTestUtil.waitWithEventsDispatching("the review tab never showed a diff", { viewer() != null }, 10)
        return ReviewDiff(project, viewer()!!).also { it.rediff() }
    }

    fun compareFiles(left: String, right: String): ReviewDiff =
        show(DiffContentFactory.getInstance().create(project, file(left)), DiffContentFactory.getInstance().create(project, file(right))) { context, request ->
            SimpleDiffViewer(context, request)
        }

    private fun file(path: String) = project.guessProjectDir()!!.findFileByRelativePath(path)!!

    private fun revision(path: String, text: String): DiffContent =
        DiffContentFactory.getInstance().create(project, text, file(path))

    private fun show(before: DiffContent, after: DiffContent, viewerOf: (DiffContext, SimpleDiffRequest) -> DiffViewerBase): ReviewDiff {
        val context = object : DiffContext() {
            override fun getProject(): Project = this@ReviewDiffs.project
            override fun isWindowFocused(): Boolean = true
            override fun isFocusedInWindow(): Boolean = true
            override fun requestFocusInWindow() {}
        }
        val request = SimpleDiffRequest("Review", before, after, "Base", "Pull request")
        val viewer = viewerOf(context, request)
        ReviewDiffMarkers().onViewerCreated(viewer, context, request)
        return ReviewDiff(project, viewer).also { diff ->
            Disposer.register(disposable) { diff.close() }
            viewer.init()
            diff.rediff()
        }
    }
}

class ReviewDiff(private val project: Project, val viewer: DiffViewerBase) {

    private var rediffs = 0
    private var closed = false
    var laterListenersToldOfClose = false
        private set

    init {
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                rediffs++
            }

            override fun onDispose() {
                laterListenersToldOfClose = true
            }
        })
    }

    val editor: EditorEx
        get() = when (viewer) {
            is UnifiedDiffViewer -> viewer.editor
            is SimpleDiffViewer -> viewer.getEditor(Side.RIGHT)
            is SimpleOnesideDiffViewer -> viewer.editor
            else -> error("no editor in $viewer")
        }

    val baseEditor: EditorEx get() = (viewer as SimpleDiffViewer).getEditor(Side.LEFT)

    fun clickBaseIcon(row: Int) {
        val document = baseEditor.document
        val click = DocumentMarkupModel.forDocument(document, project, false)!!.allHighlighters
            .filter { it.isValid && document.getLineNumber(it.startOffset) == row - 1 }
            .firstNotNullOf { it.gutterIconRenderer as? ThreadGutterIconRenderer }.clickAction
        click.actionPerformed(TestActionEvent.createTestEvent(click, editorContext(baseEditor)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun basePanels(): List<ThreadPanel> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return UIUtil.findComponentsOfType(baseEditor.contentComponent, ThreadPanel::class.java)
    }

    fun editorContext(on: EditorEx = editor): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, on)
        .build()

    fun lookAtTheBase() {
        (viewer as SimpleDiffViewer).currentSide = Side.LEFT
    }

    fun putCaretOnRow(row: Int, on: EditorEx = editor) {
        on.caretModel.moveToLogicalPosition(LogicalPosition(row - 1, 0))
    }

    fun rediff() {
        val before = rediffs
        viewer.rediff(true)
        PlatformTestUtil.waitWithEventsDispatching("the diff never landed", { rediffs > before }, 10)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun iconRows(): List<Int> = icons().map { it.first }.sorted()

    fun textOfRow(row: Int): String {
        val document = editor.document
        return document.getText(TextRange(document.getLineStartOffset(row - 1), document.getLineEndOffset(row - 1)))
    }

    fun iconAt(row: Int): Icon = icons().single { it.first == row }.second.icon

    fun tooltipAt(row: Int): String = icons().single { it.first == row }.second.tooltipText

    fun clickIcon(row: Int) {
        val click = icons().single { it.first == row }.second.clickAction
        click.actionPerformed(TestActionEvent.createTestEvent(click, editorContext()))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun panels(): List<ThreadPanel> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return UIUtil.findComponentsOfType(editor.contentComponent, ThreadPanel::class.java)
    }

    fun panelRows(): List<Int> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            .filter { it.isValid && (it.renderer as? JComponent)?.let(::holdsAThreadPanel) == true }
            .map { editor.document.getLineNumber(it.offset) + 1 }
            .sorted()
    }

    fun iconHighlighters(): List<RangeHighlighter> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor.markupModel.allHighlighters.filter { it.isValid && it.gutterIconRenderer is ThreadGutterIconRenderer }
    }

    private fun holdsAThreadPanel(component: JComponent) =
        UIUtil.findComponentsOfType(component, ThreadPanel::class.java).isNotEmpty()

    fun close() {
        if (closed) return
        closed = true
        viewer.dispose()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun icons(): List<Pair<Int, ThreadGutterIconRenderer>> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        if (editor.isDisposed) return emptyList()
        return editor.markupModel.allHighlighters
            .filter { it.isValid }
            .mapNotNull { highlighter ->
                (highlighter.gutterIconRenderer as? ThreadGutterIconRenderer)
                    ?.let { editor.document.getLineNumber(highlighter.startOffset) + 1 to it }
            }
    }
}
