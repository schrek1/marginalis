package dev.marginalis.core

enum class ThreadSignal(val glyph: String, val spoken: String, val turn: Turn? = null) {
    YOUR_MOVE("✉", "your move", Turn.USER_OWES),
    AGENTS_MOVE("✈", "agent's move", Turn.AGENT_OWES),
    AGREED("👍", "agreed"),
    AGENT_ON_IT("⏳", "agent on it");

    companion object {
        fun forTurn(turn: Turn): ThreadSignal = entries.first { it.turn == turn }

        fun of(thread: CommentThread): ThreadSignal? =
            onCard(thread)?.takeUnless { it == YOUR_MOVE && thread.unreadByUserCount() == 0 }

        fun onCard(thread: CommentThread): ThreadSignal? = when {
            thread.status !is ThreadStatus.Open -> null
            thread.awaitsAgreementAnswer -> AGREED
            else -> when (thread.agreementAnswer) {
                AgreementAnswer.WORKING -> AGENT_ON_IT
                AgreementAnswer.ACKNOWLEDGED -> null
                null -> thread.turn()?.let(::forTurn)
            }
        }
    }
}
