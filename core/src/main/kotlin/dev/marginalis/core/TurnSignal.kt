package dev.marginalis.core

object TurnSignal {
    /**
     * The turn a thread's glyph shows: your move only while the thread holds a message you have not read, and
     * an Agree the agent has not answered yet is approval, shown as 👍, not ✈.
     */
    fun of(thread: CommentThread): Turn? =
        thread.turn()
            ?.takeUnless { it == Turn.USER_OWES && thread.unreadByUserCount() == 0 }
            ?.takeUnless { thread.awaitsAgreementAnswer }

    fun spoken(turn: Turn): String = when (turn) {
        Turn.USER_OWES -> "your move"
        Turn.AGENT_OWES -> "agent's move"
    }

    fun glyph(turn: Turn): String = when (turn) {
        Turn.USER_OWES -> "✉"
        Turn.AGENT_OWES -> "✈"
    }

    fun glyphs(tally: TurnTally): String = shown(tally, ::glyph, " · ")

    fun spoken(tally: TurnTally): String = shown(tally, ::spoken, ", ")

    private fun shown(tally: TurnTally, name: (Turn) -> String, separator: String): String = listOfNotNull(
        tally.user.takeIf { it > 0 }?.let { "${name(Turn.USER_OWES)} $it" },
        tally.agent.takeIf { it > 0 }?.let { "${name(Turn.AGENT_OWES)} $it" },
    ).joinToString(separator)
}

data class TurnTally(val user: Int, val agent: Int) {
    companion object {
        fun of(threads: List<CommentThread>): TurnTally = tally(threads.mapNotNull { it.turn() })

        fun signalled(threads: List<CommentThread>): TurnTally = tally(threads.mapNotNull(TurnSignal::of))

        private fun tally(turns: List<Turn>): TurnTally =
            TurnTally(user = turns.count { it == Turn.USER_OWES }, agent = turns.count { it == Turn.AGENT_OWES })
    }
}
