package dev.marginalis.plugin.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readLines

/** The agent's side of a sandbox scenario: the plugin's REST API, called as an agent calls it. */
object Agent {

    const val PROJECT_NAME = "project"
    private val identity = mapOf("author_name" to "Claude", "author_id" to "claude-sandbox")
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val since = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'.000Z'").withZone(ZoneOffset.UTC)

    private val apiBase get() = "http://127.0.0.1:${Sandbox.marginalisPort}/api/marginalis"

    fun apiPost(route: String, payload: Map<String, Any?>): JsonObject {
        val request = HttpRequest.newBuilder(URI("$apiBase/$route"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload.filterValues { it != null })))
            .build()
        return parsed(http.send(request, HttpResponse.BodyHandlers.ofString()), route)
    }

    fun agentStartsProjectThread(body: String): String =
        threadId(apiPost("comment_add", mapOf("body" to body, "project" to PROJECT_NAME) + identity))

    /** `line` is 1-based, as agents send it; the anchor text is read from the sandbox project's file. */
    fun agentAddsLineThread(file: String, line: Int, body: String, order: Int? = null): String =
        threadId(apiPost("comment_add", mapOf("body" to body, "file" to file, "line" to line, "anchor_text" to anchorOf(file, line), "project" to PROJECT_NAME, "order" to order) + identity))

    fun agentAddsStep(file: String, line: Int, body: String, order: Int, walkthrough: String): String =
        threadId(
            apiPost(
                "comment_add",
                mapOf("body" to body, "file" to file, "line" to line, "anchor_text" to anchorOf(file, line), "order" to order, "walkthrough" to walkthrough, "project" to PROJECT_NAME) + identity,
            ),
        )

    fun agentAddsProjectStep(body: String, order: Int, walkthrough: String): String =
        threadId(apiPost("comment_add", mapOf("body" to body, "order" to order, "walkthrough" to walkthrough, "project" to PROJECT_NAME) + identity))

    fun agentReplies(threadId: String, body: String) {
        apiPost("comment_reply", mapOf("thread_id" to threadId, "body" to body) + identity)
    }

    fun resolveThread(threadId: String) {
        apiPost("comment_resolve", mapOf("thread_id" to threadId) + identity)
    }

    /** Blocks like an agent's `comment_wait` from now on; returns the wake or the timeout. */
    fun agentWaits(timeoutSeconds: Int): JsonObject {
        val query = (mapOf("project" to PROJECT_NAME, "since" to since.format(Instant.now()), "timeout" to timeoutSeconds.toString()) + identity)
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        val request = HttpRequest.newBuilder(URI("$apiBase/comment_wait?$query")).timeout(Duration.ofSeconds(timeoutSeconds + 30L)).GET().build()
        return parsed(http.send(request, HttpResponse.BodyHandlers.ofString()), "comment_wait")
    }

    private fun parsed(response: HttpResponse<String>, route: String): JsonObject {
        check(response.statusCode() < 400) { "$route answered ${response.statusCode()}: ${response.body()}" }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    private fun anchorOf(file: String, line: Int) = Sandbox.project.resolve(file).readLines(StandardCharsets.UTF_8)[line - 1]

    private fun threadId(response: JsonObject): String =
        response.get("thread_id")?.asString ?: error("no thread_id in $response")
}
