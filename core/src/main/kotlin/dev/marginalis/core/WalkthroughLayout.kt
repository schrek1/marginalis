package dev.marginalis.core

enum class WalkthroughLayout(val title: String) {
    BY_STEP_NUMBER("By Step Number"),
    BY_FOLDER("By Folder"),
    BY_FILE("By File");

    companion object {
        val DEFAULT = BY_STEP_NUMBER

        fun fromStored(stored: String?): WalkthroughLayout = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}
