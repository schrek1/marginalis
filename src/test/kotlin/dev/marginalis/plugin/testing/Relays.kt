package dev.marginalis.plugin.testing

object Relays {

    fun reviewComment(id: Long, login: String = "mona", name: String = "Mona Lisa"): Map<String, Any> = mapOf(
        "source" to "github",
        "comment_id" to id,
        "url" to "https://github.com/acme/shop/pull/7#discussion_r$id",
        "name" to name,
        "login" to login,
    )

    fun conversationComment(id: Long, login: String = "hubot"): Map<String, Any> = mapOf(
        "source" to "github",
        "comment_id" to id.toString(),
        "url" to "https://github.com/acme/shop/pull/7#issuecomment-$id",
        "name" to login,
        "login" to login,
    )
}
