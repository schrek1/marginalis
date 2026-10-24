package dev.marginalis.plugin.testing

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.ide.BuiltInServerManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

class Agent(private val project: Project, val name: String = "Test agent", val id: String = "test-agent") {

    data class Response(val status: Int, val body: String) {
        val json: JsonObject get() = JsonParser.parseString(body).asJsonObject
        operator fun get(field: String): JsonElement? = json.get(field)
    }

    fun get(endpoint: String, vararg params: Pair<String, Any?>): Response = await(getAsync(endpoint, *params))

    fun getAsync(endpoint: String, vararg params: Pair<String, Any?>): CompletableFuture<Response> {
        val query = (identity() + params.toMap())
            .filterValues { it != null }
            .entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value.toString(), Charsets.UTF_8)}" }
        return send(HttpRequest.newBuilder(uri("$endpoint?$query")).GET().build())
    }

    fun post(endpoint: String, vararg fields: Pair<String, Any?>): Response = post(endpoint, fields.toMap())

    fun post(endpoint: String, fields: Map<String, Any?>): Response = await(postAsync(endpoint, fields))

    fun postAsync(endpoint: String, vararg fields: Pair<String, Any?>): CompletableFuture<Response> = postAsync(endpoint, fields.toMap())

    private fun postAsync(endpoint: String, fields: Map<String, Any?>): CompletableFuture<Response> {
        val body = gson.toJson((identity() + fields).filterValues { it != null })
        val request = HttpRequest.newBuilder(uri(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return send(request)
    }

    fun postRaw(endpoint: String, body: String): Response {
        val request = HttpRequest.newBuilder(uri(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return await(send(request))
    }

    fun startWait(vararg params: Pair<String, Any?>): CompletableFuture<Response> {
        val waiting = getAsync("comment_wait", *params)
        val inProject = params.filter { it.first == "project" }.toTypedArray()
        val deadline = System.nanoTime() + 15_000_000_000
        while (!waiting.isDone && !isListening(*inProject)) {
            check(System.nanoTime() < deadline) { "$id never started waiting" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
        return waiting
    }

    private fun isListening(vararg inProject: Pair<String, Any?>): Boolean =
        get("comment_identities", *inProject).json.getAsJsonArray("identities").map { it.asJsonObject }
            .any { it["id"]?.asString == id && it["waiting"]?.asBoolean == true }

    fun await(response: CompletableFuture<Response>, seconds: Int = 15): Response {
        PlatformTestUtil.waitWithEventsDispatching("no answer within $seconds s", { response.isDone }, seconds)
        return response.get()
    }

    private fun identity(): Map<String, Any?> = mapOf("author_name" to name, "author_id" to id, "project" to project.name)

    private fun send(request: HttpRequest): CompletableFuture<Response> =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { Response(it.statusCode(), it.body()) }

    private fun uri(path: String) = URI("http://127.0.0.1:$port/api/marginalis/$path")

    private companion object {
        val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val gson = Gson()
        val port: Int by lazy { BuiltInServerManager.getInstance().waitForStart().port }
    }
}
