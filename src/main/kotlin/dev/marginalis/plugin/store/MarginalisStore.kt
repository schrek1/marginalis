package dev.marginalis.plugin.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import dev.marginalis.core.Addressee
import dev.marginalis.core.FileTurns
import dev.marginalis.core.CommentThread
import dev.marginalis.core.HandBack
import dev.marginalis.core.LiveAgent
import dev.marginalis.core.LiveSwitches
import dev.marginalis.core.LiveThread
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.ThreadStore
import dev.marginalis.core.ThreadsCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    private val liveSwitches = LiveSwitches()

    private val liveListeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        threads.addListener { changed ->
            orphanIfItsCodeIsGone(changed)
            val current = threads.byId(changed.id)
            when {
                current == null -> forgetLive(changed)
                LiveThread.isOver(current) -> endLive(current)
                liveSwitches.resumed(current.id) && isLive(current) ->
                    LiveThread.goLive(handBack, current, liveTarget(current, null))
            }
        }
    }

    fun rehydrate(thread: CommentThread) {
        threads.addSilently(thread)
        if (LiveThread.isOver(thread)) liveSwitches.end(thread.id)
    }

    fun onLiveChange(parent: Disposable, listener: () -> Unit) {
        liveListeners += listener
        Disposer.register(parent) { liveListeners -= listener }
    }

    val liveListenerCount: Int get() = liveListeners.size

    private fun announceLiveChange() = liveListeners.forEach { it() }

    val isProjectLive: Boolean
        get() = ProjectLive.getInstance(project).isOn

    fun isLive(thread: CommentThread): Boolean = liveSwitches.isLive(thread, isProjectLive)

    fun setProjectLive(on: Boolean) {
        val wasLive = isProjectLive
        ProjectLive.getInstance(project).turn(on)
        reconsiderLive(wasLive)
    }

    private fun followLiveByDefault(settingsWasLive: Boolean) {
        if (ProjectLive.getInstance(project).followsSettings) reconsiderLive(wasLive = settingsWasLive)
    }

    private fun reconsiderLive(wasLive: Boolean) {
        val turned = liveSwitches.turnProject(threads.all(), wasLive, isProjectLive)
        turned.noLongerLive.forEach { handBack.forgetLive(it.id) }
        turned.nowLive.forEach { LiveThread.goLive(handBack, it, liveTarget(it, null)) }
        announceLiveChange()
    }

    fun liveAgentKey(thread: CommentThread, to: Addressee?): String? =
        LiveAgent.keyOf(thread.messages, to, handBack.waitingAgents) ?: liveSwitches.targetOf(thread.id)

    private fun liveTarget(thread: CommentThread, to: Addressee?): String? =
        liveAgentKey(thread, to)?.also { liveSwitches.target(thread.id, it) }

    fun setLive(thread: CommentThread, live: Boolean, to: Addressee?) {
        liveSwitches.switch(thread.id, live)
        if (live) LiveThread.goLive(handBack, thread, liveTarget(thread, to)) else withdrawLive(thread)
        threads.notifyChanged(thread)
        announceLiveChange()
    }

    private fun withdrawLive(thread: CommentThread) {
        liveSwitches.withdraw(thread.id)
        handBack.forgetLive(thread.id)
    }

    private fun endLive(thread: CommentThread) {
        liveSwitches.end(thread.id)
        handBack.forgetLive(thread.id)
    }

    private fun forgetLive(thread: CommentThread) {
        liveSwitches.forget(thread.id)
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

    fun isDraft(thread: CommentThread): Boolean = threads.byId(thread.id) == null && thread.messages.isEmpty()

    fun syncLine(thread: CommentThread): Int? {
        orphanIfItsCodeIsGone(thread)
        markers[thread.id]?.let { thread.line = it.document.getLineNumber(it.startOffset) }
        return thread.line
    }

    @Synchronized
    fun orphanIfItsCodeIsGone(thread: CommentThread): Boolean {
        val marker = markers[thread.id]
        if (marker == null || marker.isValid) return false
        val orphans = thread.status is ThreadStatus.Open
        if (orphans) {
            thread.markOrphaned()
            endLive(thread)
        }
        markers.remove(thread.id, marker)
        return orphans
    }

    fun syncLines() {
        threads.all().forEach { syncLine(it) }
    }

    companion object {
        private const val SAVE_COALESCING_MILLIS = 100L

        fun getInstance(project: Project): MarginalisStore = project.service()

        fun liveByDefaultChanged(settingsWasLive: Boolean) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) getInstance(project).followLiveByDefault(settingsWasLive)
            }
        }
    }
}
