package dev.marginalis.plugin.store

import com.google.gson.JsonObject
import com.intellij.openapi.components.impl.stores.stateStore
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.SavedProjectTestCase
import java.nio.file.Files

class ReopeningTheProjectTest : SavedProjectTestCase() {

    private val observer get() = Agent(shop.project, "Observer", "observer")

    fun testAConversationComesBackExactlyAsItWasLeft() {
        openShop(mapOf(SAMPLE to sampleText(), OTHER to sampleText()))
        val step = agent.addOnLine(12, "body" to "check the bounds", "severity" to "blocker", "order" to 1, "walkthrough" to "SHOP-7")
        val fyi = agent.add("file" to OTHER, "body" to "nice split", "intent" to "fyi", "label" to "praise")
        val plan = agent.add("body" to "release plan", "intent" to "question", "to" to "claude-review")
        val settled = agent.addOnLine(30, "body" to "rename this")
        val agreed = agent.addOnLine(40, "body" to "drop the cache")
        user.reply(step, "bounds are checked upstream")
        agent.reply(step, "then a comment would help")
        reviewer.reply(plan, "ship on Friday")
        user.resolve(settled)
        agent.post("comment_resolve", "thread_id" to agreed)
        agent.reply(fyi, "last word")
        waitForSave("last word")
        val before = observer.listing()

        reopen()

        assertEquals(before, observer.listing())
        val after = observer.threads().associateBy { it["thread_id"].asString }
        assertEquals(setOf(step, fyi, plan, settled, agreed), after.keys)
        assertEquals(listOf("open", "open", "open", "resolved", "resolved"), listOf(step, fyi, plan, settled, agreed).map { after.getValue(it).string("status") })
        assertEquals("Claude", after.getValue(agreed).string("resolved_by"))
        assertEquals(listOf("check the bounds", "bounds are checked upstream", "then a comment would help"), after.getValue(step).messages().map { it.string("body") })
        assertEquals(listOf(12, 30, 40), listOf(step, settled, agreed).map { after.getValue(it)["line"].asInt })
    }

    fun testReopeningTwiceNeitherLosesNorDuplicatesAThread() {
        openShop()
        val ids = listOf(agent.addOnLine(5), agent.add("file" to SAMPLE), agent.add())
        user.reply(ids[0], "read it twice")
        waitForSave("read it twice")

        reopen()
        reopen()

        assertEquals(ids.toSet(), agent.threads().map { it["thread_id"].asString }.toSet())
        assertEquals(3, agent.threads().size)
        assertEquals(listOf(2, 1, 1), ids.map { agent.summaryOf(it)["messages"].asInt })
    }

    fun testAThreadTheUserDeletedStaysDeletedAfterReopening() {
        openShop()
        val kept = agent.addOnLine(5)
        val deleted = agent.addOnLine(9)
        waitForSave(deleted)
        user.delete(deleted)
        waitUntil("the deletion is saved") { !Files.readString(shop.savedThreads).contains(deleted) }

        reopen()

        assertEquals(listOf(kept), agent.threads().map { it["thread_id"].asString })
    }

    // FixMe: Known issue - #57 an agent's read receipts are lost on reopen unless a later change was saved before closing
    fun testAnAgentsReadReceiptsAreLostWhenNothingChangesAfterTheRead() {
        openShop()
        val id = agent.addOnLine(5)
        user.reply(id, "over to you")
        waitForSave("over to you")
        assertEquals(1, agent.get("comment_list")["marked_seen"]!!.asInt)
        assertEquals(0, agent.summaryOf(id)["unread"].asInt)

        reopen()

        assertEquals(1, agent.summaryOf(id)["unread"].asInt)
        assertTrue(agent.thread(id).messages().last()["newly_seen"].asBoolean)
    }

    fun testAnAgentsReadReceiptsSurviveOnceALaterChangeIsSaved() {
        openShop()
        val id = agent.addOnLine(5)
        user.reply(id, "over to you")
        waitForSave("over to you")
        agent.threads()
        val later = agent.addOnLine(9)
        waitForSave(later)

        reopen()

        assertEquals(0, agent.summaryOf(id)["unread"].asInt)
        assertEquals(listOf("claude-main"), agent.thread(id).messages().last().getAsJsonArray("seen_by").map { it.asString })
    }

    fun testWhatTheUserReadStaysReadAfterReopening() {
        openShop()
        val fyi = agent.addOnLine(5, "body" to "for your information", "intent" to "fyi")
        val question = agent.addOnLine(9, "body" to "why?", "intent" to "question")
        assertEquals("user", agent.summaryOf(fyi).string("awaiting"))
        user.read(fyi)
        user.read(question)
        waitForSave("read_by_user")

        reopen()

        assertNull(agent.summaryOf(fyi).string("awaiting"))
        assertEquals("user", agent.summaryOf(question).string("awaiting"))
        assertEquals(listOf(0, 0), listOf(fyi, question).map { storedThread(it).unreadByUserCount() })
    }

    fun testTheLastRoundSurvivesReopening() {
        openShop()
        val id = agent.addOnLine(5)
        waitForSave(id)
        user.submitRound()
        val handedBack = agent.get("comment_list")["handed_back_at"]!!.asString
        waitForSave(handedBack)

        reopen()

        assertEquals(handedBack, agent.get("comment_list")["handed_back_at"]!!.asString)
    }

    fun testAProjectWithoutARoundHasNoHandedBackAtAfterReopening() {
        openShop()
        val id = agent.addOnLine(5)
        waitForSave(id)

        reopen()

        assertNull(agent.get("comment_list")["handed_back_at"])
    }

    fun testARelayedThreadTheUserDeletedIsNotRecreatedAfterReopening() {
        openShop()
        val relayed = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))
        waitForSave(relayed)
        user.delete(relayed)
        waitForSave("deleted_relays")

        reopen()

        val again = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001))
        assertEquals(409, again.status)
        assertEquals("deleted_relay", again["reason"]!!.asString)
        assertEquals(emptyList<JsonObject>(), agent.threads())
    }

    fun testClearingTheMarginStaysClearedAndLetsADeletedRelayComeBackAfterReopening() {
        openShop()
        val relayed = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))
        val other = agent.addOnLine(9)
        waitForSave(other)
        user.delete(relayed)
        waitForSave("deleted_relays")
        assertEquals(1, agent.post("comment_clear_all", "project" to shop.name)["cleared"]!!.asInt)
        waitUntil("the clearing is saved") { !Files.readString(shop.savedThreads).contains(other) }

        reopen()

        assertEquals(emptyList<JsonObject>(), agent.threads())
        val again = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001))
        assertEquals(again.body, 200, again.status)
        assertEquals(1, agent.threads().size)
    }

    fun testClearingAMarginThatHoldsOnlyADeletedRelayIsSaved() {
        openShop()
        val relayed = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "relayed" to Relays.reviewComment(1001))
        waitForSave(relayed)
        user.delete(relayed)
        waitForSave("deleted_relays")

        assertEquals(0, agent.post("comment_clear_all", "project" to shop.name)["cleared"]!!.asInt)

        waitUntil("the clearing is saved") { !Files.readString(shop.savedThreads).contains("deleted_relays") }
        reopen()
        assertEquals(200, agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001)).status)
    }

    fun testAChangeMadeJustBeforeClosingIsNotLost() {
        openShop()
        val id = agent.addOnLine(5, "body" to "said at the door")

        reopen()

        assertEquals("said at the door", agent.thread(id).messages().single().string("body"))
    }

    fun testABurstOfChangesIsSavedInFull() {
        openShop()
        val ids = (1..15).map { agent.addOnLine(it) }
        waitForSave(ids.last())

        val saved = Files.readString(shop.savedThreads)

        assertTrue(ids.all { saved.contains(it) })
        reopen()
        assertEquals(ids.toSet(), agent.threads().map { it["thread_id"].asString }.toSet())
    }

    fun testAnEmptyMarginOpensEmptyAndWritesNothing() {
        openShop()

        reopen()

        assertEquals(emptyList<JsonObject>(), agent.threads())
        assertFalse(Files.exists(shop.savedThreads))
    }

    fun testALiveThreadIsNoLongerLiveAfterReopening() {
        openShop()
        val id = agent.addOnLine(5)
        // The first editor in a fresh project takes seconds to open; opened later, it would outlast the 1 s wait.
        user.open(id)
        val waiting = agent.getAsync("comment_wait", "timeout" to 1)
        waitUntil("Claude listens") { user.canGoLive(id) }
        user.goLive(id)
        agent.await(waiting)
        assertTrue(user.isLive(id))
        waitForSave(id)

        reopen()

        assertFalse(user.isLive(id))
    }

    fun testALiveProjectStaysLiveAfterReopeningButAThreadSwitchedOffIsLiveAgain() {
        openShop()
        val id = agent.addOnLine(5)
        user.goProjectLive()
        user.stopLive(id)
        assertFalse(user.isLive(id))
        waitForSave(id)
        shop.project.stateStore.saveComponent(ProjectLive.getInstance(shop.project))

        reopen()

        assertTrue(user.isProjectLive())
        assertTrue(user.isLive(id))
    }

    fun testAThreadResolvedBeforeReopeningWakesTheAgentWhenReopenedInALiveProject() {
        openShop()
        val id = agent.addOnLine(5)
        val later = agent.addOnLine(9)
        agent.threads()
        user.reply(id, "One more thing.")
        user.resolve(id)
        waitForSave("RESOLVED")
        waitForSave(later)

        reopen()
        user.goProjectLive()
        val waiting = agent.startWait("timeout" to 60)
        user.reopen(id)

        val woken = agent.await(waiting)
        assertEquals("live", woken["reason"]!!.asString)
        assertEquals(listOf(id), woken.json.getAsJsonArray("awaiting").map { it.asJsonObject["thread_id"].asString })
    }

    private fun Agent.listing(): String {
        val listing = get("comment_list").json
        listing.remove("marked_seen")
        listing.getAsJsonArray("threads").forEach { thread ->
            thread.asJsonObject.getAsJsonArray("messages").forEach { it.asJsonObject.remove("newly_seen") }
        }
        return listing.toString()
    }

    private companion object {
        const val OTHER = "src/Other.kt"
    }
}
