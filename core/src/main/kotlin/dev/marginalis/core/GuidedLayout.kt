package dev.marginalis.core

/** How the tool window's Guided section lays out a walkthrough's steps; navigation follows step numbers in all. */
enum class GuidedLayout(val stored: String, val label: String) {
    BY_STEP_NUMBER("BY_STEP_NUMBER", "By step number"),
    BY_FOLDER("BY_FOLDER", "By folder"),
    BY_FILE("BY_FILE", "By file");

    companion object {
        val DEFAULT = BY_FOLDER

        fun fromStored(stored: String?): GuidedLayout = entries.firstOrNull { it.stored == stored } ?: DEFAULT
    }
}
