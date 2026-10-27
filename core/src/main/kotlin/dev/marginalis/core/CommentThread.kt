package dev.marginalis.core

import java.time.Instant
import java.util.UUID

class CommentThread(
    /** Project-relative path. */
    val file: String?,
    /** 0-based, last known good. */
    var line: Int?,
    var anchorText: String?,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    val order: Int? = null,
    val walkthrough: String? = null,
    /**
     * Human-created only (the selection gesture); agents read segments, never
     * write them. On a file- or project-level thread it is provenance, not an anchor.
     */
    val segment: Segment? = null,
    val severity: Severity? = null,
    val intent: Intent? = null,
    val label: String? = null,
) {
    init {
        require((line == null) == (anchorText == null)) {
            "an anchor is a line and its text together; a thread has both or neither"
        }
        require(label == null || intent == Intent.FYI) {
            "a label names a kind of fyi; only an fyi carries one"
        }
        require(intent != Intent.FYI || severity == null) {
            "an fyi asks for nothing, so it carries no severity"
        }
        require(line == null || file != null) {
            "a line is a place in a file; a thread without a file has no line to hold"
        }
    }

    val isProjectLevel: Boolean
        get() = file == null

    val isFileLevel: Boolean
        get() = file != null && line == null

    private val messagesLock = Any()
    private val _messages = mutableListOf<Message>()

    @Volatile
    var status: ThreadStatus = ThreadStatus.Open
        private set

    /**
     * A cursor, not a heartbeat: reads and anchor drift must not touch it, or
     * "what moved since I last looked?" never shrinks to nothing.
     */
    @Volatile
    var updatedAt: Instant = createdAt
        private set

    /** For the one change made inside a [Message]: a body revised in place. */
    fun touch() {
        updatedAt = Instant.now()
    }

    val messages: List<Message>
        get() = synchronized(messagesLock) { _messages.toList() }

    val resolvedBy: Author?
        get() = (status as? ThreadStatus.Resolved)?.by

    /** The agent's answer to the Agree that is the thread's last word; gone with the next word or a resolve. */
    @Volatile
    var agentState: AgentState? = null
        private set

    fun addMessage(message: Message) {
        synchronized(messagesLock) { _messages.add(message) }
        if (message.relayed == null) agentState = null
        touch()
    }

    fun resolve(by: Author) {
        status = ThreadStatus.Resolved(by)
        agentState = null
        touch()
    }

    val awaitsAgreementAnswer: Boolean
        get() = status is ThreadStatus.Open && lastSpoken?.agrees == true && agentState == null

    fun canAnswerAgreement(agent: Author.Agent): Boolean =
        status is ThreadStatus.Open && lastSpoken?.let { it.agrees && it.to == Addressee.Agent(agent.receiptKey) } == true

    fun answerAgreement(by: Author.Agent, state: AgentState) {
        check(canAnswerAgreement(by)) { "only the agent agreed with answers an Agree, and only while it is the last word" }
        agentState = state
        touch()
    }

    /** Rehydration only. */
    fun restoreAgentState(state: AgentState?) {
        agentState = state
    }

    fun reopen() {
        status = ThreadStatus.Open
        touch()
    }

    fun markOrphaned() {
        status = ThreadStatus.Orphaned
        touch()
    }

    /** The fresh [anchorText] is mandatory: a rescue that kept the old one would re-orphan on the next restart. */
    fun rescueTo(line: Int, anchorText: String) {
        check(this.line != null) {
            val subject = if (isProjectLevel) "the project" else "its file"
            "this thread is about $subject as a whole and has no anchor to move; it comes back with what it is about"
        }
        check(status is ThreadStatus.Orphaned) {
            "only an orphaned thread can be re-anchored; this one is ${status.kind.name.lowercase()}"
        }
        this.line = line
        this.anchorText = anchorText
        status = ThreadStatus.Open
        touch()
    }

    /** Rehydration only. */
    fun restoreStatus(status: ThreadStatus) {
        this.status = status
    }

    /** Rehydration only: loading a thread is not a change to it. */
    fun restoreUpdatedAt(updatedAt: Instant) {
        this.updatedAt = updatedAt
    }

    fun markReadByUser(shown: List<Message> = messages): Boolean = shown.map { it.markReadByUser() }.any { it }

    /** Unread as when it arrived: only the latest message from someone else, so the thread opens folded as before. */
    fun markUnreadByUser(): Boolean = messages.lastOrNull { it.author !is Author.User }?.markUnreadByUser() ?: false

    val isRelayedRoot: Boolean
        get() = messages.firstOrNull()?.relayed != null

    val discussion: String?
        get() = messages.firstOrNull()?.relayed?.discussion

    val lastSpoken: Message?
        get() = messages.lastOrNull { it.relayed == null }

    fun relayedMessage(key: Relayed.Key): Message? = messages.firstOrNull { it.relayed?.key == key }

    fun addRelayedOnce(message: Message): Message {
        val key = requireNotNull(message.relayed) { "only a relayed message can be relayed once" }.key
        synchronized(messagesLock) {
            _messages.firstOrNull { it.relayed?.key == key }?.let { return it }
            _messages.add(message)
        }
        if (status is ThreadStatus.Resolved) status = ThreadStatus.Open
        touch()
        return message
    }

    fun agreeable(): Message? =
        lastSpoken?.takeIf { it.author is Author.Agent && turn() == Turn.USER_OWES }

    private val isReadFyi: Boolean
        get() = intent == Intent.FYI && messages.all { it.author is Author.Agent && it.readByUser }

    fun unreadByUserCount(): Int = messages.count { !it.readByUser }

    fun unreadCount(): Int = messages.count { !it.seenByAnyAgent }

    fun unreadCountFor(agentKey: String): Int = messages.count { !it.seenBy(agentKey) }

    fun turn(): Turn? = turnFor(null)

    fun turnFor(agentKey: String?): Turn? {
        val last = lastSpoken
        val turn = when {
            status !is ThreadStatus.Open -> null
            isReadFyi -> null
            last == null && messages.isNotEmpty() -> Turn.USER_OWES
            last?.agrees == true && agentState != null -> null
            last?.awaits == Turn.USER_OWES -> Turn.USER_OWES
            else -> Turn.AGENT_OWES
        }
        val addressee = last?.to
        return turn?.takeIf { agentKey == null || it == Turn.USER_OWES || addressee == null || addressee == Addressee.Agent(agentKey) }
    }
}
