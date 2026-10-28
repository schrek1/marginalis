package dev.marginalis.core

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Message(
    val author: Author,
    body: String,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    seenBy: Set<String>? = null,
    val to: Addressee? = null,
    val agrees: Boolean = false,
    readByUser: Boolean = false,
    val relayed: Relayed? = null,
) {
    init {
        require(relayed == null || to == null) { "a relayed message is someone else's words; it addresses no one" }
    }

    fun speaker(people: People): String =
        relayed?.let { people.nicknameFor(it.login) ?: it.name } ?: people.displayNameOf(author)

    val notifiesUser: Boolean
        get() = author is Author.Agent && relayed == null

    /** A user message may be revised only until an agent reads it. */
    @Volatile
    var body: String = body

    private val _seenBy: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        when {
            seenBy != null -> addAll(seenBy)
            // An agent has read its own words; other agents haven't.
            author is Author.Agent -> add(author.receiptKey)
        }
    }

    /** [Author.Agent.receiptKey]s. Per agent, so one agent's sweep can't consume another's unread. */
    val seenBy: Set<String>
        get() = _seenBy

    fun markSeenBy(agentKey: String) {
        _seenBy.add(agentKey)
    }

    val seenByAnyAgent: Boolean
        get() = _seenBy.isNotEmpty()

    fun seenBy(agentKey: String): Boolean = agentKey in _seenBy

    @Volatile
    var readByUser: Boolean = readByUser || author is Author.User
        private set

    fun markReadByUser(): Boolean {
        if (readByUser) return false
        readByUser = true
        return true
    }

    /** The user's own words are always read; only someone else's message can be unread again. */
    fun markUnreadByUser(): Boolean {
        if (!readByUser || author is Author.User) return false
        readByUser = false
        return true
    }

    fun continues(previous: Message?): Boolean =
        previous != null && author is Author.Agent && author == previous.author && to == null && previous.to == null &&
            relayed == null && previous.relayed == null

    fun showsAvatar(previous: Message?): Boolean = !agrees && !continues(previous)

    val awaits: Turn
        get() = when (to) {
            Addressee.User -> Turn.USER_OWES
            is Addressee.Agent -> Turn.AGENT_OWES
            null -> if (author is Author.Agent) Turn.USER_OWES else Turn.AGENT_OWES
        }

    companion object {
        fun agreement(by: Author.User, with: Author.Agent): Message =
            Message(by, "Agreed.", to = Addressee.Agent(with.receiptKey), agrees = true)
    }
}
