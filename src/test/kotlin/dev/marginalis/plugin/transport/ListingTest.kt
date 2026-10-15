package dev.marginalis.plugin.transport

import com.intellij.openapi.command.WriteCommandAction
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Message
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.testing.Agent
import java.time.Instant

class ListingTest : HttpContractTestCase() {

    fun testAThreadListsEveryDocumentedField() {
        sample()
        val id = agent.addOnLine(12, "body" to "Why `compute` here?")

        val thread = reviewer.threads().single()

        assertEquals(id, thread.id)
        assertEquals(project.name, thread.string("project"))
        assertEquals(SAMPLE, thread.string("file"))
        assertEquals(12, thread["line"].asInt)
        assertEquals(lineText(12), thread.string("anchor_text"))
        assertEquals("open", thread.string("status"))
        Instant.parse(thread.string("created_at"))
        Instant.parse(thread.string("updated_at"))
        val message = thread.messages().single()
        Instant.parse(message.string("created_at"))
        assertEquals("Why `compute` here?", message.string("body"))
        assertEquals(setOf("kind", "name", "id"), message.getAsJsonObject("author").keySet())
        assertEquals("agent", message.getAsJsonObject("author").string("kind"))
        assertEquals("Claude", message.getAsJsonObject("author").string("name"))
        assertEquals("claude-main", message.getAsJsonObject("author").string("id"))
        assertEquals(listOf("claude-main", "claude-review"), message["seen_by"].strings())
        assertTrue(message["newly_seen"].asBoolean)
    }

    fun testOptionalFieldsAreAbsentUntilSet() {
        sample()
        agent.addOnLine(12)

        val thread = agent.threads().single()

        listOf("segment", "order", "walkthrough", "severity", "intent", "label", "resolved_by").forEach { assertNull(it, thread[it]) }
        listOf("to", "agrees", "relayed", "referenced", "newly_seen").forEach { assertNull(it, thread.messages().single()[it]) }
    }

    fun testSetFieldsAreListed() {
        sample()
        agent.addOnLine(12, "order" to 2, "walkthrough" to "A", "severity" to "blocker", "intent" to "guidance", "to" to "claude-review")
        agent.addOnLine(14, "intent" to "fyi", "label" to "praise")

        val (guidance, fyi) = agent.threads()

        assertEquals(2, guidance["order"].asInt)
        assertEquals("A", guidance.string("walkthrough"))
        assertEquals("blocker", guidance.string("severity"))
        assertEquals("guidance", guidance.string("intent"))
        assertEquals("claude-review", guidance.messages().single().string("to"))
        assertEquals("fyi", fyi.string("intent"))
        assertEquals("praise", fyi.string("label"))
    }

    fun testAnAuthorCarriesAnIdOnlyWhenTheAgentGaveOne() {
        sample()
        agent.post("comment_add", "file" to SAMPLE, "body" to "no id", "author_id" to null)

        val author = agent.threads().single().messages().single().getAsJsonObject("author")

        assertEquals(setOf("kind", "name"), author.keySet())
        assertEquals("Claude", author.string("name"))
    }

    fun testAUserMessageIsAuthoredByTheUser() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Because.")

        val author = agent.threads().single().messages().last().getAsJsonObject("author")

        assertEquals("user", author.string("kind"))
        assertEquals(Authors.user.displayName, author.string("name"))
        assertNull(author["id"])
    }

    fun testListingMarksMessagesSeenForTheCallerOnly() {
        sample()
        agent.addOnLine(5)

        val first = reviewer.get("comment_list")
        val second = reviewer.get("comment_list")
        val third = agentNamed("Claude · impl", "claude-impl").get("comment_list")

        assertEquals(1, first["marked_seen"]!!.asInt)
        assertEquals(0, second["marked_seen"]!!.asInt)
        assertNull(second.json.getAsJsonArray("threads")[0].asJsonObject.messages().single()["newly_seen"])
        assertEquals(1, third["marked_seen"]!!.asInt)
        assertEquals(
            listOf("claude-impl", "claude-main", "claude-review"),
            third.json.getAsJsonArray("threads")[0].asJsonObject.messages().single()["seen_by"].strings(),
        )
    }

    fun testAnAgentsOwnMessagesAreBornSeenByIt() {
        sample()
        agent.addOnLine(5)

        assertEquals(0, agent.get("comment_list")["marked_seen"]!!.asInt)
    }

    fun testAUserReplyIsBornUnreadForEveryAgent() {
        sample()
        val id = agent.addOnLine(5)
        agent.threads()
        user.reply(id, "Please rename it.")

        val unread = agent.get("comment_list", "unread_only" to true)
        assertEquals(1, unread["marked_seen"]!!.asInt)
        assertEquals(listOf(null, true), unread.json.getAsJsonArray("threads")[0].asJsonObject.messages().map { it["newly_seen"]?.asBoolean })
        assertEquals(emptyList<String>(), agent.ids("unread_only" to true))
        assertEquals(listOf(id), reviewer.ids("unread_only" to true))
    }

    fun testUnreadOnlyListsOnlyThreadsHoldingSomethingUnseen() {
        sample()
        agent.addOnLine(5)
        val unseen = agent.addOnLine(7)
        reviewer.reply(unseen, "and this")

        assertEquals(listOf(unseen), agent.ids("unread_only" to true))
    }

    fun testWithoutAnIdTheReceiptIsKeyedByTheName() {
        sample()
        agent.addOnLine(5)

        agent.get("comment_list", "author_name" to "Solo", "author_id" to null)

        assertEquals(listOf("Solo", "claude-main"), agent.threads().single().messages().single()["seen_by"].strings())
    }

    fun testUnidentifiedCallersShareOneIdentityAndConsumeEachOthersUnread() {
        sample()
        agent.addOnLine(5)

        val first = agent.get("comment_list", "author_name" to null, "author_id" to null)
        val second = reviewer.get("comment_list", "author_name" to null, "author_id" to null)

        assertEquals(1, first["marked_seen"]!!.asInt)
        assertEquals(0, second["marked_seen"]!!.asInt)
        assertEquals(listOf("Agent", "claude-main"), agent.threads().single().messages().single()["seen_by"].strings())
    }

    fun testFileFilterNarrowsToThatPath() {
        sample()
        sample("src/Other.kt")
        val here = agent.addOnLine(5)
        agent.addOnLine(5, file = "src/Other.kt")

        assertEquals(listOf(here), agent.ids("file" to SAMPLE))
        assertEquals(emptyList<String>(), agent.ids("file" to "src/Missing.kt"))
    }

    fun testStatusFilterNarrowsToOpenResolvedOrOrphaned() {
        sample()
        val open = agent.addOnLine(5)
        val resolved = agent.addOnLine(7)
        val orphaned = agent.addOnLine(9)
        agent.post("comment_resolve", "thread_id" to resolved)
        eraseLine(9)
        agent.threads()

        assertEquals(listOf(open), agent.ids("status" to "open"))
        assertEquals(listOf(resolved), agent.ids("status" to "resolved"))
        assertEquals(listOf(orphaned), agent.ids("status" to "orphaned"))
        assertEquals(listOf(open), agent.ids("status" to "OPEN"))
    }

    // FixMe: Known issue - #55 a status filter judges a thread whose line just vanished by its stale status
    fun testAThreadWhoseLineJustVanishedIsFilteredByItsStaleStatus() {
        sample()
        val id = agent.addOnLine(9)
        eraseLine(9)

        assertEquals(emptyList<String>(), agent.ids("status" to "orphaned"))
        val listedAsOpen = agent.threads("status" to "open").single()
        assertEquals(id, listedAsOpen.id)
        assertEquals("orphaned", listedAsOpen.string("status"))
        assertEquals(listOf(id), agent.ids("status" to "orphaned"))
    }

    fun testAnUnknownStatusIsATeaching400() {
        assertRefused(agent.get("comment_list", "status" to "closed"), 400, "open|resolved|orphaned")
    }

    fun testIntentFilterNarrowsToThatIntent() {
        sample()
        val guidance = agent.addOnLine(5, "intent" to "guidance")
        agent.addOnLine(7, "intent" to "question")
        agent.addOnLine(9)

        assertEquals(listOf(guidance), agent.ids("intent" to "guidance"))
        assertEquals(listOf(guidance), agent.ids("intent" to "Guidance"))
    }

    fun testAnUnknownIntentIsATeaching400() {
        assertRefused(agent.get("comment_list", "intent" to "praise"), 400, "'finding'")
    }

    fun testAwaitingAgentListsThreadsWhereTheUserSpokeLast() {
        sample()
        val answered = agent.addOnLine(5)
        val pending = agent.addOnLine(7)
        user.reply(pending, "Not convinced.")

        assertEquals(listOf(pending), agent.ids("awaiting" to "agent"))
        assertEquals(listOf(answered), agent.ids("awaiting" to "user"))
        assertEquals(listOf(pending), agent.ids("awaiting" to "AGENT"))
    }

    fun testReadingDoesNotShrinkAwaitingOnlyAReplyOrResolveDoes() {
        sample()
        val replied = agent.addOnLine(5)
        val resolved = agent.addOnLine(7)
        user.reply(replied, "Change it?")
        user.reply(resolved, "And this?")
        agent.threads()

        assertEquals(listOf(replied, resolved), agent.ids("awaiting" to "agent"))
        agent.reply(replied, "Done.")
        agent.post("comment_resolve", "thread_id" to resolved)
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
    }

    fun testAwaitingComposesWithOtherFilters() {
        sample()
        sample("src/Other.kt")
        val here = agent.addOnLine(5, "intent" to "question")
        val there = agent.addOnLine(5, "intent" to "question", file = "src/Other.kt")
        user.reply(here, "a")
        user.reply(there, "b")

        assertEquals(listOf(here), agent.ids("awaiting" to "agent", "file" to SAMPLE, "intent" to "question", "status" to "open"))
    }

    fun testAnUnknownAwaitingIsATeaching400() {
        assertRefused(agent.get("comment_list", "awaiting" to "me"), 400, "'agent'")
    }

    fun testAThreadTheUserAddressedToAnotherAgentIsTheirDebtNotYours() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.threads()
        user.reply(id, "Review this, please.", to = "claude-review")

        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
        assertEquals(listOf(id), reviewer.ids("awaiting" to "agent"))
        assertNull(agent.summaryOf(id)["awaiting"])
        assertEquals("agent", reviewer.summaryOf(id).string("awaiting"))
        assertEquals("claude-review", agent.threads().single().messages().last().string("to"))
    }

    fun testAnAgentCanHandAThreadToAnotherAgent() {
        sample()
        val id = agent.addOnLine(5, "to" to "claude-review")

        assertEquals(listOf(id), reviewer.ids("awaiting" to "agent"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "user"))
        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent"))
    }

    fun testAMessageAddressedToTheUserAwaitsTheUser() {
        sample()
        val id = agent.addOnLine(5)
        reviewer.reply(id, "Over to you.", "to" to "user")

        assertEquals(listOf(id), agent.ids("awaiting" to "user"))
        assertEquals(listOf(id), reviewer.ids("awaiting" to "user"))
    }

    fun testAnUnaddressedAgentMessageIsEveryAgentsDebtWhenTheUserSpokeLast() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Anyone?")

        assertEquals(listOf(id), reviewer.ids("awaiting" to "agent"))
        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
    }

    fun testUnaddressedCallersAwaitAsTheAnonymousAgent() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "To you, Claude.", to = "claude-main")

        assertEquals(emptyList<String>(), agent.ids("awaiting" to "agent", "author_name" to null, "author_id" to null))
        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
    }

    fun testSummaryCarriesCountsInsteadOfBodiesAndMarksNothingSeen() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Hmm.")

        val response = reviewer.get("comment_list", "summary" to true)

        assertEquals(0, response["marked_seen"]!!.asInt)
        val summary = response.json.getAsJsonArray("threads").single().asJsonObject
        assertEquals(2, summary["messages"].asInt)
        assertEquals(2, summary["unread"].asInt)
        assertEquals("agent", summary.string("awaiting"))
        assertEquals("user", summary.getAsJsonObject("last_author").string("kind"))
        assertEquals(SAMPLE, summary.string("file"))
        assertEquals(5, summary["line"].asInt)
        assertEquals(2, reviewer.get("comment_list")["marked_seen"]!!.asInt)
        assertEquals(0, reviewer.summaryOf(id)["unread"].asInt)
    }

    fun testSummaryLastAuthorIsTheAgentWhoSpokeLast() {
        sample()
        val id = agent.addOnLine(5)

        val summary = reviewer.summaryOf(id)

        assertEquals("claude-main", summary.getAsJsonObject("last_author").string("id"))
        assertEquals("user", summary.string("awaiting"))
        assertEquals(1, summary["unread"].asInt)
    }

    fun testSummaryAwaitingIsAbsentOnceTheThreadIsClosed() {
        sample()
        val id = agent.addOnLine(5)
        agent.post("comment_resolve", "thread_id" to id)

        assertNull(agent.summaryOf(id)["awaiting"])
    }

    fun testSummaryComposesWithOtherFilters() {
        sample()
        agent.addOnLine(5)
        val asked = agent.addOnLine(7, "intent" to "question")

        assertEquals(listOf(asked), agent.ids("summary" to true, "intent" to "question"))
    }

    fun testUpdatedAfterReturnsOnlyThreadsThatMovedStrictlySince() {
        sample()
        val quiet = agent.addOnLine(5)
        val moving = agent.addOnLine(7)
        val cursor = agent.threads().maxOf { Instant.parse(it.string("updated_at")) }

        assertEquals(emptyList<String>(), agent.ids("updated_after" to cursor))
        user.reply(moving, "Moved on.")
        assertEquals(listOf(moving), agent.ids("updated_after" to cursor))
        assertEquals(listOf(quiet, moving), agent.ids())
    }

    fun testUpdatedAfterIncludesAThreadTheUserResolved() {
        sample()
        val id = agent.addOnLine(5)
        val cursor = Instant.parse(agent.threads().single().string("updated_at"))

        user.resolve(id)

        val listed = agent.threads("updated_after" to cursor, "status" to "resolved").single()
        assertEquals(Authors.user.displayName, listed.string("resolved_by"))
    }

    fun testUpdatedAtDoesNotMoveWhenAThreadIsReadOrItsLineDrifts() {
        sample()
        agent.addOnLine(20)
        val before = agent.threads().single().string("updated_at")

        reviewer.threads()
        insertLinesAbove(10, 3)

        val after = agent.threads().single()
        assertEquals(before, after.string("updated_at"))
        assertEquals(23, after["line"].asInt)
    }

    fun testUpdatedAtMovesWhenTheThreadIsOrphaned() {
        sample()
        agent.addOnLine(20)
        val before = Instant.parse(agent.threads().single().string("updated_at"))

        eraseLine(20)

        val after = agent.threads().single()
        assertEquals("orphaned", after.string("status"))
        assertTrue(Instant.parse(after.string("updated_at")) > before)
    }

    fun testAMalformedUpdatedAfterIsATeaching400() {
        assertRefused(agent.get("comment_list", "updated_after" to "yesterday"), 400, "ISO-8601")
    }

    fun testAnchorTextIsTheLineAsItStandsNow() {
        sample()
        agent.addOnLine(20)
        val document = document()

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineEndOffset(19), " // renamed later")
        }

        assertEquals("${lineText(20)} // renamed later", agent.threads().single().string("anchor_text"))
    }

    fun testThreadsArriveInReadingOrder() {
        sample("b/Z.kt")
        sample("a/Y.kt")
        val zLine = agent.addOnLine(9, file = "b/Z.kt")
        val yLate = agent.addOnLine(30, file = "a/Y.kt")
        val yFile = agent.add("file" to "a/Y.kt")
        val projectLevel = agent.add()
        val yEarly = agent.addOnLine(3, file = "a/Y.kt")
        val zFile = agent.add("file" to "b/Z.kt")

        assertEquals(listOf(projectLevel, yFile, yEarly, yLate, zFile, zLine), agent.ids())
        assertEquals(listOf(projectLevel, yFile, yEarly, yLate, zFile, zLine), agent.ids("summary" to true))
    }

    fun testProjectAndFileLevelThreadsListOnlyWhatTheyHave() {
        sample()
        agent.add("file" to SAMPLE)
        agent.add()

        val (projectLevel, fileLevel) = agent.threads()

        assertNull(projectLevel["file"])
        assertNull(projectLevel["line"])
        assertNull(projectLevel["anchor_text"])
        assertEquals(SAMPLE, fileLevel.string("file"))
        assertNull(fileLevel["line"])
        assertNull(fileLevel["anchor_text"])
    }

    fun testAUsersSelectionIsListedAsTheThreadsSegment() {
        sample()

        val id = user.commentOnSelection(SAMPLE, 12, "line12", "Rename this?")

        val thread = agent.threads().single()
        assertEquals(id, thread.id)
        assertEquals(12, thread["line"].asInt)
        val segment = thread.getAsJsonObject("segment")
        assertEquals("line12", segment.string("exact"))
        assertEquals("val ", segment.string("prefix"))
        assertEquals(" = 12", segment.string("suffix"))
        assertEquals("user", thread.messages().single().getAsJsonObject("author").string("kind"))
        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
    }

    fun testASegmentAtTheStartOfALineHasNoPrefix() {
        myFixture.addFileToProject(SAMPLE, "compute()\n")

        user.commentOnSelection(SAMPLE, 1, "compute()", "Why?")

        assertEquals(setOf("exact"), agent.threads().single().getAsJsonObject("segment").keySet())
    }

    fun testAnAgreeIsListedAsApprovalAddressedToTheAgent() {
        sample()
        val id = agent.addOnLine(5, "body" to "Shall I extract it?")
        user.agree(id)

        val agreement = agent.threads().single().messages().last()

        assertEquals("Agreed.", agreement.string("body"))
        assertTrue(agreement["agrees"].asBoolean)
        assertEquals("claude-main", agreement.string("to"))
        assertEquals(listOf(id), agent.ids("awaiting" to "agent"))
        assertEquals(emptyList<String>(), reviewer.ids("awaiting" to "agent"))
    }

    fun testHandedBackAtAppearsInTheEnvelopeOnceTheUserSubmitsARound() {
        assertNull(agent.get("comment_list")["handed_back_at"])

        val waiting = agent.startWait()
        user.submitRound()
        val wake = agent.await(waiting)

        assertEquals(wake["handed_back_at"]!!.asString, agent.get("comment_list")["handed_back_at"]!!.asString)
    }

    fun testARefResolvesToTheThreadWithTheNamedMessageMarkedReferenced() {
        sample()
        val id = agent.addOnLine(5)
        agent.addOnLine(7)
        val reply = agent.reply(id, "second")["message_id"]!!.asString

        val listed = agent.threads("ref" to "mg:${reply.take(8)}").single()

        assertEquals(id, listed.id)
        assertEquals(listOf(null, true), listed.messages().map { it["referenced"]?.asBoolean })
    }

    fun testARefToAThreadMarksNoMessage() {
        sample()
        val id = agent.addOnLine(5)

        val listed = agent.threads("ref" to "MG:${id.take(12).uppercase()}").single()

        assertEquals(id, listed.id)
        assertNull(listed.messages().single()["referenced"])
    }

    fun testARefComposesWithOtherFilters() {
        sample()
        val id = agent.addOnLine(5)

        assertEquals(emptyList<String>(), agent.ids("ref" to "mg:${id.take(8)}", "status" to "resolved"))
    }

    fun testARefMatchingNothingIsA404() {
        val response = agent.get("comment_list", "ref" to "mg:00000000")

        assertRefused(response, 404, "mg:00000000")
        assertNotNull(response["open_projects"])
    }

    fun testARefMatchingNothingAnywhereSaysSo() {
        assertRefused(agent.get("comment_list", "ref" to "mg:00000000", "project" to null), 404, "any open project")
    }

    fun testAMalformedRefIsATeaching400() {
        assertRefused(agent.get("comment_list", "ref" to "3d4770ad"), 400, "mg:")
        assertRefused(agent.get("comment_list", "ref" to "mg:3d47"), 400, "mg:")
    }

    fun testAnAmbiguousRefIsA400WithUnambiguousCandidates() {
        sample()
        val thread = CommentThread(SAMPLE, null, null, id = "abcdef12-0000-4000-8000-000000000001")
        val message = Message(Authors.user, "first", id = "abcdef12-1111-4000-8000-000000000002")
        thread.addMessage(message)
        store.threads.add(thread)
        val projectLevel = CommentThread(null, null, null, id = "abcdef12-2222-4000-8000-000000000003")
        projectLevel.addMessage(Message(Authors.user, "second"))
        store.threads.add(projectLevel)

        val response = agent.get("comment_list", "ref" to "mg:abcdef12")

        assertRefused(response, 400, "mg:abcdef12")
        val candidates = response.json.getAsJsonArray("candidates").map { it.asJsonObject }
        val byRef = candidates.associateBy { it.string("ref") }
        assertEquals(
            setOf("mg:abcdef12-0000-4000-8000-000000000001", "mg:abcdef12-1111-4000-8000-000000000002", "mg:abcdef12-2222-4000-8000-000000000003"),
            byRef.keys,
        )
        val messageCandidate = byRef.getValue("mg:abcdef12-1111-4000-8000-000000000002")
        assertEquals(thread.id, messageCandidate.string("thread_id"))
        assertEquals(message.id, messageCandidate.string("message_id"))
        assertEquals(project.name, messageCandidate.string("project"))
        assertEquals(SAMPLE, messageCandidate.string("file"))
        assertEquals(setOf("ref", "thread_id", "project"), byRef.getValue("mg:abcdef12-2222-4000-8000-000000000003").keySet())
        assertEquals(setOf("ref", "thread_id", "project", "file"), byRef.getValue("mg:abcdef12-0000-4000-8000-000000000001").keySet())
        assertEquals(listOf(thread.id), agent.ids("ref" to "mg:abcdef12-0000-4000-8000-000000000001"))
    }

    private fun agentNamed(name: String, id: String) = Agent(project, name, id)
}
