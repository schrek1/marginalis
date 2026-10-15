package dev.marginalis.plugin.store

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.testFramework.LoggedErrorProcessor
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.SavedProjectTestCase
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class SavedFileTest : SavedProjectTestCase() {

    fun testADamagedEntrySkipsOnlyThatEntry() {
        openShop(saved = saved(GOOD, MISSING_ID, BAD_DATE, "42", "\"text\"", PROJECT_LEVEL))

        val listed = agent.threads().associateBy { it["thread_id"].asString }

        assertEquals(setOf("good", "plan"), listed.keys)
        assertEquals(5, listed.getValue("good")["line"].asInt)
        assertEquals("2026-10-01T10:05:00Z", listed.getValue("good").string("updated_at"))
        assertEquals(listOf(5), gutterLinesShowing("kept"))
    }

    fun testADamagedMessageSkipsOnlyThatMessage() {
        openShop(saved = saved(GOOD))

        assertEquals(1, agent.summaryOf("good")["unread"].asInt)
        val messages = agent.thread("good").messages()

        assertEquals(listOf("kept", "also kept"), messages.map { it.string("body") })
        assertEquals(listOf(null, true), messages.map { it["newly_seen"]?.asBoolean })
    }

    fun testADamagedRoundAndDamagedRelayKeysAreIgnored() {
        val text = """{"version":1,"handed_back_at":"last tuesday","threads":[$PROJECT_LEVEL],"deleted_relays":[7,{"kind":"review"},{"kind":"review_comment","comment_id":"1001"}]}"""
        openShop(saved = text)

        val again = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1001))
        val other = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "relayed" to Relays.reviewComment(1002))

        assertNull(agent.get("comment_list")["handed_back_at"])
        assertEquals(listOf("plan", other["thread_id"]!!.asString), agent.threads().map { it["thread_id"].asString })
        assertEquals("deleted_relay", again["reason"]?.asString)
        assertEquals(other.body, 200, other.status)
    }

    fun testALegacyFileStillOpens() {
        val legacy = """{"threads":[{"id":"old","file":"$SAMPLE","tour":"onboarding","order":2,"status":"resolved","resolved_by":{"kind":"HUMAN","name":"Ada"},
            |"created_at":"2026-01-01T00:00:00Z","messages":[{"id":"m","author":{"kind":"HUMAN","name":"Ada"},"body":"first","created_at":"2026-01-01T00:00:00Z"},
            |{"id":"n","author":{"kind":"AGENT","name":"Claude"},"body":"second","created_at":"2026-01-01T00:01:00Z","seen_by_agent":true}]}]}""".trimMargin()
        openShop(saved = legacy)

        val old = agent.thread("old")

        assertEquals("onboarding", old.string("walkthrough"))
        assertEquals(2, old["order"].asInt)
        assertEquals("resolved", old.string("status"))
        assertEquals("Ada", old.string("resolved_by"))
        assertEquals("2026-01-01T00:01:00Z", old.string("updated_at"))
        assertEquals(listOf("user", "agent"), old.messages().map { it.getAsJsonObject("author").string("kind") })
    }

    // FixMe: Known issue - #53 a legacy line thread saved without anchor text orphans on reopen, or jumps to the nearest blank line
    fun testALegacyLineThreadWithoutAnchorTextJumpsToTheNearestBlankLine() {
        val text = sampleText().lines().toMutableList().apply { set(7, "") }.joinToString("\n")
        val legacy = """{"threads":[{"id":"old","file":"$SAMPLE","line":4,"status":"OPEN","created_at":"2026-01-01T00:00:00Z",
            |"messages":[{"id":"m","author":{"kind":"AGENT","name":"Claude"},"body":"about line five","created_at":"2026-01-01T00:00:00Z"}]}]}""".trimMargin()
        openShop(files = mapOf(SAMPLE to text), saved = legacy)

        assertEquals(8, agent.thread("old")["line"].asInt)
        assertEquals(listOf(8), gutterLinesShowing("about line five"))
    }

    fun testAFileWithoutThreadsOpensEmpty() {
        openShop(saved = """{"version":1}""")

        assertEquals(emptyList<JsonObject>(), agent.threads())
    }

    // FixMe: Known issue - #56 an unreadable saved file opens as an empty margin and is overwritten by the next change, losing every thread in it
    fun testAnUnreadableFileOpensEmptyAndIsReplacedByTheNextChange() {
        openShop(saved = "{" + saved(GOOD))
        assertEquals(emptyList<JsonObject>(), agent.threads())
        assertTrue(Files.readString(shop.savedThreads).contains("kept"))

        val id = agent.addOnLine(9)
        waitForSave(id)

        assertFalse(Files.readString(shop.savedThreads).contains("kept"))
    }

    fun testAMarginThatCannotBeSavedStillWorksWhileTheProjectIsOpen() {
        openShop(files = mapOf(SAMPLE to sampleText(), ".idea/marginalis.json/keep" to ""))

        val warnings = CopyOnWriteArrayList<String>()
        val id = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return super.processWarn(category, message, t)
            }
        }).use {
            agent.addOnLine(9, "body" to "unsaved").also {
                waitUntil("the failed save is reported") { warnings.any { it.startsWith("Failed to save margin threads") } }
            }
        }

        assertEquals("unsaved", agent.thread(id).messages().single().string("body"))
        assertTrue(Files.isDirectory(shop.savedThreads))
    }

    fun testTheSavedFileIsReadableJsonWithEveryThread() {
        openShop()
        val line = agent.addOnLine(5)
        val file = agent.add("file" to SAMPLE)
        val project = agent.add()
        waitForSave(project)

        val saved = JsonParser.parseString(Files.readString(shop.savedThreads)).asJsonObject

        assertEquals(1, saved["version"].asInt)
        assertEquals(listOf(line, file, project), saved.getAsJsonArray("threads").map { it.asJsonObject["id"].asString })
    }

    private fun saved(vararg threads: String) = """{"version":1,"threads":[${threads.joinToString(",")}]}"""

    private companion object {
        val GOOD = """{"id":"good","file":"$SAMPLE","line":4,"anchor_text":"val line5 = 5","status":"OPEN",
            |"created_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-01T10:05:00Z","messages":[
            |{"id":"m1","author":{"kind":"AGENT","id":"claude-main","name":"Claude"},"body":"kept","created_at":"2026-10-01T10:00:00Z","seen_by":["claude-main"]},
            |{"id":"m2","author":{"kind":"USER"},"body":"lost","created_at":"2026-10-01T10:01:00Z"},
            |{"id":"m3","author":{"kind":"USER","name":"Ada"},"body":"also kept","created_at":"2026-10-01T10:02:00Z"}]}""".trimMargin()
        val MISSING_ID = """{"file":"$SAMPLE","status":"OPEN","created_at":"2026-10-01T10:00:00Z","messages":[]}"""
        val BAD_DATE = """{"id":"bad-date","status":"OPEN","created_at":"yesterday","messages":[]}"""
        val PROJECT_LEVEL = """{"id":"plan","status":"OPEN","created_at":"2026-10-01T11:00:00Z","messages":[
            |{"id":"p1","author":{"kind":"USER","name":"Ada"},"body":"release plan","created_at":"2026-10-01T11:00:00Z"}]}""".trimMargin()
    }
}
