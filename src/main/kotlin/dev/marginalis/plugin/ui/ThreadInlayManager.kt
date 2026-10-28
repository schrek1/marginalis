package dev.marginalis.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
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
    private val STORE_SUBSCRIPTION = Key.create<Disposable>("marginalis.store.subscription")

    val SHOWN_THREADS = Key.create<ShownThreads>("marginalis.shown.threads")

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

    fun openThreadIds(editor: Editor): List<String> =
        editor.getUserData(OPEN_INLAYS)?.filterValues { (inlay, _) -> inlay.isValid }?.keys?.toList().orEmpty()

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

        val line = lineOf(project, editor, thread)?.coerceAtMost(editor.document.lineCount - 1) ?: return null
        val panel = ThreadPanel(
            project,
            editor,
            thread,
            ensureStored,
            onClose = { close(editor, thread.id) },
            hostWidth = { inlayWidth(editor) },
        )
        val aboveFirstLine = thread.isFileLevel
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

    private fun lineOf(project: Project, editor: Editor, thread: CommentThread): Int? {
        val shown = editor.getUserData(SHOWN_THREADS)
        return if (shown != null) shown.lineOf(thread) else MarginalisStore.getInstance(project).syncLine(thread) ?: 0
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
            editor.getUserData(STORE_SUBSCRIPTION)?.let(Disposer::dispose)
            editor.putUserData(SHOWN_THREADS, null)
        }
    }

    private fun inlayWidth(editor: Editor): Int {
        val viewport = editor.scrollingModel.visibleArea.width
        return (viewport - JBUI.scale(120)).coerceIn(JBUI.scale(360), JBUI.scale(800))
    }

    private fun installStoreListener(project: Project, editor: Editor) {
        if (editor.getUserData(STORE_SUBSCRIPTION) != null) return
        val store = MarginalisStore.getInstance(project)
        val listening = store.threads.observe { thread ->
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed) return@invokeLater
                if (store.threads.byId(thread.id) == null || thread.status is ThreadStatus.Resolved) {
                    close(editor, thread.id)
                }
            }
        }
        val subscription = Disposable {
            listening.close()
            editor.putUserData(STORE_SUBSCRIPTION, null)
        }
        editor.putUserData(STORE_SUBSCRIPTION, subscription)
        EditorUtil.disposeWithEditor(editor, subscription)
    }
}

class ShownThreads private constructor(val file: String, val lineOf: (CommentThread) -> Int?) {
    companion object {
        fun at(file: String, lineOf: (CommentThread) -> Int?) = ShownThreads(file, lineOf)

        fun none(file: String) = ShownThreads(file) { null }
    }
}
