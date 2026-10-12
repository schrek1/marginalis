package dev.marginalis.plugin.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.marginalis.core.Addressee
import dev.marginalis.core.FileTurns
import dev.marginalis.core.CommentThread
import dev.marginalis.core.HandBack
import dev.marginalis.core.LiveAgent
import dev.marginalis.core.LiveThread
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.ThreadStore
import dev.marginalis.core.ThreadsCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class MarginalisStore(private val project: Project) : Disposable {

    val threads = ThreadStore()

    val fileTurns = FileTurns()

    val handBack = HandBack.over(threads)

    fun recordHandBack() {
        handBack.record()
        scheduleSave()
    }

    private val savePending = AtomicBoolean()

    fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { if (savePending.getAndSet(false) && !project.isDisposed) MarginalisPersistence.save(project, snapshot()) },
            SAVE_COALESCING_MILLIS, TimeUnit.MILLISECONDS,
        )
    }

    fun clearAll(): List<CommentThread> = threads.clear().also { scheduleSave() }

    private val liveThreads = ConcurrentHashMap.newKeySet<String>()

    private val notLiveThreads = ConcurrentHashMap.newKeySet<String>()

    private val liveTargets = ConcurrentHashMap<String, String>()

    init {
        threads.addListener { changed ->
            if (LiveThread.isOver(threads.byId(changed.id))) endLive(changed)
        }
    }

    val liveByDefault: Boolean
        get() = LiveDefault.getInstance(project).enabled

    fun isLive(thread: CommentThread): Boolean =
        LiveThread.isLive(thread, liveByDefault, switchedOn = liveThreads, switchedOff = notLiveThreads)

    fun setLiveByDefault(enabled: Boolean) {
        val wasLive = threads.all().filter(::isLive)
        LiveDefault.getInstance(project).enabled = enabled
        liveThreads.clear()
        notLiveThreads.clear()
        wasLive.filterNot(::isLive).forEach(::endLive)
    }

    fun liveAgentKey(thread: CommentThread, to: Addressee?): String? =
        LiveAgent.keyOf(thread.messages, to, handBack.waitingAgents) ?: liveTargets[thread.id]

    private fun liveTarget(thread: CommentThread, to: Addressee?): String? =
        liveAgentKey(thread, to)?.also { liveTargets[thread.id] = it }

    fun setLive(thread: CommentThread, live: Boolean, to: Addressee?) {
        if (live) {
            liveThreads.add(thread.id)
            notLiveThreads.remove(thread.id)
            LiveThread.goLive(handBack, thread, liveTarget(thread, to))
        } else {
            endLive(thread)
            notLiveThreads.add(thread.id)
        }
        threads.notifyChanged(thread)
    }

    private fun endLive(thread: CommentThread) {
        liveThreads.remove(thread.id)
        notLiveThreads.remove(thread.id)
        liveTargets.remove(thread.id)
        handBack.forgetLive(thread.id)
    }

    fun wakeLive(thread: CommentThread, to: Addressee?) {
        if (!isLive(thread)) return
        LiveThread.submit(handBack, thread, liveTarget(thread, to))
    }

    fun snapshot() = ThreadsCodec.Document(threads.all(), handBack.lastAt, threads.deletedRelays)

    override fun dispose() {
        handBack.close()
        if (savePending.getAndSet(false)) MarginalisPersistence.save(project, snapshot())
    }

    // Deliberately not persisted: a draft is a thought in progress, not a record.
    val drafts = ConcurrentHashMap<String, Draft>()

    data class Draft(val text: String, val to: Addressee?)

    private val markers = ConcurrentHashMap<String, RangeHighlighter>()

    // Deliberately apart from markers: a file glyph is a place to click, never
    // an anchor — nothing reads a line off it, and its threads follow the file.
    private val fileGlyphs = ConcurrentHashMap<String, RangeHighlighter>()

    fun fileGlyphOf(file: String): RangeHighlighter? = fileGlyphs[file]

    fun setFileGlyph(file: String, highlighter: RangeHighlighter) {
        fileGlyphs[file] = highlighter
    }

    fun removeFileGlyph(file: String): RangeHighlighter? = fileGlyphs.remove(file)

    fun clearFileGlyphs(): List<RangeHighlighter> {
        val all = fileGlyphs.values.toList()
        fileGlyphs.clear()
        return all
    }

    fun markerOf(thread: CommentThread): RangeHighlighter? = markers[thread.id]

    fun setMarker(thread: CommentThread, highlighter: RangeHighlighter) {
        markers[thread.id] = highlighter
    }

    fun removeMarker(thread: CommentThread): RangeHighlighter? = markers.remove(thread.id)

    fun syncLine(thread: CommentThread): Int? {
        val marker = markers[thread.id]
        if (marker != null) {
            if (marker.isValid) {
                thread.line = marker.document.getLineNumber(marker.startOffset)
            } else if (thread.status is ThreadStatus.Open) {
                thread.markOrphaned()
                endLive(thread)
            }
        }
        return thread.line
    }

    fun syncLines() {
        threads.all().forEach { syncLine(it) }
    }

    companion object {
        private const val SAVE_COALESCING_MILLIS = 100L

        fun getInstance(project: Project): MarginalisStore = project.service()
    }
}
