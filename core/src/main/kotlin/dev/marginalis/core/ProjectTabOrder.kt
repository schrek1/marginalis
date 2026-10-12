package dev.marginalis.core

enum class ProjectTabOrder(val stored: String, val label: String, val newOnTop: Boolean) {
    OLDEST_FIRST("OLDEST_FIRST", "Oldest first", newOnTop = false),
    NEWEST_FIRST("NEWEST_FIRST", "Newest first", newOnTop = true),
    RECENT_ACTIVITY("RECENT_ACTIVITY", "Recent activity", newOnTop = true);

    val comparator: Comparator<CommentThread>
        get() = when (this) {
            OLDEST_FIRST -> compareBy<CommentThread> { it.createdAt }.thenBy { it.id }
            NEWEST_FIRST -> compareByDescending<CommentThread> { it.createdAt }.thenBy { it.id }
            RECENT_ACTIVITY ->
                compareByDescending<CommentThread> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id }
        }

    companion object {
        val DEFAULT = OLDEST_FIRST

        fun fromStored(stored: String?): ProjectTabOrder = entries.firstOrNull { it.stored == stored } ?: DEFAULT
    }
}
