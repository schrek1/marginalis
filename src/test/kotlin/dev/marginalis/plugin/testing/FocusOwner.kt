package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.ExpirableRunnable
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.impl.IdeFocusManagerHeadless
import com.intellij.testFramework.replaceService
import java.awt.Component
import java.awt.Window
import javax.swing.JComponent

class FocusOwner private constructor(private val owner: Component) : IdeFocusManager() {

    private val headless = IdeFocusManagerHeadless.INSTANCE

    override fun getFocusOwner(): Component = owner

    override fun requestFocus(c: Component, forced: Boolean): ActionCallback = headless.requestFocus(c, forced)

    override fun getFocusTargetFor(comp: JComponent): JComponent? = headless.getFocusTargetFor(comp)

    override fun doWhenFocusSettlesDown(runnable: Runnable) = headless.doWhenFocusSettlesDown(runnable)

    override fun doWhenFocusSettlesDown(runnable: Runnable, modality: ModalityState) = headless.doWhenFocusSettlesDown(runnable, modality)

    override fun doWhenFocusSettlesDown(runnable: ExpirableRunnable) = headless.doWhenFocusSettlesDown(runnable)

    override fun getFocusedDescendantFor(comp: Component): Component? = headless.getFocusedDescendantFor(comp)

    override fun isFocusTransferEnabled(): Boolean = headless.isFocusTransferEnabled

    override fun runOnOwnContext(context: DataContext, runnable: Runnable) = headless.runOnOwnContext(context, runnable)

    override fun getLastFocusedFor(frame: Window?): Component? = headless.getLastFocusedFor(frame)

    override fun getLastFocusedFrame(): IdeFrame? = headless.lastFocusedFrame

    override fun getLastFocusedIdeWindow(): Window? = headless.lastFocusedIdeWindow

    override fun toFront(c: JComponent) = headless.toFront(c)

    companion object {
        fun on(component: Component, disposable: Disposable) {
            ApplicationManager.getApplication().replaceService(IdeFocusManager::class.java, FocusOwner(component), disposable)
        }
    }
}
