package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import dev.marginalis.core.LeavingAStep
import dev.marginalis.plugin.settings.MarginalisSettings

object SteppingChoice {

    fun keepTheStepYouLeaveOpen(disposable: Disposable) {
        val settings = MarginalisSettings.getInstance()
        val before = settings.leavingAStep
        settings.leavingAStep = LeavingAStep.KEEP_OPEN
        Disposer.register(disposable) { settings.leavingAStep = before }
    }
}
