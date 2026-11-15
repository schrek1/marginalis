package dev.marginalis.plugin.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.DiffEditorViewer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import dev.marginalis.core.CommentThread
import dev.marginalis.core.ReviewDiffFiles
import dev.marginalis.core.StepMark
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.ui.LastStep
import dev.marginalis.plugin.ui.ThreadInlayManager
import dev.marginalis.plugin.ui.WalkthroughNavigator
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CancellationException
import javax.swing.Timer

object ReviewDiffSteps {

    private val LOG = logger<ReviewDiffSteps>()
    private const val ARRIVAL_TIMEOUT_MILLIS = 10_000
    private const val ARRIVAL_TIMEOUT_IN_TESTS_MILLIS = 600_000

    private val platformReviewFiles: (DiffEditorViewer) -> ReviewFiles? = { review -> ReviewDiffFileList.of(review) }
    private val underWay = mutableListOf<ArrivingStep>()
    private var reviewFilesOf: (DiffEditorViewer) -> ReviewFiles? = platformReviewFiles
    private var arrivalTimeoutMillis = ARRIVAL_TIMEOUT_MILLIS

    fun isLoadingAStepIn(project: Project, editor: Editor): Boolean =
        underWay.isNotEmpty() && reviewShowing(project, editor)?.let { review -> underWay.any { it.context === review.context } } == true

    fun switchTo(project: Project, from: Editor, step: CommentThread, leaving: CommentThread?, onShown: () -> Unit): Boolean {
        val wanted = MarginalisSettings.getInstance().state.keepStepsInReviewAcrossFiles && step.order != null
        if (!wanted && underWay.isEmpty()) return false
        val review = reviewShowing(project, from) ?: return false
        if (wanted && switchReview(project, review, from, step, leaving, onShown)) return true
        underWay.filter { it.context === review.context }.forEach(ArrivingStep::finish)
        return false
    }

    private fun switchReview(
        project: Project,
        review: DiffEditorViewer,
        from: Editor,
        step: CommentThread,
        leaving: CommentThread?,
        onShown: () -> Unit,
    ): Boolean {
        val file = step.file ?: return false
        val root = project.guessProjectDir()?.takeIf { it.findFileByRelativePath(file) != null } ?: return false
        val fileLeft = from.getUserData(ThreadInlayManager.SHOWN_THREADS)?.file ?: return false
        if (review.javaClass.name != ReviewDiffFileList.PROCESSOR_CLASS_NAME) return false
        val loading = underWay.firstOrNull { it.context === review.context }
        if (loading?.file == file) {
            loading.retarget(step, fileLeft, leaving?.id, onShown)
            return true
        }
        val files = throughTheSeam(file) { reviewFilesOf(review) } ?: return false
        val (paths, shownIndex) = throughTheSeam(file) { files.absolutePaths to files.shownIndex } ?: return false
        val index = if (loading != null) ReviewDiffFiles.indexOf(file, root.path, paths)
        else ReviewDiffFiles.switchTarget(file, root.path, paths, shownIndex)
        if (index == null) return false
        val arriving = ArrivingStep(project, review.context, file, fileLeft, leaving?.id, step, onShown)
        arriving.start(review.disposable)
        var shown = false
        try {
            shown = throughTheSeam(file) { files.show(index) } != null
        } finally {
            if (!shown) arriving.finish()
        }
        return shown
    }

    fun arrivingIn(context: DiffContext, path: String, canShowIt: Boolean): ArrivingStep? {
        val arriving = underWay.firstOrNull { it.context === context && it.awaitsAViewer } ?: return null
        when {
            path == arriving.file && canShowIt -> return arriving.also { it.claim() }
            path == arriving.file -> arriving.tabInstead()
            path != arriving.fileLeft -> arriving.finish()
        }
        return null
    }

    fun disposeAll() {
        underWay.toList().forEach(ArrivingStep::finish)
    }

    @TestOnly
    fun hasStepsUnderWay(): Boolean = underWay.isNotEmpty()

    @TestOnly
    fun timeOutArrivalsInTests() {
        underWay.toList().forEach(ArrivingStep::timeOutIfWaiting)
    }

    @TestOnly
    fun useInTests(disposable: Disposable, reviewFilesOf: (DiffEditorViewer) -> ReviewFiles? = platformReviewFiles) {
        arrivalTimeoutMillis = ARRIVAL_TIMEOUT_IN_TESTS_MILLIS
        this.reviewFilesOf = reviewFilesOf
        Disposer.register(disposable) {
            this.arrivalTimeoutMillis = ARRIVAL_TIMEOUT_MILLIS
            this.reviewFilesOf = platformReviewFiles
        }
    }

    private fun reviewShowing(project: Project, editor: Editor): DiffEditorViewer? =
        FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<DiffEditorViewerFileEditor>()
            .firstOrNull { editor in it.embeddedEditors }
            ?.editorViewer
            ?.takeIf { !it.disposable.isDisposed }

    private fun <T : Any> throughTheSeam(file: String, call: () -> T?): T? = try {
        call()
    } catch (e: LinkageError) {
        tabInsteadAfter(e, file)
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        tabInsteadAfter(e, file)
    }

    private fun tabInsteadAfter(failure: Throwable, file: String): Nothing? {
        LOG.warn("The pull request review could not show $file; the walkthrough step opens in its tab", failure)
        return null
    }

    class ArrivingStep internal constructor(
        private val project: Project,
        val context: DiffContext,
        val file: String,
        val fileLeft: String,
        leavingId: String?,
        private var step: CommentThread,
        private var onShown: () -> Unit,
    ) {
        private var leftBehind = listOfNotNull(leavingId?.let { fileLeft to it })
        var awaitsAViewer = true
            private set
        private var done = false
        private var walkthroughWasOn: StepMark? = null
        private val expiry = Timer(arrivalTimeoutMillis) { timedOut() }.apply { isRepeats = false }
        private var reviewWatch: ReviewWatch? = null

        internal fun start(review: CheckedDisposable) {
            val replaced = underWay.filter { it.context === context }
            leftBehind = replaced.flatMap { it.leftBehind } + leftBehind
            replaced.forEach(ArrivingStep::finish)
            underWay += this
            walkthroughWasOn = LastStep.getInstance(project).mark
            reviewWatch = ReviewWatch().also { Disposer.register(review, it) }
            expiry.start()
        }

        internal fun retarget(step: CommentThread, fileLeft: String, leavingId: String?, onShown: () -> Unit) {
            this.step = step
            this.onShown = onShown
            leftBehind = leftBehind + listOfNotNull(leavingId?.let { fileLeft to it })
            walkthroughWasOn = LastStep.getInstance(project).mark
        }

        internal fun claim() {
            awaitsAViewer = false
            expiry.stop()
        }

        fun stillArriving() {
            if (done) return
            awaitsAViewer = true
            expiry.restart()
        }

        fun landIn(editor: Editor) {
            if (done) return
            finish()
            if (walkthroughMovedOn()) return
            if (!WalkthroughNavigator.navigateWithin(editor, project, step, beforeMoving = ::shown)) {
                WalkthroughNavigator.navigateTo(project, step, onShown = ::shown)
            }
        }

        fun threadsLeftBehind(): List<String> = leftBehind.map { (_, threadId) -> threadId }

        private fun shown() {
            leftBehind.forEach { (path, threadId) -> ReviewDiffMarkers.forgetOpen(context, path, threadId) }
            onShown()
        }

        fun tabInstead() {
            if (done) return
            awaitsAViewer = false
            expiry.stop()
            ApplicationManager.getApplication().invokeLater({
                finish()
                if (!project.isDisposed && !walkthroughMovedOn()) WalkthroughNavigator.navigateTo(project, step, onShown = ::shown)
            }, { done })
        }

        fun finish() {
            if (done) return
            done = true
            underWay -= this
            stopWaiting()
        }

        private fun walkthroughMovedOn(): Boolean = LastStep.getInstance(project).mark != walkthroughWasOn

        internal fun timeOutIfWaiting() {
            if (expiry.isRunning) timedOut()
        }

        private fun timedOut() {
            if (walkthroughMovedOn()) return finish()
            LOG.warn("The pull request review did not show $file in time; the walkthrough step opens in its tab")
            tabInstead()
        }

        private fun stopWaiting() {
            expiry.stop()
            val watch = reviewWatch ?: return
            reviewWatch = null
            Disposer.dispose(watch)
        }

        private inner class ReviewWatch : Disposable {
            override fun dispose() {
                if (reviewWatch === this) {
                    reviewWatch = null
                    finish()
                }
            }
        }
    }
}
