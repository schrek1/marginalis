package dev.marginalis.core

enum class AgreementAnswer(val wireName: String) {
    WORKING("working"),
    ACKNOWLEDGED("acknowledged");

    enum class Outcome { ANSWERED, UNCHANGED, REFUSED }

    companion object {
        private const val CHOICES = "answer an Agree with 'working' (you are taking the agreed change on; " +
            "reply or resolve once it lands) or 'acknowledged' (nothing more to do; the user resolves)."

        fun parse(raw: String?): Parsed<AgreementAnswer> {
            if (raw == null) return Parsed.Invalid("missing 'state' — $CHOICES")
            return entries.firstOrNull { it.wireName == raw.trim().lowercase() }?.let { Parsed.Ok(it) }
                ?: Parsed.Invalid("invalid state '$raw' — $CHOICES")
        }

        fun parseLenient(raw: String?): AgreementAnswer? = parse(raw).getOrElse { null }
    }
}
