package dev.marginalis.plugin.transport

import com.google.gson.JsonParser
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.Relays
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.jetbrains.ide.BuiltInServerManager

class DiscoveryAndErrorsTest : HttpContractTestCase() {

    fun testPingReportsStatusIdeVersionAndTheOpenProject() {
        val ping = agent.get("ping")

        assertEquals(200, ping.status)
        assertEquals("ok", ping["status"]!!.asString)
        assertFalse(ping["ide"]!!.asString.isBlank())
        assertFalse(ping["version"]!!.asString.isBlank())
        val projects = ping.json.getAsJsonArray("projects").map { it.asJsonObject }
        val here = projects.single { it["name"].asString == project.name }
        assertEquals("/src", here["path"].asString)
        assertNull(here["branch"])
    }

    fun testAgentGuideIsServedAsTheBundledMarkdown() {
        val bundled = javaClass.classLoader.getResourceAsStream("marginalis/agent-guide.md")!!.readBytes().toString(Charsets.UTF_8)

        val response = raw("GET", "agent_guide")

        assertEquals(200, response.statusCode())
        assertEquals(bundled, response.body())
        assertTrue(response.headers().firstValue("Content-Type").get().startsWith("text/markdown"))
    }

    fun testAgentGuideMentionsEveryEndpoint() {
        val guide = raw("GET", "agent_guide").body()
        listOf(
            "ping", "agent_guide", "comment_list", "comment_add", "comment_add_batch", "comment_reply",
            "comment_resolve", "comment_reopen", "comment_reanchor", "comment_reanchor_all", "comment_resolve_all",
            "comment_clear_all", "comment_wait", "comment_identities", "navigate",
        ).forEach { assertTrue("guide never mentions $it", guide.contains("`GET $it") || guide.contains("`POST $it")) }
    }

    fun testUnknownEndpointIsA404WithAnError() {
        assertRefused(agent.get("comment_delete"), 404, "comment_delete")
    }

    fun testWriteEndpointsRefuseGetWith405() {
        listOf(
            "comment_add", "comment_add_batch", "comment_reply", "comment_resolve", "comment_reopen", "comment_resolve_all",
            "comment_reanchor", "comment_reanchor_all", "comment_clear_all", "navigate",
        ).forEach { assertRefused(agent.get(it), 405, "POST") }
    }

    fun testCommentWaitAndIdentitiesRefusePostWith405() {
        assertRefused(agent.post("comment_wait"), 405, "GET")
        assertRefused(agent.post("comment_identities"), 405, "GET")
    }

    fun testMethodsOtherThanGetAndPostAreNotFound() {
        assertEquals(404, raw("PUT", "ping").statusCode())
        assertEquals(404, raw("DELETE", "comment_list").statusCode())
    }

    fun testCommentListAnswersAPostLikeAGet() {
        val id = sample().let { agent.addOnLine(3) }

        val listed = agent.post("comment_list")

        assertEquals(200, listed.status)
        assertEquals(listOf(id), listed.json.getAsJsonArray("threads").map { it.asJsonObject["thread_id"].asString })
    }

    fun testPostBodyThatIsNotJsonIsA400() {
        assertRefused(agent.postRaw("comment_add", "body=hello"), 400, "JSON object")
    }

    fun testPostBodyThatIsAJsonArrayIsA400() {
        assertRefused(agent.postRaw("comment_add", "[{\"body\": \"hello\"}]"), 400, "JSON object")
    }

    fun testEveryRefusalIsAJsonObjectWithAnError() {
        sample()
        val refusals = listOf(
            agent.get("nope"),
            agent.post("comment_add"),
            agent.post("comment_add", "file" to SAMPLE, "line" to 999, "anchor_text" to "x", "body" to "b"),
            agent.post("comment_reply", "thread_id" to "missing", "body" to "b"),
            agent.get("comment_list", "status" to "closed"),
            agent.get("comment_wait", "timeout" to "soon"),
        )
        refusals.forEach { response ->
            assertTrue(response.status in 400..499)
            assertTrue(JsonParser.parseString(response.body).asJsonObject["error"].asString.isNotBlank())
        }
    }

    fun testIdentitiesListTheUserFirstThenAgentsByMessagesWritten() {
        val thread = sample().let { reviewer.addOnLine(5) }
        agent.addOnLine(7)
        agent.addOnLine(9)
        user.reply(thread, "Why?")

        val identities = agent.get("comment_identities")

        assertEquals(project.name, identities["project"]!!.asString)
        val listed = identities.json.getAsJsonArray("identities").map { it.asJsonObject }
        assertEquals(listOf("user", "agent", "agent"), listed.map { it["kind"].asString })
        assertEquals(Authors.user.displayName, listed[0]["name"].asString)
        assertEquals(1, listed[0]["messages_written"].asInt)
        assertEquals(setOf("kind", "name", "messages_written"), listed[0].keySet())
        assertEquals(listOf("claude-main", "claude-review"), listed.drop(1).map { it["id"].asString })
        assertEquals(listOf("Claude", "Claude · review"), listed.drop(1).map { it["name"].asString })
        assertEquals(listOf(2, 1), listed.drop(1).map { it["messages_written"].asInt })
        assertEquals(listOf(false, false), listed.drop(1).map { it["waiting"].asBoolean })
    }

    fun testIdentitiesWithEqualOutputAreOrderedById() {
        sample()
        agentNamed("Zed", "b-agent").addOnLine(3)
        agentNamed("Amy", "c-agent").addOnLine(4)
        agentNamed("Bob", "a-agent").addOnLine(5)

        val ids = agent.get("comment_identities").json.getAsJsonArray("identities").drop(1).map { it.asJsonObject["id"].asString }

        assertEquals(listOf("a-agent", "b-agent", "c-agent"), ids)
    }

    fun testIdentityCountsMessagesItHasNotSeen() {
        sample()
        agent.addOnLine(3)
        agent.addOnLine(4)
        reviewer.threads()
        val thread = agent.addOnLine(5)
        user.reply(thread, "ok")

        val review = identity(reviewer.id)

        assertEquals(2, review["unread"].asInt)
        assertEquals(1, identity(agent.id)["unread"].asInt)
    }

    fun testIdentitiesMarkNothingSeen() {
        sample()
        agent.addOnLine(3)

        reviewer.get("comment_identities")

        assertEquals(1, reviewer.threads()[0].messages().count { it["newly_seen"]?.asBoolean == true })
    }

    fun testAnIdentityKnownOnlyFromReadReceiptsHasNoName() {
        sample()
        agent.addOnLine(3)
        reviewer.threads()

        val review = identity(reviewer.id)

        assertTrue(review["name"].isJsonNull)
        assertEquals(0, review["messages_written"].asInt)
        assertEquals(0, review["unread"].asInt)
    }

    fun testAWaitingAgentIsListedBeforeItHasWrittenAnything() {
        val waiting = reviewer.startWait("timeout" to 30)

        val review = identity(reviewer.id)

        assertEquals("Claude · review", review["name"].asString)
        assertTrue(review["waiting"].asBoolean)
        assertEquals(0, review["messages_written"].asInt)
        user.stop("Claude · review")
        assertEquals("stopped", reviewer.await(waiting)["reason"]!!.asString)
        assertTrue(agent.get("comment_identities").json.getAsJsonArray("identities").none { it.asJsonObject["id"]?.asString == reviewer.id })
    }

    fun testRelaysDoNotCountAsMessagesWritten() {
        sample()
        agent.add("file" to SAMPLE, "body" to "From GitHub", "relayed" to Relays.reviewComment(1001))
        agent.addOnLine(3)

        assertEquals(1, identity(agent.id)["messages_written"].asInt)
    }

    fun testUnidentifiedCallersAreListedAsTheAnonymousAgent() {
        sample()
        agent.post("comment_add", "file" to SAMPLE, "body" to "who am I", "author_name" to null, "author_id" to null)

        val anonymous = identity("Agent")

        assertEquals("Agent", anonymous["name"].asString)
        assertEquals(1, anonymous["messages_written"].asInt)
    }

    fun testAnUnknownProjectIsA404ListingOpenProjects() {
        val response = agent.get("comment_identities", "project" to "elsewhere")

        assertRefused(response, 404, "elsewhere")
        assertEquals(project.name, response.json.getAsJsonArray("open_projects")[0].asJsonObject["name"].asString)
    }

    fun testWithOneProjectOpenIdentitiesNeedNoProject() {
        assertEquals(project.name, agent.get("comment_identities", "project" to null)["project"]!!.asString)
    }

    private fun agentNamed(name: String, id: String) = Agent(project, name, id)

    private fun identity(id: String) =
        agent.get("comment_identities").json.getAsJsonArray("identities").map { it.asJsonObject }
            .single { it["kind"].asString == "agent" && it["id"].asString == id }

    private fun raw(method: String, endpoint: String): HttpResponse<String> {
        val port = BuiltInServerManager.getInstance().waitForStart().port
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/marginalis/$endpoint"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString())
    }
}
