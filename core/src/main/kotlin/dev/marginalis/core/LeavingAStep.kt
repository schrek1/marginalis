package dev.marginalis.core

/** What a thread's own step arrows do with the step you leave. */
enum class LeavingAStep(val stored: String) {
    FOLD("FOLD"),
    KEEP_OPEN("KEEP_OPEN");

    companion object {
        fun fromStored(stored: String): LeavingAStep = entries.firstOrNull { it.stored == stored } ?: FOLD
    }
}
