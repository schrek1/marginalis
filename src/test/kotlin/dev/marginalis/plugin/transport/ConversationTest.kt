package dev.marginalis.plugin.transport

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.ui.ThreadPanel
import java.time.Instant

class ConversationTest : HttpContractTestCase() {

    fun testAReplyAnswersWithItsMessageAndThread() {
        sample()
        val id = agent.addOnLine(5)

        val response = agent.post("comment_reply", "thread_id" to id, "body" to "Also this.")

        assertEquals(setOf("message_id", "thread_id", "status"), response.json.keySet())
        assertEquals(id, response["thread_id"]!!.asString)
        assertEquals("open", response["status"]!!.asString)
        assertEquals(response["message_id"]!!.asString, agent.threads().single().messages().last().string("message_id"))
    }

    fun testAnAgentReplyIsTheUsersMoveAndShowsInTheirPanel() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Why?")

        agent.reply(id, "Because it is cheaper.")

        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
        assertTrue(user.canAgree(id))
    }

    fun testAReplyAddressedToAnotherAgentHandsThemTheThread() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Can someone check?")

        agent.reply(id, "Review, please.", "to" to "claude-review")

        assertEquals(listOf(id), reviewer.ids("awaiting" to "agent"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
        assertEquals("claude-review", agent.threads().single().messages().last().string("to"))
    }

    fun testAnAgreeStaysTheAgentsToAnswerUntilItSaysWhetherItIsOnIt() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")
        user.agree(id)

        val response = agent.post("comment_agent_state", "thread_id" to id, "state" to "working")

        assertEquals(setOf("thread_id", "status", "agent_state"), response.json.keySet())
        assertEquals("working", response["agent_state"]!!.asString)
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
        assertEquals("working", agent.summaryOf(id).string("agent_state"))
    }

    fun testAnAcknowledgedAgreeLeavesTheThreadOpenForTheUser() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")
        user.agree(id)

        agent.post("comment_agent_state", "thread_id" to id, "state" to "acknowledged")

        assertEquals("open", agent.summaryOf(id).string("status"))
        assertEquals("acknowledged", agent.summaryOf(id).string("agent_state"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
    }

    fun testTheNextWordClearsTheAgentState() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")
        user.agree(id)
        agent.post("comment_agent_state", "thread_id" to id, "state" to "working")

        agent.reply(id, "Extracted.")

        assertNull(agent.summaryOf(id)["agent_state"])
        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
    }

    fun testAnAgentStateAnswersOnlyAnAgreeAddressedToTheCaller() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")

        assertRefused(agent.post("comment_agent_state", "thread_id" to id, "state" to "working"), 409, "agent_state answers an Agree only")
        user.agree(id)
        val byAnotherAgent = reviewer.post("comment_agent_state", "thread_id" to id, "state" to "working")
        assertRefused(byAnotherAgent, 409, "claude-review")
        assertEquals("no_agreement", byAnotherAgent["reason"]!!.asString)
        assertNull(agent.summaryOf(id)["agent_state"])
    }

    fun testAnUnknownAgentStateIsATeaching400() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")
        user.agree(id)

        assertRefused(agent.post("comment_agent_state", "thread_id" to id, "state" to "done"), 400, "'working'")
        assertRefused(agent.post("comment_agent_state", "thread_id" to id), 400, "'acknowledged'")
    }

    fun testABlankReplyIsATeaching400() {
        sample()
        val id = agent.addOnLine(5)

        assertRefused(agent.post("comment_reply", "thread_id" to id, "body" to " "), 400, "'body' is empty")
        assertRefused(agent.post("comment_reply", "thread_id" to id), 400, "missing 'body'")
        assertRefused(agent.post("comment_reply", "thread_id" to id, "body" to listOf(7.5)), 400, "'body' must be a string")
        assertEquals(1, agent.threads().single().messages().size)
    }

    fun testABlankToOnAReplyIsATeaching400() {
        sample()
        val id = agent.addOnLine(5)

        assertRefused(agent.post("comment_reply", "thread_id" to id, "body" to "b", "to" to ""), 400, "'to' is blank")
    }

    fun testAReplyNeedsAThreadThatExists() {
        assertRefused(agent.post("comment_reply", "body" to "b"), 400, "missing 'thread_id'")
        assertRefused(agent.post("comment_reply", "thread_id" to "nope", "body" to "b"), 404, "nope")
    }

    fun testAReplyIgnoresTheProjectItIsGiven() {
        sample()
        val id = agent.addOnLine(5)

        assertEquals(200, agent.post("comment_reply", "thread_id" to id, "body" to "b", "project" to "elsewhere").status)
    }

    fun testAnAgentReplyLeavesAResolvedThreadResolved() {
        sample()
        val id = agent.addOnLine(5)
        agent.post("comment_resolve", "thread_id" to id)

        assertEquals("resolved", agent.reply(id, "Late thought.")["status"]!!.asString)
    }

    fun testResolvingRecordsTheResolverAndClosesTheThreadInTheEditor() {
        sample()
        val id = agent.addOnLine(5)
        user.open(id)

        val response = agent.post("comment_resolve", "thread_id" to id)

        assertEquals(setOf("thread_id", "status"), response.json.keySet())
        assertEquals("resolved", response["status"]!!.asString)
        assertEquals("Claude", agent.threads().single().string("resolved_by"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertNull(UIUtil.findComponentOfType(editor.contentComponent, ThreadPanel::class.java))
    }

    fun testResolvingWithoutAnIdentityIsCreditedToTheAnonymousAgent() {
        sample()
        val id = agent.addOnLine(5)

        agent.post("comment_resolve", "thread_id" to id, "author_name" to null, "author_id" to null)

        assertEquals("Agent", agent.threads().single().string("resolved_by"))
    }

    fun testReopeningResurfacesAResolvedThread() {
        sample()
        val id = agent.addOnLine(5)
        user.resolve(id)

        val response = agent.post("comment_reopen", "thread_id" to id)

        assertEquals("open", response["status"]!!.asString)
        val thread = agent.threads().single()
        assertEquals("open", thread.string("status"))
        assertNull(thread["resolved_by"])
    }

    // FixMe: Known issue - #53 reopening an orphan pins it to whatever line now holds its old number, and reanchor then refuses it
    fun testReopeningAnOrphanPinsItToTheLineNowAtItsOldNumber() {
        sample()
        val id = agent.addOnLine(5)
        eraseLine(5)
        agent.threads()

        assertEquals("open", agent.post("comment_reopen", "thread_id" to id)["status"]!!.asString)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val reopened = agent.threads().single()
        assertEquals("open", reopened.string("status"))
        assertEquals(5, reopened["line"].asInt)
        assertEquals(lineText(6), reopened.string("anchor_text"))
        val rescue = agent.post("comment_reanchor", "thread_id" to id, "line" to 7, "anchor_text" to lineText(8))
        assertEquals(409, rescue.status)
        assertEquals("not_orphaned", rescue["reason"]!!.asString)
    }

    fun testResolveAndReopenNeedAThreadThatExists() {
        listOf("comment_resolve", "comment_reopen").forEach { endpoint ->
            assertRefused(agent.post(endpoint), 400, "missing 'thread_id'")
            assertRefused(agent.post(endpoint, "thread_id" to "nope"), 404, "nope")
        }
    }

    fun testAnFyiAwaitsOnlyTheUsersRead() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi", "label" to "praise")

        assertEquals("user", agent.summaryOf(id).string("awaiting"))
        user.read(id)

        assertNull(agent.summaryOf(id)["awaiting"])
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
        assertEquals(listOf(id), agent.ids("status" to "open"))
    }

    fun testAnAgentsFurtherWordOnAReadFyiOwesARead() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi")
        user.read(id)

        agent.reply(id, "One more thing.")
        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
        user.read(id)

        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
    }

    fun testOnceTheUserRepliesToAnFyiTheTurnFollowsTheUsualRule() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi")
        user.reply(id, "Can you also document it?")

        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
        agent.reply(id, "Documented.")
        user.read(id)

        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
        assertEquals("open", agent.summaryOf(id).string("status"))
    }

    fun testAnAgentCanResolveAnFyiOverHttp() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi")

        assertEquals("resolved", agent.post("comment_resolve", "thread_id" to id)["status"]!!.asString)
    }

    fun testARelayedThreadIsAttributedToItsGitHubAuthor() {
        sample()
        val response = agent.post(
            "comment_add", "file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5),
            "body" to "Nit: rename", "relayed" to Relays.reviewComment(1001) + ("avatar_url" to "https://avatars.githubusercontent.com/u/1"),
        )

        assertEquals(200, response.status)
        val message = agent.threads().single().messages().single()
        assertEquals("Nit: rename", message.string("body"))
        assertEquals("claude-main", message.getAsJsonObject("author").string("id"))
        val relayed = message.getAsJsonObject("relayed")
        assertEquals("github", relayed.string("source"))
        assertEquals("1001", relayed.string("comment_id"))
        assertEquals("https://github.com/acme/shop/pull/7#discussion_r1001", relayed.string("url"))
        assertEquals("Mona Lisa", relayed.string("name"))
        assertEquals("mona", relayed.string("login"))
        assertFalse(relayed["bot"].asBoolean)
        assertEquals("https://avatars.githubusercontent.com/u/1", relayed.string("avatar_url"))
    }

    fun testALoginEndingInBotIsABot() {
        agent.add("relayed" to Relays.conversationComment(77, login = "dependabot[bot]"))

        assertTrue(agent.threads().single().messages().single().getAsJsonObject("relayed")["bot"].asBoolean)
    }

    fun testAThreadOfOnlyRelaysIsTheUsersMoveBeforeAndAfterTheyReadIt() {
        sample()
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))

        val summary = agent.summaryOf(id)
        assertEquals("user", summary.string("awaiting"))
        assertNull(summary["last_author"])
        user.read(id)
        assertEquals("user", agent.summaryOf(id).string("awaiting"))
        assertFalse(user.canAgree(id))
    }

    fun testAnUnreadRelayNeverChangesWhoseTurnItIs() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Thoughts?")

        agent.reply(id, "GitHub said so", "relayed" to Relays.reviewComment(2002))

        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
        assertEquals("user", agent.summaryOf(id).getAsJsonObject("last_author").string("kind"))
    }

    fun testANewRelayOnAReadFyiIsTheUsersMoveUntilTheyReadIt() {
        sample()
        val id = agent.addOnLine(5, "intent" to "fyi")
        user.read(id)

        agent.reply(id, "From the PR", "relayed" to Relays.reviewComment(3003))

        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
        user.read(id)
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
    }

    fun testRelayingTheSameCommentAgainCreatesNothing() {
        sample()
        val first = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))

        val again = agent.post("comment_add", "file" to SAMPLE, "line" to 9, "body" to "x", "relayed" to Relays.reviewComment(1001))

        assertEquals(200, again.status)
        assertTrue(again["existing"]!!.asBoolean)
        assertEquals(first, again["thread_id"]!!.asString)
        assertEquals(5, again["line"]!!.asInt)
        assertNull(again["line_adjusted"])
        assertEquals(listOf(first), agent.ids())
    }

    fun testARelayIsKeyedByItsKindAndId() {
        sample()
        agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))

        val conversation = agent.post("comment_add", "body" to "x", "relayed" to Relays.conversationComment(1001))

        assertNull(conversation["existing"])
        assertEquals(2, agent.ids().size)
    }

    fun testABatchCountsOnlyWhatItCreated() {
        sample()
        agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))

        val response = agent.post(
            "comment_add_batch",
            "items" to listOf(
                mapOf("file" to SAMPLE, "body" to "a", "relayed" to Relays.reviewComment(1001)),
                mapOf("file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1002)),
            ),
        )

        assertEquals(1, response["created"]!!.asInt)
        assertTrue(response.json.getAsJsonArray("results")[0].asJsonObject["existing"].asBoolean)
    }

    fun testAMalformedRelayIsATeaching400() {
        sample()
        val good = Relays.reviewComment(1001)
        val malformed = listOf(
            "'to' with 'relayed'" to mapOf("relayed" to good, "to" to "user"),
            "must be an object" to mapOf("relayed" to "github"),
            "'github'" to mapOf("relayed" to good + ("source" to "gitlab")),
            "missing 'login'" to mapOf("relayed" to good - "login"),
            "digits only" to mapOf("relayed" to good + ("comment_id" to "12a")),
            "is not the comment" to mapOf("relayed" to good + ("comment_id" to 999)),
            "https html_url" to mapOf("relayed" to good + ("url" to "http://github.com/acme/shop/pull/7#discussion_r1001")),
            "https html_url" to mapOf("relayed" to good + ("url" to "https://github.com/acme/shop/pull/7")),
            "doesn't start with '<'" to mapOf("relayed" to good + ("name" to "<script>")),
            "doesn't start with '<'" to mapOf("relayed" to good + ("name" to "x".repeat(101))),
            "GitHub login" to mapOf("relayed" to good + ("login" to "mona lisa")),
            "true or false" to mapOf("relayed" to good + ("bot" to "yes")),
            "'avatar_url'" to mapOf("relayed" to good + ("avatar_url" to "http://x")),
        )
        malformed.forEach { (mentioning, fields) ->
            assertRefused(agent.post("comment_add", mapOf("file" to SAMPLE, "body" to "b") + fields), 400, mentioning)
        }
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testARelayedReplyLandsOnceAndAnswersExistingAfterwards() {
        sample()
        val id = agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))

        val first = agent.reply(id, "reply on GitHub", "relayed" to Relays.reviewComment(1002))
        val again = agent.reply(id, "reply on GitHub", "relayed" to Relays.reviewComment(1002))

        assertNull(first["existing"])
        assertTrue(again["existing"]!!.asBoolean)
        assertEquals(first["message_id"]!!.asString, again["message_id"]!!.asString)
        assertEquals(2, agent.threads().single().messages().size)
    }

    fun testARelayedReplyAlreadyInAnotherThreadIsRefusedNamingIt() {
        sample()
        val home = agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))
        val other = agent.addOnLine(5)

        val response = agent.post("comment_reply", "thread_id" to other, "body" to "b", "relayed" to Relays.reviewComment(1001))

        assertRefused(response, 409, home)
        assertEquals("relayed_elsewhere", response["reason"]!!.asString)
        assertEquals(home, response["thread_id"]!!.asString)
    }

    fun testARelayedReplyReopensAResolvedThread() {
        sample()
        val id = agent.addOnLine(5)
        user.resolve(id)

        val response = agent.reply(id, "late GitHub reply", "relayed" to Relays.reviewComment(1002))

        assertEquals("open", response["status"]!!.asString)
    }

    fun testARelayedReplyLeavesAnOrphanOrphaned() {
        sample()
        val id = agent.addOnLine(5)
        eraseLine(5)
        agent.threads()

        assertEquals("orphaned", agent.reply(id, "late GitHub reply", "relayed" to Relays.reviewComment(1002))["status"]!!.asString)
    }

    fun testARelayedThreadTheUserDeletedStaysDeleted() {
        sample()
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))
        agent.reply(id, "reply", "relayed" to Relays.reviewComment(1002))
        user.delete(id)
        val other = agent.addOnLine(9)

        val again = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001))
        val asReply = agent.post("comment_reply", "thread_id" to other, "body" to "b", "relayed" to Relays.reviewComment(1001))
        val itsReply = agent.post("comment_reply", "thread_id" to other, "body" to "b", "relayed" to Relays.reviewComment(1002))

        assertRefused(again, 409, "1001")
        assertEquals("deleted_relay", again["reason"]!!.asString)
        assertRefused(asReply, 409, "1001")
        assertEquals("deleted_relay", asReply["reason"]!!.asString)
        assertEquals(200, itsReply.status)
        assertEquals(listOf(other), agent.ids())
    }

    fun testABatchItemRelayingADeletedThreadIsRefusedAlone() {
        sample()
        user.delete(agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001)))

        val response = agent.post(
            "comment_add_batch",
            "items" to listOf(
                mapOf("file" to SAMPLE, "body" to "a", "relayed" to Relays.reviewComment(1001)),
                mapOf("file" to SAMPLE, "body" to "b"),
            ),
        )

        assertEquals(1, response["created"]!!.asInt)
        assertEquals("deleted_relay", response.json.getAsJsonArray("results")[0].asJsonObject.string("reason"))
    }

    fun testAUserReplyIsAddressedWhenTheUserPicksAnAgent() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.addOnLine(9)

        assertEquals("@Claude · review ✕", user.addresseeShownWhileMentioning(id, "claude-review"))

        user.reply(id, "@review what do you think?", to = "claude-review")

        val message = agent.threads().single { it.id == id }.messages().last()
        assertEquals("claude-review", message.string("to"))
        assertEquals(Authors.user.displayName, message.getAsJsonObject("author").string("name"))
    }

    fun testTheUserCanReviseAMessageUntilAnAgentReadsIt() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Use a map.")
        val before = agent.summaryOf(id).string("updated_at")

        user.edit(id, "Use a sorted map.")

        val revised = reviewer.threads().single()
        assertEquals("Use a sorted map.", revised.messages().last().string("body"))
        assertTrue(Instant.parse(revised.string("updated_at")) > Instant.parse(before))
        assertFalse(user.canEdit(id))
    }

    // FixMe: Known issue - #57 an edit the user saves after an agent read the message is silently dropped
    fun testAnEditSavedAfterAnAgentReadTheMessageIsSilentlyDropped() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Use a map.")

        val composer = user.edit(id, "Use a sorted map.") { agent.threads() }

        assertEquals("", composer.text)
        assertEquals("Use a map.", agent.threads().single().messages().last().string("body"))
        assertEquals("Use a map.", user.thread(id).messages.last().body)
    }

    fun testClearingEverythingForgetsDeletedRelays() {
        sample()
        user.delete(agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001)))

        assertEquals(0, agent.post("comment_clear_all")["cleared"]!!.asInt)

        assertEquals(200, agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001)).status)
    }

    fun testClearingOneFileLetsItsRelaysComeBackButKeepsDeletedOnesOut() {
        sample()
        agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))
        user.delete(agent.add("file" to SAMPLE, "line" to 3, "relayed" to Relays.reviewComment(2002)))

        assertEquals(1, agent.post("comment_clear_all", "file" to SAMPLE)["cleared"]!!.asInt)

        assertNull(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001))["existing"])
        assertEquals("deleted_relay", agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(2002))["reason"]!!.asString)
    }
}
