package dev.marginalis.core

import java.util.concurrent.ConcurrentHashMap

class FileTurns {
    private val byPath = ConcurrentHashMap<String, Turn>()

    fun isEmpty(): Boolean = byPath.isEmpty()

    fun of(path: String): Turn? = byPath[path]

    fun paths(): Set<String> = byPath.keys.toSet()

    fun track(path: String, threads: List<CommentThread>): Boolean {
        val turn = Turn.of(threads.filter { it.file == path && !it.awaitsAgreementAnswer })
        val previous = if (turn == null) byPath.remove(path) else byPath.put(path, turn)
        return previous != turn
    }
}
