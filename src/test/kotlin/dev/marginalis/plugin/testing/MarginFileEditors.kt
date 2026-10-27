package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.lang.CompoundRuntimeException
import dev.marginalis.plugin.ui.tab.MarginFile
import java.util.concurrent.CopyOnWriteArrayList

// The Margin tab never has a text editor of its own. Only the light project's editor manager makes one, when something
// opens the tab after the test's editor manager is gone, and the platform's leak report doesn't say where that came from.
class MarginFileEditors {

    private val openedAt = CopyOnWriteArrayList<Throwable>()

    private var watching: Disposable? = null

    fun watch() {
        val disposable = Disposer.newDisposable("Margin file editors")
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (FileDocumentManager.getInstance().getFile(event.editor.document) is MarginFile) {
                    openedAt += Throwable("${event.editor} was opened here")
                }
            }
        }, disposable)
        watching = disposable
    }

    fun namingTheirOpeners(tearDown: () -> Unit) {
        try {
            tearDown()
        } catch (failure: Throwable) {
            reported(failure).forEach { reported -> openedAt.forEach(reported::addSuppressed) }
            throw failure
        } finally {
            watching?.let(Disposer::dispose)
            watching = null
            openedAt.clear()
        }
    }

    // A teardown's failures arrive bundled, and the bundle prints only what it holds, never its own suppressed ones.
    private fun reported(failure: Throwable): List<Throwable> =
        (failure as? CompoundRuntimeException)?.exceptions?.flatMap(::reported) ?: listOf(failure)
}
