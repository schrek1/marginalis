package dev.marginalis.core

object TurnSignal {
    fun spoken(turn: Turn): String = ThreadSignal.forTurn(turn).spoken

    fun glyph(turn: Turn): String = ThreadSignal.forTurn(turn).glyph

    fun glyphs(tally: TurnTally): String = shown(tally, ::glyph, " · ")

    fun spoken(tally: TurnTally): String = shown(tally, ::spoken, ", ")

    private fun shown(tally: TurnTally, name: (Turn) -> String, separator: String): String = listOfNotNull(
        tally.user.takeIf { it > 0 }?.let { "${name(Turn.USER_OWES)} $it" },
        tally.agent.takeIf { it > 0 }?.let { "${name(Turn.AGENT_OWES)} $it" },
    ).joinToString(separator)
}

data class TurnTally(val user: Int, val agent: Int) {
    companion object {
        fun owed(threads: List<CommentThread>): TurnTally = tally(threads.mapNotNull { it.owedTurn() })

        fun signalled(threads: List<CommentThread>): TurnTally =
            tally(threads.mapNotNull { ThreadSignal.of(it)?.turn })

        private fun tally(turns: List<Turn>): TurnTally =
            TurnTally(user = turns.count { it == Turn.USER_OWES }, agent = turns.count { it == Turn.AGENT_OWES })
    }
}
