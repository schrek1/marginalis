package dev.marginalis.core

/** How the agent answered an Agree: it took the agreed change on, or there is nothing left for it to do. */
enum class AgentState(val wireName: String) {
    WORKING("working"),
    ACKNOWLEDGED("acknowledged");

    companion object {
        fun parse(raw: String?): Parsed<AgentState> =
            entries.firstOrNull { it.wireName == raw?.trim()?.lowercase() }?.let { Parsed.Ok(it) }
                ?: Parsed.Invalid(
                    "invalid state '$raw' — answer an Agree with 'working' (you are taking the agreed change on; " +
                        "reply or resolve once it lands) or 'acknowledged' (nothing more to do; the user resolves).",
                )

        fun parseLenient(raw: String?): AgentState? = parse(raw).getOrElse { null }
    }
}

/** What a thread shows in place of a turn glyph while its last word is an Agree. */
enum class AgreementSignal(val glyph: String, val spoken: String) {
    AGREED("👍", "agreed"),
    AGENT_ON_IT("⏳", "agent on it");

    companion object {
        fun of(thread: CommentThread): AgreementSignal? = when {
            thread.awaitsAgreementAnswer -> AGREED
            thread.status is ThreadStatus.Open && thread.agentState == AgentState.WORKING -> AGENT_ON_IT
            else -> null
        }
    }
}
