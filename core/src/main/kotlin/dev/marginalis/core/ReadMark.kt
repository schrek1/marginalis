package dev.marginalis.core

enum class ReadMark {
    READ,
    UNREAD;

    fun applyTo(thread: CommentThread): Boolean = when (this) {
        READ -> thread.markReadByUser()
        UNREAD -> thread.markUnreadByUser()
    }

    companion object {
        fun of(threads: List<CommentThread>): ReadMark? = when {
            threads.any { it.unreadByUserCount() > 0 } -> READ
            threads.all { thread -> thread.messages.all { it.author is Author.User } } -> null
            else -> UNREAD
        }
    }
}
