package dev.marginalis.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import dev.marginalis.core.AnchorPolicy
import dev.marginalis.core.CommentThread
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.store.MarginalisPersistence
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.CoalescedEdtRunner
import dev.marginalis.plugin.ui.FileTurn
import dev.marginalis.plugin.ui.MarginalisMarkers
import dev.marginalis.plugin.ui.tab.ProjectTab

class MarginalisStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val store = MarginalisStore.getInstance(project)

        store.threads.addListener { thread ->
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                syncMarker(project, thread)
                thread.file?.let { FileTurn.track(project, it) }
            }
            store.scheduleSave()
        }
        refreshTabOnChange(project, store)

        val loaded = MarginalisPersistence.load(project)
        store.handBack.restore(loaded.handedBackAt)
        store.threads.restoreDeletedRelays(loaded.deletedRelays)
        val persisted = loaded.threads
        if (persisted.isNotEmpty()) {
            // Finding and loading the files touches the disk: on the EDT that froze a slow file system for seconds, and
            // a VFS event it raised then held the Margin tab's opening for 30 s.
            val sources = readAction { persisted.map { sourceOf(project, it) } }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                for ((thread, source) in persisted.zip(sources)) {
                    rehydrate(project, thread, source)
                    store.threads.addSilently(thread)
                }
                // Attach assigns solo icons; group shared lines per file.
                val files = persisted.mapNotNull { it.file }.distinct()
                files.forEach { MarginalisMarkers.refreshIcons(project, it) }
                files.forEach { FileTurn.track(project, it) }
                // One notification after the silent bulk load refreshes every UI surface.
                persisted.lastOrNull()?.let { store.threads.notifyChanged(it) }
            }
        }
        // Queued behind the persisted threads: whether the tab opens depends on them.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) ProjectTab.openOnStartup(project)
        }
    }

    private fun refreshTabOnChange(project: Project, store: MarginalisStore) {
        val refresh = CoalescedEdtRunner(project) { ProjectTab.refreshPresentation(project) }
        store.threads.addListener { refresh.request() }
    }

    private class Source(val file: VirtualFile?, val document: Document?)

    // Read action. Only an open line thread needs its document.
    private fun sourceOf(project: Project, thread: CommentThread): Source? {
        val path = thread.file ?: return null
        val vFile = project.guessProjectDir()?.findFileByRelativePath(path)
        val needsDocument = !thread.isFileLevel && thread.status is ThreadStatus.Open
        return Source(vFile, if (needsDocument) vFile?.let { FileDocumentManager.getInstance().getDocument(it) } else null)
    }

    // EDT. An orphaned line thread stays orphaned: moving a line anchor is the
    // agent's call (comment_reanchor), not a guess made at startup.
    private fun rehydrate(project: Project, thread: CommentThread, source: Source?) {
        if (source == null) return
        if (thread.isFileLevel) {
            when {
                source.file == null -> thread.markOrphaned()
                thread.status is ThreadStatus.Orphaned -> thread.reopen()
            }
            return
        }
        if (thread.status !is ThreadStatus.Open) return
        reanchor(project, thread, source.document)
    }

    private fun reanchor(project: Project, thread: CommentThread, document: Document?) {
        if (document == null) {
            thread.markOrphaned()
            return
        }
        val found = AnchorPolicy.findAnchor(
            lineCount = document.lineCount,
            lineTextAt = { lineText(document, it) },
            nearLine = thread.line ?: 0,
            anchorText = thread.anchorText ?: "",
            segment = thread.segment,
        )
        if (found == null) {
            thread.markOrphaned()
            return
        }
        thread.line = found.line
        MarginalisMarkers.attach(project, thread, document)
    }

    private fun lineText(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

    // Resolved threads carry no marker: after edits a stale checkmark drifts
    // onto unrelated lines. Always refresh icons — with several threads on a
    // line, the combined icon's owner may have changed.
    private fun syncMarker(project: Project, thread: CommentThread) {
        val store = MarginalisStore.getInstance(project)
        val marker = store.markerOf(thread)
        when {
            store.threads.byId(thread.id) == null || thread.status is ThreadStatus.Resolved -> {
                if (store.threads.byId(thread.id) == null) store.drafts.remove(thread.id)
                if (marker != null) {
                    if (marker.isValid) {
                        DocumentMarkupModel.forDocument(marker.document, project, false)
                            ?.removeHighlighter(marker)
                    }
                    store.removeMarker(thread)
                }
            }

            thread.line == null -> {}

            thread.status is ThreadStatus.Open && (marker == null || !marker.isValid) -> {
                val base = project.guessProjectDir() ?: return
                val vFile = thread.file?.let { base.findFileByRelativePath(it) } ?: return
                val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return
                MarginalisMarkers.attach(project, thread, document)
            }
        }
        thread.file?.let { MarginalisMarkers.refreshIcons(project, it) }
    }
}
