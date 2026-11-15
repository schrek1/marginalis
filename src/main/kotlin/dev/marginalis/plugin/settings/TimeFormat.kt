package dev.marginalis.plugin.settings

import com.intellij.DynamicBundle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class TimeFormat(val stored: String, val label: String) {
    AUTO("AUTO", "Auto (system)"),
    TWELVE_HOUR("12", "12-hour"),
    TWENTY_FOUR_HOUR("24", "24-hour");

    fun formatter(): DateTimeFormatter =
        when (this) {
            TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", DynamicBundle.getLocale())
            TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm")
            AUTO -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        }.withZone(ZoneId.systemDefault())

    companion object {
        fun fromStored(stored: String): TimeFormat = entries.firstOrNull { it.stored == stored } ?: AUTO
    }
}
