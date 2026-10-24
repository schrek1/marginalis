package dev.marginalis.core

class LiveSwitches {

    data class ProjectTurned(val nowLive: List<CommentThread>, val noLongerLive: List<CommentThread>)

    private val lock = Any()
    private val ownSwitches = HashMap<String, Boolean>()
    private val targets = HashMap<String, String>()
    private val ended = HashSet<String>()

    fun isLive(thread: CommentThread, projectLive: Boolean): Boolean =
        synchronized(lock) { liveNow(thread, projectLive) }

    fun switch(threadId: String, on: Boolean) {
        synchronized(lock) { ownSwitches[threadId] = on }
    }

    fun turnProject(threads: List<CommentThread>, wasLive: Boolean, isLive: Boolean): ProjectTurned = synchronized(lock) {
        val before = threads.filter { liveNow(it, wasLive) }
        ownSwitches.clear()
        val after = threads.filter { liveNow(it, isLive) }
        val noLongerLive = before - after.toSet()
        noLongerLive.forEach { targets.remove(it.id) }
        ProjectTurned(nowLive = after - before.toSet(), noLongerLive = noLongerLive)
    }

    fun targetOf(threadId: String): String? = synchronized(lock) { targets[threadId] }

    fun target(threadId: String, agentKey: String) {
        synchronized(lock) { targets[threadId] = agentKey }
    }

    fun withdraw(threadId: String) {
        synchronized(lock) { targets.remove(threadId) }
    }

    fun end(threadId: String) {
        synchronized(lock) {
            forgetSwitchAndTarget(threadId)
            ended += threadId
        }
    }

    fun resumed(threadId: String): Boolean = synchronized(lock) { ended.remove(threadId) }

    fun forget(threadId: String) {
        synchronized(lock) {
            forgetSwitchAndTarget(threadId)
            ended -= threadId
        }
    }

    private fun forgetSwitchAndTarget(threadId: String) {
        ownSwitches.remove(threadId)
        targets.remove(threadId)
    }

    private fun liveNow(thread: CommentThread, projectLive: Boolean): Boolean =
        LiveThread.isLive(thread, projectLive, ownSwitches[thread.id])
}
