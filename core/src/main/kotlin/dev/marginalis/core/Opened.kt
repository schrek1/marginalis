package dev.marginalis.core

sealed interface Opened {

    fun isListedIn(threads: Collection<CommentThread>): Boolean

    data class MarginThread(val id: String) : Opened {
        override fun isListedIn(threads: Collection<CommentThread>) = threads.any { it.id == id }
    }

    data class File(val path: String) : Opened {
        override fun isListedIn(threads: Collection<CommentThread>) = threads.any { it.file == path }
    }

    data object ProjectThreads : Opened {
        override fun isListedIn(threads: Collection<CommentThread>) = threads.any { it.isProjectLevel }
    }

    companion object {
        fun onLine(file: String, line: Int, threads: Collection<CommentThread>, lineOf: (CommentThread) -> Int?): Opened =
            threads
                .filter { it.file == file && !it.isFileLevel && it.status is ThreadStatus.Open && lineOf(it) == line }
                .minByOrNull { it.createdAt }
                ?.let { MarginThread(it.id) }
                ?: File(file)
    }
}
