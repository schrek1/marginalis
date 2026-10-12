package dev.marginalis.core

import java.time.Instant

object LiveThread {

    fun isOver(current: CommentThread?): Boolean = current == null || current.status !is ThreadStatus.Open

    fun hasUnseen(thread: CommentThread?, agentKey: String): Boolean =
        thread != null && !isOver(thread) && thread.unreadCountFor(agentKey) > 0

    // A thread's own switch overrides the project's default until the default is flipped again.
    fun isLive(thread: CommentThread, byDefault: Boolean, switchedOn: Set<String>, switchedOff: Set<String>): Boolean =
        !isOver(thread) && if (byDefault) thread.id !in switchedOff else thread.id in switchedOn

    fun goLive(handBack: HandBack, thread: CommentThread, agentKey: String?) {
        if (agentKey != null && hasUnseen(thread, agentKey)) handBack.recordLive(thread.id, agentKey, thread.updatedAt)
    }

    fun submit(handBack: HandBack, thread: CommentThread, agentKey: String?) {
        if (agentKey != null && thread.turnFor(agentKey) == Turn.AGENT_OWES) {
            handBack.recordLive(thread.id, agentKey, thread.updatedAt)
        }
    }

    fun isWorking(messages: List<Message>, agentKey: String, deliveredAt: Instant?): Boolean =
        deliveredAt != null &&
            messages.none {
                it.relayed == null && (it.author as? Author.Agent)?.receiptKey == agentKey && it.createdAt > deliveredAt
            }
}
