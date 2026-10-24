package dev.marginalis.plugin.transport

import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.testing.Relays
import java.net.Socket
import java.net.URLEncoder
import java.time.Instant
import org.jetbrains.ide.BuiltInServerManager

class WaitingTest : HttpContractTestCase() {

    fun testASubmittedRoundWakesTheWaitingAgentWithWhatItOwes() {
        sample()
        val owed = agent.addOnLine(5)
        agent.addOnLine(9)
        user.reply(owed, "Please split this.")
        val waiting = agent.startWait()

        user.submitRound()
        val wake = agent.await(waiting)

        assertEquals(setOf("handed_back", "reason", "handed_back_at", "awaiting"), wake.json.keySet())
        assertTrue(wake["handed_back"]!!.asBoolean)
        assertEquals("hand_back", wake["reason"]!!.asString)
        val awaiting = wake.json.getAsJsonArray("awaiting").map { it.asJsonObject }
        assertEquals(listOf(owed), awaiting.map { it.id })
        assertEquals(project.name, awaiting.single().string("project"))
        assertEquals(listOf(null, true), awaiting.single().messages().map { it["newly_seen"]?.asBoolean })
        assertEquals(0, agent.get("comment_list")["marked_seen"]!!.asInt)
        assertEquals(wake["handed_back_at"]!!.asString, agent.get("comment_list")["handed_back_at"]!!.asString)
    }

    fun testSubmitAndSendRoundFromTheComposerWakesTheAgent() {
        sample()
        val id = agent.addOnLine(5)
        val waiting = agent.startWait()

        user.replyAndSendRound(id, "Here is my batch.")

        val wake = agent.await(waiting)
        assertEquals("hand_back", wake["reason"]!!.asString)
        assertEquals("Here is my batch.", wake.json.getAsJsonArray("awaiting")[0].asJsonObject.messages().last().string("body"))
    }

    fun testSubmitRoundIsEnabledOnlyWhileSomeAgentIsListening() {
        assertFalse(user.canSubmitRound())

        val waiting = agent.startWait()

        assertTrue(user.canSubmitRound())
        user.submitRound()
        agent.await(waiting)
        assertFalse(user.canSubmitRound())
    }

    fun testARoundSubmittedWhileTheAgentWorkedAnswersItsNextWaitAtOnce() {
        sample()
        val id = agent.addOnLine(5)
        val first = agent.startWait()
        user.submitRound()
        val cursor = agent.await(first)["handed_back_at"]!!.asString
        user.reply(id, "One more.")
        user.submitRound()

        val wake = agent.get("comment_wait", "since" to cursor)

        assertEquals("hand_back", wake["reason"]!!.asString)
        assertTrue(Instant.parse(wake["handed_back_at"]!!.asString) > Instant.parse(cursor))
        assertEquals(listOf(id), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testARoundAlreadyAnsweredNeverWakesTheAgentTwice() {
        val first = agent.startWait()
        user.submitRound()
        val cursor = agent.await(first)["handed_back_at"]!!.asString

        val wake = agent.get("comment_wait", "since" to cursor, "timeout" to 1)

        assertEquals("timeout", wake["reason"]!!.asString)
    }

    fun testWithoutSinceTheAgentWaitsForTheNextRound() {
        user.submitRound()

        val wake = agent.get("comment_wait", "timeout" to 1)

        assertEquals(setOf("handed_back", "reason"), wake.json.keySet())
        assertFalse(wake["handed_back"]!!.asBoolean)
        assertEquals("timeout", wake["reason"]!!.asString)
    }

    fun testAMalformedTimeoutOrSinceIsATeaching400() {
        listOf("soon", "-1", "1.5", "").forEach { assertRefused(agent.get("comment_wait", "timeout" to it), 400, "whole number of seconds") }
        assertRefused(agent.get("comment_wait", "since" to "2026-13-01"), 400, "ISO-8601")
        assertFalse(user.canSubmitRound())
    }

    fun testATimeoutBeyondTheCapIsAccepted() {
        val waiting = agent.startWait("timeout" to "99999999999999999999")

        user.stop("Claude")

        assertEquals("stopped", agent.await(waiting)["reason"]!!.asString)
    }

    fun testAnUnknownProjectIsA404ListingOpenProjects() {
        val response = agent.get("comment_wait", "project" to "elsewhere")

        assertRefused(response, 404, "elsewhere")
        assertNotNull(response["open_projects"])
    }

    fun testStopAnswersTheWaitWithStopped() {
        val waiting = agent.startWait()

        user.stop("Claude")

        val wake = agent.await(waiting)
        assertEquals(setOf("handed_back", "reason"), wake.json.keySet())
        assertEquals("stopped", wake["reason"]!!.asString)
        assertEquals(emptyList<String>(), user.stoppableAgents())
    }

    fun testAStopPressedWhileTheAgentWorkedAnswersItsNextWait() {
        sample()
        user.reply(agent.addOnLine(5), "Fix it.")
        val first = agent.startWait()
        user.submitRound()
        agent.await(first)
        assertEquals(listOf("Claude"), user.stoppableAgents())

        user.stop("Claude")

        assertEquals("stopped", agent.get("comment_wait", "timeout" to 5)["reason"]!!.asString)
        assertEquals("timeout", agent.get("comment_wait", "timeout" to 1)["reason"]!!.asString)
    }

    fun testAnEmptyRoundEndsAOneShotAgentsPresence() {
        val waiting = agent.startWait()

        user.submitRound()

        assertEquals(0, agent.await(waiting).json.getAsJsonArray("awaiting").size())
        assertEquals(emptyList<String>(), user.stoppableAgents())
    }

    fun testAnAgentThatStaysRemainsPresentAfterAnEmptyRoundAndATimeout() {
        val waiting = agent.startWait("stay" to true)
        user.submitRound()
        agent.await(waiting)

        assertEquals(listOf("Claude"), user.stoppableAgents())
        agent.get("comment_wait", "stay" to true, "timeout" to 1)
        assertEquals(listOf("Claude"), user.stoppableAgents())
    }

    fun testARoundWakesOnlyTheAgentsWithSomethingAwaitingThem() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "For you, Claude.", to = "claude-main")
        val mine = agent.startWait()
        val theirs = reviewer.startWait("timeout" to 2)

        user.submitRound()

        assertEquals(listOf(id), agent.await(mine).json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
        assertFalse(theirs.isDone)
        assertEquals("timeout", reviewer.await(theirs)["reason"]!!.asString)
    }

    fun testARoundThatPassedAnAgentByDoesNotAnswerItsNextWait() {
        sample()
        user.reply(agent.addOnLine(5), "For you, Claude.", to = "claude-main")
        val mine = agent.startWait()
        user.submitRound()
        agent.await(mine)

        val theirs = reviewer.get("comment_wait", "since" to "2000-01-01T00:00:00Z", "timeout" to 1)

        assertEquals("timeout", theirs["reason"]!!.asString)
    }

    fun testWhenNobodyOwesAnythingEveryWaiterWakesToAnEmptyRound() {
        val mine = agent.startWait()
        val theirs = reviewer.startWait()

        user.submitRound()

        listOf(agent.await(mine), reviewer.await(theirs)).forEach { wake ->
            assertEquals("hand_back", wake["reason"]!!.asString)
            assertEquals(0, wake.json.getAsJsonArray("awaiting").size())
        }
    }

    fun testHangingUpAWaitStopsListening() {
        val port = BuiltInServerManager.getInstance().waitForStart().port
        val query = "project=${URLEncoder.encode(project.name, Charsets.UTF_8)}&author_name=Claude&author_id=claude-main"
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /api/marginalis/comment_wait?$query HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            PlatformTestUtil.waitWithEventsDispatching("never listened", { user.canSubmitRound() }, 10)
        }

        PlatformTestUtil.waitWithEventsDispatching("still listening", { user.stoppableAgents().isEmpty() }, 10)
        assertFalse(user.canSubmitRound())
    }

    fun testWaitingMakesTheAgentListeningInIdentities() {
        val waiting = agent.startWait("timeout" to 1)

        val listed = agent.get("comment_identities").json.getAsJsonArray("identities").map { it.asJsonObject }

        assertTrue(listed.single { it.string("id") == "claude-main" }["waiting"].asBoolean)
        agent.await(waiting)
    }

    fun testGoingLiveOnAThreadWithUnseenMessagesWakesTheAgentAtOnce() {
        sample()
        val id = agent.addOnLine(5)
        user.reply(id, "Typed before you listened.")
        val waiting = agent.startWait()
        val wentLiveAfter = Instant.now()

        user.goLive(id)

        val wake = agent.await(waiting)
        assertEquals("live", wake["reason"]!!.asString)
        assertFalse(wake.instant("handed_back_at").isBefore(wentLiveAfter))
        assertEquals(listOf(id), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
        assertNull(agent.get("comment_list")["handed_back_at"])
    }

    fun testASubmitInALiveThreadWakesTheAgentWithThatThreadAlone() {
        sample()
        val live = agent.addOnLine(5)
        val other = agent.addOnLine(9)
        user.reply(other, "Later.")
        agent.threads()
        val waiting = agent.startWait()
        user.goLive(live)
        assertFalse(waiting.isDone)

        user.reply(live, "Now, please.")

        val wake = agent.await(waiting)
        assertEquals("live", wake["reason"]!!.asString)
        assertEquals(listOf(live), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
        assertTrue(wake.json.getAsJsonArray("awaiting")[0].asJsonObject.messages().last()["newly_seen"].asBoolean)
    }

    fun testAnAgreeInALiveThreadWakesTheAgent() {
        sample()
        val id = agent.addOnLine(5, "body" to "Extract it?")
        agent.threads()
        val waiting = agent.startWait()
        user.goLive(id)

        user.agree(id)

        val wake = agent.await(waiting)
        assertEquals("live", wake["reason"]!!.asString)
        assertTrue(wake.json.getAsJsonArray("awaiting")[0].asJsonObject.messages().last()["agrees"].asBoolean)
    }

    fun testALiveSubmitWakesTheAgentThatSpokeLastNotEveryone() {
        sample()
        val id = reviewer.addOnLine(5)
        val mine = agent.startWait("timeout" to 2)
        val theirs = reviewer.startWait()
        user.goLive(id)

        user.reply(id, "Go on.")

        assertEquals("live", reviewer.await(theirs)["reason"]!!.asString)
        assertFalse(mine.isDone)
        assertEquals("timeout", agent.await(mine)["reason"]!!.asString)
    }

    fun testALiveSubmitAddressedToAnAgentWakesThatAgent() {
        sample()
        val id = reviewer.addOnLine(5)
        val mine = agent.startWait()
        val theirs = reviewer.startWait("timeout" to 2)
        user.goLive(id)

        user.reply(id, "Claude, take this one.", to = "claude-main")

        assertEquals("live", agent.await(mine)["reason"]!!.asString)
        assertEquals("timeout", reviewer.await(theirs)["reason"]!!.asString)
    }

    fun testALiveSubmitMadeWhileTheAgentWorkedAnswersItsNextWaitAtOnce() {
        sample()
        val id = agent.addOnLine(5)
        val first = agent.startWait()
        user.goLive(id)
        user.reply(id, "First.")
        val cursor = agent.await(first)["handed_back_at"]!!.asString
        agent.reply(id, "On it.")

        user.reply(id, "Second.")

        val wake = agent.get("comment_wait", "since" to cursor, "timeout" to 5)
        assertEquals("live", wake["reason"]!!.asString)
        assertEquals("Second.", wake.json.getAsJsonArray("awaiting")[0].asJsonObject.messages().last().string("body"))
    }

    fun testWhenARoundIsPendingTooTheRoundWinsAndCarriesTheLiveThread() {
        sample()
        val live = agent.addOnLine(5)
        val other = agent.addOnLine(9)
        val first = agent.startWait()
        user.goLive(live)
        user.reply(live, "First.")
        val cursor = agent.await(first)["handed_back_at"]!!.asString
        user.reply(other, "Round reply.")
        user.reply(live, "Live follow-up.")
        user.submitRound()

        val wake = agent.get("comment_wait", "since" to cursor, "timeout" to 5)

        assertEquals("hand_back", wake["reason"]!!.asString)
        assertEquals(listOf(live, other), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testALiveFollowUpTheAgentHasNotSeenRidesTheRoundEvenWhenTheAgentSpokeLast() {
        sample()
        val live = agent.addOnLine(5)
        val first = agent.startWait()
        user.goLive(live)
        user.reply(live, "First.")
        val cursor = agent.await(first)["handed_back_at"]!!.asString
        user.reply(live, "Follow-up.")
        agent.reply(live, "Answered the first.")
        user.submitRound()

        val wake = agent.get("comment_wait", "since" to cursor, "timeout" to 5)

        assertEquals("hand_back", wake["reason"]!!.asString)
        assertEquals(listOf(live), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testResolvingALiveThreadEndsItsLiveness() {
        sample()
        val id = agent.addOnLine(5)
        val waiting = agent.startWait("timeout" to 2)
        user.goLive(id)

        agent.post("comment_resolve", "thread_id" to id)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(user.isLive(id))
        assertEquals("timeout", agent.await(waiting)["reason"]!!.asString)
    }

    fun testALiveSubmitOnAThreadTheUserStartedWakesTheOneWaitingAgent() {
        sample()
        val id = user.commentOnSelection(SAMPLE, 12, "line12", "Thoughts?")
        agent.threads()
        val waiting = agent.startWait()
        user.goLive(id)

        user.reply(id, "Well?")

        val wake = agent.await(waiting)
        assertEquals("live", wake["reason"]!!.asString)
        assertEquals(Instant.parse(agent.summaryOf(id).string("updated_at")), wake.instant("handed_back_at"))
    }

    fun testALiveSubmitOnAThreadOfOnlyRelaysWakesTheAgentThatRelayedThem() {
        sample()
        val id = reviewer.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))
        val mine = agent.startWait("timeout" to 2)
        val theirs = reviewer.startWait()
        user.goLive(id)

        user.reply(id, "What does Mona mean?")

        assertEquals("live", reviewer.await(theirs)["reason"]!!.asString)
        assertEquals("timeout", agent.await(mine)["reason"]!!.asString)
    }
}
