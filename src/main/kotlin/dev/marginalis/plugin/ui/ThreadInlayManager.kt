package dev.marginalis.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.ui.JBUI
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.store.MarginalisStore

object ThreadInlayManager {

    private val OPEN_INLAYS = Key.create<MutableMap<String, Pair<Inlay<*>, ThreadPanel>>>("marginalis.open.inlays")
    private val LISTENER_INSTALLED = Key.create<Boolean>("marginalis.store.listener")

    /** Set on editors that show a thread's file at other line numbers than the file itself, such as a review diff. */
    val THREAD_LINE = Key.create<(CommentThread) -> Int?>("marginalis.thread.line")

    fun toggle(project: Project, editor: Editor, thread: CommentThread) {
        val open = openInlays(editor)
        open.remove(thread.id)?.let { (inlay, _) ->
            // A document reload disposes inlays behind our back; a stale
            // entry is not an open panel.
            val wasOpen = inlay.isValid
            Disposer.dispose(inlay)
            if (wasOpen) return
        }
        openPanel(project, editor, thread, ensureStored = {})
    }

    fun open(project: Project, editor: Editor, thread: CommentThread, revealing: Message? = null) {
        val panel = openPanel(project, editor, thread, ensureStored = {}) ?: return
        revealing?.let { ApplicationManager.getApplication().invokeLater { panel.reveal(it) } }
    }

    fun openDraft(project: Project, editor: Editor, thread: CommentThread) {
        openPanel(project, editor, thread) {
            val store = MarginalisStore.getInstance(project)
            if (store.threads.byId(thread.id) == null) {
                MarginalisMarkers.attach(project, thread, editor.document)
                store.threads.add(thread)
            }
        }
    }

    private fun openInlays(editor: Editor): MutableMap<String, Pair<Inlay<*>, ThreadPanel>> =
        editor.getUserData(OPEN_INLAYS) ?: mutableMapOf<String, Pair<Inlay<*>, ThreadPanel>>()
            .also { editor.putUserData(OPEN_INLAYS, it) }

    private fun openPanel(project: Project, editor: Editor, thread: CommentThread, ensureStored: () -> Unit): ThreadPanel? {
        val open = openInlays(editor)
        open[thread.id]?.let { (inlay, panel) ->
            // Document reloads dispose inlays behind our back; a stale entry
            // must not veto reopening.
            if (inlay.isValid) return panel
            open.remove(thread.id)
        }

        val panel = ThreadPanel(
            project,
            editor,
            thread,
            ensureStored,
            onClose = { close(editor, thread.id) },
            hostWidth = { inlayWidth(editor) },
        )
        val aboveFirstLine = thread.isFileLevel
        val line = (
            editor.getUserData(THREAD_LINE)?.invoke(thread)
                ?: MarginalisStore.getInstance(project).syncLine(thread)
                ?: 0
            ).coerceAtMost(editor.document.lineCount - 1)
        val offset =
            if (aboveFirstLine) editor.document.getLineStartOffset(0)
            else editor.document.getLineEndOffset(line.coerceAtLeast(0))
        val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
            editor as EditorEx,
            panel,
            EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                true, // relatesToPrecedingText
                aboveFirstLine, // showAbove
                0,
                offset,
            ),
        ) ?: return null
        // The panel sizes from the live viewport width; scrolling fires this
        // too, with the width unchanged.
        editor.scrollingModel.addVisibleAreaListener(
            { event ->
                if (event.newRectangle.width != event.oldRectangle?.width) panel.refresh()
            },
            inlay,
        )
        open[thread.id] = inlay to panel
        installStoreListener(project, editor)
        ApplicationManager.getApplication().invokeLater { panel.focusDefault() }
        return panel
    }

    private fun close(editor: Editor, threadId: String) {
        val open = editor.getUserData(OPEN_INLAYS) ?: return
        open.remove(threadId)?.let { (inlay, _) -> Disposer.dispose(inlay) }
    }

    /**
     * Editor user data outlives the plugin's classloader — anything of ours
     * left behind (panels, inlays, even Key values) pins it after a dynamic unload.
     */
    fun disposeAll() {
        for (editor in EditorFactory.getInstance().allEditors) {
            editor.getUserData(OPEN_INLAYS)?.values?.forEach { (inlay, _) -> Disposer.dispose(inlay) }
            editor.putUserData(OPEN_INLAYS, null)
            editor.putUserData(LISTENER_INSTALLED, null)
            editor.putUserData(THREAD_LINE, null)
        }
    }

    private fun inlayWidth(editor: Editor): Int {
        val viewport = editor.scrollingModel.visibleArea.width
        return (viewport - JBUI.scale(120)).coerceIn(JBUI.scale(360), JBUI.scale(800))
    }

    private fun installStoreListener(project: Project, editor: Editor) {
        if (editor.getUserData(LISTENER_INSTALLED) == true) return
        editor.putUserData(LISTENER_INSTALLED, true)
        val store = MarginalisStore.getInstance(project)
        store.threads.addListener { thread ->
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed) return@invokeLater
                if (store.threads.byId(thread.id) == null || thread.status is ThreadStatus.Resolved) {
                    close(editor, thread.id)
                }
            }
        }
    }
}
