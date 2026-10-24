package dev.marginalis.plugin.store

import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.plugin.transport.HttpContractTestCase

class LiveThreadsTest : HttpContractTestCase() {

    private fun goLiveWhileClaudeBrieflyListens(id: String) {
        val waiting = agent.startWait("timeout" to 1)
        user.goLive(id)
        agent.await(waiting)
    }

    fun testTurningLiveOffStopsASubmitFromWakingTheAgent() {
        sample()
        val id = agent.addOnLine(5)
        agent.threads()
        val waiting = agent.startWait("timeout" to 60)
        user.goLive(id)
        assertTrue(user.isLive(id))

        user.stopLive(id)
        user.reply(id, "Not now.")

        assertFalse(user.isLive(id))
        assertFalse(waiting.isDone)
        user.submitRound()
        assertEquals("hand_back", agent.await(waiting)["reason"]!!.asString)
    }

    fun testTurningLiveOffWithdrawsASubmitTheAgentHasNotPickedUp() {
        sample()
        val id = agent.addOnLine(5)
        agent.threads()
        val cursor = agent.summaryOf(id).string("updated_at")
        goLiveWhileClaudeBrieflyListens(id)
        user.reply(id, "Now, please.")

        user.stopLive(id)

        assertEquals("timeout", agent.get("comment_wait", "since" to cursor, "timeout" to 1)["reason"]!!.asString)
    }

    fun testALiveSubmitTheAgentHasNotPickedUpWakesItsNextWait() {
        sample()
        val id = agent.addOnLine(5)
        agent.threads()
        val cursor = agent.summaryOf(id).string("updated_at")
        goLiveWhileClaudeBrieflyListens(id)

        user.reply(id, "Now, please.")

        assertEquals("live", agent.get("comment_wait", "since" to cursor, "timeout" to 1)["reason"]!!.asString)
    }

    fun testResolvingALiveThreadEndsLiveForGood() {
        sample()
        val id = agent.addOnLine(5)
        goLiveWhileClaudeBrieflyListens(id)
        assertTrue(user.isLive(id))

        agent.post("comment_resolve", "thread_id" to id)
        agent.post("comment_reopen", "thread_id" to id)

        assertFalse(user.isLive(id))
    }

    fun testALiveThreadWhoseLineWasDeletedStopsBeingLiveOnceOrphaned() {
        sample()
        val id = agent.addOnLine(5)
        goLiveWhileClaudeBrieflyListens(id)
        eraseLine(5)

        agent.threads()

        assertEquals("orphaned", agent.threads().single().string("status"))
        assertFalse(user.isLive(id))
    }

    fun testALiveThreadOrphanedByAReplyAfterARewriteStopsBeingLive() {
        sample()
        val id = agent.addOnLine(20)
        goLiveWhileClaudeBrieflyListens(id)
        rewrite()

        agent.reply(id, "Still relevant after the rewrite?")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertFalse(user.isLive(id))
        val waiting = agent.startWait("timeout" to 1)
        user.reply(id, "It went with the rewrite.")
        assertEquals("timeout", agent.await(waiting)["reason"]!!.asString)
    }

    fun testAThreadSwitchedLiveOnItsOwnStaysOffOnceRescuedFromOrphaning() {
        sample()
        val id = agent.addOnLine(20)
        goLiveWhileClaudeBrieflyListens(id)
        rewrite { if (it == 55) lineText(20) else "// rewritten $it" }
        agent.reply(id, "Still relevant after the rewrite?")

        agent.post("comment_reanchor", "thread_id" to id, "line" to 55, "anchor_text" to lineText(20))

        assertEquals("open", agent.threads().single().string("status"))
        assertFalse(user.isLive(id))
    }

    fun testAThreadThatOrphanedInALiveProjectIsLiveAgainOnceRescued() {
        sample()
        val id = agent.addOnLine(20)
        user.goProjectLive()
        rewrite { if (it == 55) lineText(20) else "// rewritten $it" }
        agent.reply(id, "Still relevant after the rewrite?")
        assertFalse(user.isLive(id))

        agent.post("comment_reanchor", "thread_id" to id, "line" to 55, "anchor_text" to lineText(20))

        assertTrue(user.isLive(id))
    }

    fun testAThreadThatOrphanedInALiveProjectIsLiveAgainOnceReopened() {
        sample()
        val id = agent.addOnLine(20)
        user.goProjectLive()
        rewrite()
        agent.reply(id, "Still relevant after the rewrite?")
        assertFalse(user.isLive(id))

        agent.post("comment_reopen", "thread_id" to id)

        assertTrue(user.isLive(id))
    }

    fun testTheUserCanSwitchARescuedThreadLiveAgain() {
        sample()
        val id = agent.addOnLine(20)
        goLiveWhileClaudeBrieflyListens(id)
        rewrite { if (it == 55) lineText(20) else "// rewritten $it" }
        agent.reply(id, "Still relevant after the rewrite?")
        agent.post("comment_reanchor", "thread_id" to id, "line" to 55, "anchor_text" to lineText(20))

        goLiveWhileClaudeBrieflyListens(id)

        assertTrue(user.isLive(id))
    }

    fun testDeletingALiveThreadEndsLive() {
        sample()
        val id = agent.addOnLine(5)
        goLiveWhileClaudeBrieflyListens(id)
        val thread = user.thread(id)

        user.delete(id)

        assertFalse(store.isLive(thread))
    }

    fun testALiveThreadKeepsWakingTheAgentItWokeAfterThatAgentStoppedListening() {
        sample()
        val id = user.commentOnSelection(SAMPLE, 5, "line5", "Take a look.")
        val first = agent.startWait()
        user.goLive(id)
        val woken = agent.await(first)
        assertEquals("live", woken["reason"]!!.asString)

        user.reply(id, "And this too.")

        val next = agent.get("comment_wait", "since" to woken["handed_back_at"]!!.asString, "timeout" to 2)
        assertEquals("live", next["reason"]!!.asString)
        assertEquals(listOf(id), next.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testWithNoAgentListeningLiveCannotBeSwitchedOn() {
        sample()
        val id = user.commentOnSelection(SAMPLE, 5, "line5", "Anyone?")

        assertFalse(user.canGoLive(id))
        assertFalse(user.isLive(id))
    }
}
