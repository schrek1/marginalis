package dev.marginalis.core

enum class Turn(val wireName: String) {
    USER_OWES("user"),
    AGENT_OWES("agent");

    companion object {
        /** What the user owes outranks what the agent owes: the user is the one looking at the surface. */
        fun owed(threads: List<CommentThread>): Turn? {
            val turns = threads.mapNotNull { it.owedTurn() }
            return when {
                turns.isEmpty() -> null
                USER_OWES in turns -> USER_OWES
                else -> AGENT_OWES
            }
        }

        fun parse(raw: String?): Parsed<Turn?> {
            if (raw == null) return Parsed.Ok(null)
            val turn = entries.firstOrNull { it.wireName == raw.lowercase() }
                ?: return Parsed.Invalid(
                    "invalid awaiting '$raw' — use 'agent' (the last message that was not relayed is the user's, " +
                        "or addressed to you: you owe a reply; one addressed to another agent is theirs) or 'user' " +
                        "(an agent spoke last unaddressed, someone addressed 'user', or only relays were said: the " +
                        "user owes one — on an fyi only a read, so a read fyi owes nothing until a new message " +
                        "arrives); omit to list regardless of whose turn it is.",
                )
            return Parsed.Ok(turn)
        }
    }
}
