package dev.marginalis.core

object TurnSignal {
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
        fun of(threads: List<CommentThread>): TurnTally {
            val turns = threads.mapNotNull { it.turn() }
            return TurnTally(user = turns.count { it == Turn.USER_OWES }, agent = turns.count { it == Turn.AGENT_OWES })
        }
    }
}
