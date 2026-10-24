package dev.marginalis.plugin.testing

import com.intellij.openapi.util.Disposer
import dev.marginalis.core.LongThreadFolding
import dev.marginalis.plugin.settings.MarginalisSettings

class LongThreadSettings(private val page: SettingsPage) {

    fun openWith(label: String) = page.choose(LongThreadFolding.FOLD_EVERY_READ_MESSAGE, label)

    fun alwaysShowTheFirstMessage(on: Boolean) = page.tick("Always show the first message", on)

    companion object {
        fun change(edit: LongThreadSettings.() -> Unit) {
            val pageOpen = Disposer.newDisposable()
            try {
                val page = SettingsPage.open(pageOpen)
                LongThreadSettings(page).edit()
                page.apply()
            } finally {
                Disposer.dispose(pageOpen)
            }
        }

        fun restoreDefaults() {
            val settings = MarginalisSettings.getInstance()
            settings.longThreadFolding = LongThreadFolding.FOLD_EVERY_READ_MESSAGE
            settings.state.keepFirstMessageOpen = false
            settings.state.askForSummaryLine = true
        }
    }
}
