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
import java.util.concurrent.CompletableFuture

/** The agent's side of a sandbox scenario: the plugin's REST API, called as an agent calls it. */
object Agent {

    const val PROJECT_NAME = "project"
    private val identity = mapOf("author_name" to "Claude", "author_id" to "claude-sandbox")
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val since = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

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

    /**
     * `line` is 1-based, as agents send it; the anchor text is read from the sandbox project's file. `fields` adds
     * further `comment_add` fields, e.g. `intent` or `severity`.
     */
    fun agentAddsLineThread(file: String, line: Int, body: String, order: Int? = null, fields: Map<String, Any> = emptyMap()): String =
        threadId(
            apiPost(
                "comment_add",
                mapOf("body" to body, "file" to file, "line" to line, "anchor_text" to anchorOf(file, line), "project" to PROJECT_NAME, "order" to order) + fields + identity,
            ),
        )

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

    /** Answers the Agree that is the thread's last word: `working` or `acknowledged`. */
    fun agentAnswersAgree(threadId: String, state: String) {
        apiPost("comment_agent_state", mapOf("thread_id" to threadId, "state" to state) + identity)
    }

    fun resolveThread(threadId: String) {
        apiPost("comment_resolve", mapOf("thread_id" to threadId) + identity)
    }

    /** A read of the API as the agent makes it: the project and the agent's identity go with `params`. */
    fun apiGet(route: String, params: Map<String, String>, timeout: Duration = Duration.ofSeconds(30)): JsonObject =
        parsed(http.send(getRequest(route, params, timeout), HttpResponse.BodyHandlers.ofString()), route)

    private fun getRequest(route: String, params: Map<String, String>, timeout: Duration?): HttpRequest {
        val query = (mapOf("project" to PROJECT_NAME) + params + identity)
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}" }
        return HttpRequest.newBuilder(URI("$apiBase/$route?$query")).apply { timeout?.let(::timeout) }.GET().build()
    }

    /**
     * Blocks like an agent's `comment_wait`; returns the wake or the timeout. Its `since` is the last round the agent
     * was woken by, as the guide tells agents: the host's clock is not the IDE's, so a `since` of "now" can fall before
     * a round that already woke it, which would then wake it again at once. Before any round, `since` is now.
     */
    fun agentWaits(timeoutSeconds: Int): JsonObject =
        apiGet("comment_wait", waitSince() + ("timeout" to timeoutSeconds.toString()), Duration.ofSeconds(timeoutSeconds + 30L))
            .also(::rememberRound)

    /**
     * Runs [action] while the agent holds a `comment_wait` open, then hangs up as an agent that stops waiting does. The
     * action starts only once the IDE counts the agent as listening, and the wait has no deadline of its own that a busy
     * IDE could outlast before the action reaches it.
     */
    fun <T> listensWhile(action: () -> T): T {
        val wait = http.sendAsync(getRequest("comment_wait", waitSince(), timeout = null), HttpResponse.BodyHandlers.ofString())
        val result = try {
            val counted = waitUntil("the IDE counts the agent as listening", Duration.ofSeconds(30)) { wait.isDone || listeningAgents() > 0 }
            check(counted && !wait.isDone) { "the IDE does not count the agent as listening" }
            action()
        } catch (error: IllegalStateException) {
            throw IllegalStateException("${error.message}; the agent's comment_wait ${outcomeOf(wait)}", error)
        } finally {
            wait.cancel(true)
        }
        if (!wait.isCompletedExceptionally) rememberRound(parsed(wait.join(), "comment_wait"))
        awaitUntil("the IDE lets the agent go once it hangs up") { listeningAgents() == 0 }
        return result
    }

    private fun waitSince() = mapOf("since" to (Sandbox.lastRoundAt ?: since.format(Instant.now())))

    private fun rememberRound(wake: JsonObject) {
        wake.get("handed_back_at")?.takeUnless { it.isJsonNull }?.let { Sandbox.lastRoundAt = it.asString }
    }

    private fun listeningAgents(): Int = SandboxUi.js("String(store.getHandBack().getWaitingAgents().size())").toInt()

    private fun outcomeOf(wait: CompletableFuture<HttpResponse<String>>): String = when {
        !wait.isDone -> "was still open"
        wait.isCompletedExceptionally -> "failed: ${runCatching { wait.join() }.exceptionOrNull()}"
        else -> "returned ${wait.join().body()}"
    }

    private fun parsed(response: HttpResponse<String>, route: String): JsonObject {
        check(response.statusCode() < 400) { "$route answered ${response.statusCode()}: ${response.body()}" }
        return JsonParser.parseString(response.body()).asJsonObject
    }

    // The line as the IDE holds it, as an agent reads the file it sees: the host folder lags behind the IDE's copy
    // and its unsaved edits.
    private fun anchorOf(file: String, line: Int) = SandboxUi.lineText(file, line)

    private fun threadId(response: JsonObject): String =
        response.get("thread_id")?.asString ?: error("no thread_id in $response")
}
