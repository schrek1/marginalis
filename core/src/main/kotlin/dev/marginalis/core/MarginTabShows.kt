package dev.marginalis.core

enum class MarginTabShows(val stored: String) {
    ALWAYS("ALWAYS"),
    WITH_SOMETHING_IN_IT("WITH_SOMETHING_IN_IT");

    companion object {
        fun fromStored(stored: String): MarginTabShows = entries.firstOrNull { it.stored == stored } ?: ALWAYS
    }
}
