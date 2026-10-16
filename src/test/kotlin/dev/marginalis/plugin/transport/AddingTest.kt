package dev.marginalis.plugin.transport

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.tab.MarginFile
import dev.marginalis.plugin.ui.tab.ProjectTab

class AddingTest : HttpContractTestCase() {

    fun testALineThreadAnswersWhereItLanded() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 12, "anchor_text" to lineText(12), "body" to "Why?")

        assertEquals(200, response.status)
        assertEquals(setOf("thread_id", "file", "line", "line_adjusted", "status"), response.json.keySet())
        assertEquals(SAMPLE, response["file"]!!.asString)
        assertEquals(12, response["line"]!!.asInt)
        assertFalse(response["line_adjusted"]!!.asBoolean)
        assertEquals("open", response["status"]!!.asString)
        assertEquals(response["thread_id"]!!.asString, agent.threads().single().id)
    }

    fun testALineThreadShowsInTheGutterOfItsLine() {
        sample()
        agent.addOnLine(12)

        val document = document()
        val marked = DocumentMarkupModel.forDocument(document, project, false)!!.allHighlighters
            .filter { it.gutterIconRenderer != null }
            .map { document.getLineNumber(it.startOffset) + 1 }

        assertEquals(listOf(12), marked)
    }

    fun testAnAnchorTextFoundNearbyCorrectsTheLine() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 12, "anchor_text" to lineText(30), "body" to "b")

        assertEquals(30, response["line"]!!.asInt)
        assertTrue(response["line_adjusted"]!!.asBoolean)
        assertEquals(30, agent.threads().single()["line"].asInt)
    }

    fun testTheNearestMatchWinsWhenTheAnchorTextRepeats() {
        myFixture.addFileToProject(SAMPLE, (1..40).joinToString("\n") { if (it % 10 == 0) "return null" else "val x$it = $it" })

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 17, "anchor_text" to "return null", "body" to "b")

        assertEquals(20, response["line"]!!.asInt)
    }

    fun testAnAnchorTextMatchesByTrimmedContainment() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 12, "anchor_text" to "  line12 = ", "body" to "b")

        assertEquals(12, response["line"]!!.asInt)
        assertFalse(response["line_adjusted"]!!.asBoolean)
    }

    fun testAnAnchorTextBeyondTwentyLinesIsAStaleAnchor() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 12, "anchor_text" to lineText(33), "body" to "b")

        assertRefused(response, 409, "re-read")
        assertEquals("stale_anchor", response["reason"]!!.asString)
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testAnAnchorTextExactlyTwentyLinesAwayIsFound() {
        sample()

        assertEquals(32, agent.post("comment_add", "file" to SAMPLE, "line" to 12, "anchor_text" to lineText(32), "body" to "b")["line"]!!.asInt)
    }

    fun testALineOutsideTheFileIsAStaleAnchor() {
        sample(lines = 10)

        listOf(0, -3, 12).forEach { line ->
            val response = agent.post("comment_add", "file" to SAMPLE, "line" to line, "body" to "b")
            assertRefused(response, 409, "out of range")
            assertEquals("stale_anchor", response["reason"]!!.asString)
        }
    }

    fun testWithoutAnchorTextTheLineIsTakenAsGiven() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "line" to 12, "body" to "b")

        assertEquals(12, response["line"]!!.asInt)
        assertFalse(response["line_adjusted"]!!.asBoolean)
        assertEquals(lineText(12), agent.threads().single().string("anchor_text"))
    }

    fun testWithoutALineTheThreadIsAboutTheFile() {
        sample()

        val response = agent.post("comment_add", "file" to SAMPLE, "body" to "This file does too much.")

        assertEquals(setOf("thread_id", "file", "status"), response.json.keySet())
        assertEquals(SAMPLE, response["file"]!!.asString)
    }

    fun testWithoutAFileTheThreadIsAboutTheProject() {
        val response = agent.post("comment_add", "body" to "Let's talk about the build.")

        assertEquals(setOf("thread_id", "status"), response.json.keySet())
        assertEquals("open", response["status"]!!.asString)
        assertNull(agent.threads().single()["file"])
    }

    fun testWithOneProjectOpenAProjectThreadNeedsNoProject() {
        assertEquals(200, agent.post("comment_add", "body" to "b", "project" to null).status)
    }

    fun testAnUnknownFileIsA404ListingOpenProjects() {
        listOf(
            agent.post("comment_add", "file" to "src/Gone.kt", "line" to 3, "body" to "b"),
            agent.post("comment_add", "file" to "src/Gone.kt", "body" to "b"),
        ).forEach { response ->
            assertRefused(response, 404, "src/Gone.kt")
            val listed = response.json.getAsJsonArray("open_projects").single().asJsonObject
            assertEquals(project.name, listed.string("name"))
            assertEquals("/src", listed.string("path"))
        }
    }

    fun testAnUnknownFileWithoutAProjectIsSearchedInEveryOpenProject() {
        assertRefused(agent.post("comment_add", "file" to "src/Gone.kt", "body" to "b", "project" to null), 404, "not found in any open project")
    }

    fun testAnIdWithoutANameIsShownAsTheAnonymousAgentName() {
        sample()
        agent.add("file" to SAMPLE, "author_name" to null)

        val author = agent.threads().single().messages().single().getAsJsonObject("author")

        assertEquals("Agent", author.string("name"))
        assertEquals("claude-main", author.string("id"))
    }

    fun testAnUnknownProjectIsA404ListingOpenProjects() {
        sample()

        listOf(
            agent.post("comment_add", "file" to SAMPLE, "body" to "b", "project" to "elsewhere"),
            agent.post("comment_add", "body" to "b", "project" to "elsewhere"),
        ).forEach { response ->
            assertRefused(response, 404, "elsewhere")
            assertNotNull(response["open_projects"])
        }
    }

    fun testAFileWithoutTextIsRefused() {
        val image = myFixture.addFileToProject("logo.png", "").virtualFile
        WriteAction.runAndWait<Throwable> { image.setBinaryContent(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0)) }

        assertRefused(agent.post("comment_add", "file" to "logo.png", "line" to 1, "body" to "b"), 400, "no text document")
        assertEquals(200, agent.post("comment_add", "file" to "logo.png", "body" to "b").status)
    }

    fun testAnchorTextWithoutALineIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "anchor_text" to "val", "body" to "b"), 400, "'anchor_text' without 'line'")
    }

    fun testALineWithoutAFileIsATeaching400() {
        assertRefused(agent.post("comment_add", "line" to 3, "body" to "b"), 400, "'line' without 'file'")
    }

    fun testALineThatIsNotANumberIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "line" to "12", "body" to "b"), 400, "'line' must be an integer")
    }

    fun testAMissingOrBlankBodyIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE), 400, "missing 'body'")
        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "  \n"), 400, "'body' is empty")
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testATextFieldThatIsNotAStringIsATeaching400() {
        sample()

        listOf("file", "body", "anchor_text", "walkthrough", "severity", "intent", "label", "to").forEach { field ->
            val response = agent.post("comment_add", mapOf("file" to SAMPLE, "body" to "b", field to listOf("x")))
            assertRefused(response, 400, "'$field' must be a string")
        }
    }

    fun testAnOrderThatIsNotANumberIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "order" to "first"), 400, "'order' must be an integer")
    }

    fun testSeverityIsBlockerOrNit() {
        sample()
        val blocker = agent.addOnLine(3, "severity" to "blocker")
        val nit = agent.addOnLine(5, "severity" to "NIT")

        assertEquals(listOf("blocker", "nit"), agent.threads().map { it.string("severity") })
        assertEquals(listOf(blocker, nit), agent.ids())
    }

    fun testAnyOtherSeverityIsATeaching400() {
        sample()

        listOf("high", "major", "").forEach { severity ->
            assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "severity" to severity), 400, "'blocker'")
        }
        assertEquals(emptyList<String>(), agent.ids())
    }

    fun testIntentIsOneOfFourWords() {
        sample()
        listOf("finding", "guidance", "question", "fyi").forEach { agent.add("file" to SAMPLE, "intent" to it) }

        assertEquals(setOf("finding", "guidance", "question", "fyi"), agent.threads().map { it.string("intent") }.toSet())
    }

    fun testAnyOtherIntentIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "intent" to "praise"), 400, "'fyi'")
    }

    fun testIntentAndSeverityCompose() {
        sample()
        agent.add("file" to SAMPLE, "intent" to "guidance", "severity" to "blocker")

        val thread = agent.threads().single()

        assertEquals("guidance", thread.string("intent"))
        assertEquals("blocker", thread.string("severity"))
    }

    fun testAnFyiTakesNoSeverity() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "intent" to "fyi", "severity" to "nit"), 400, "no severity")
    }

    fun testAnFyiLabelIsLowercased() {
        sample()
        agent.add("file" to SAMPLE, "intent" to "fyi", "label" to "Heads-Up")

        assertEquals("heads-up", agent.threads().single().string("label"))
    }

    fun testALabelOfTwentyCharactersWithSingleSpacesIsAccepted() {
        sample()
        agent.add("file" to SAMPLE, "intent" to "fyi", "label" to "copied from the docs")

        assertEquals("copied from the docs", agent.threads().single().string("label"))
    }

    fun testALabelOnAnythingButAnFyiIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "label" to "praise"), 400, "intent 'fyi'")
        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "intent" to "question", "label" to "praise"), 400, "intent 'fyi'")
    }

    fun testAMalformedLabelIsATeaching400() {
        sample()

        listOf("two  spaces", "under_score", "a".repeat(21), "-lead", "praise!", "").forEach { label ->
            assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "intent" to "fyi", "label" to label), 400, "label")
        }
    }

    fun testALabelThatIsAMarginWordIsATeaching400() {
        sample()

        listOf("blocker", "nit", "finding", "guidance", "question", "FYI").forEach { label ->
            assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "intent" to "fyi", "label" to label), 400, "vocabulary")
        }
    }

    fun testToAddressesTheFirstMessage() {
        sample()
        agent.add("file" to SAMPLE, "to" to " claude-review ")
        agent.add("file" to SAMPLE, "to" to "USER")

        assertEquals(listOf("claude-review", "user"), agent.threads().map { it.messages().single().string("to") })
    }

    fun testABlankToIsATeaching400() {
        sample()

        assertRefused(agent.post("comment_add", "file" to SAMPLE, "body" to "b", "to" to "  "), 400, "'to' is blank")
    }

    fun testWalkthroughStepsCarryOrderAndLabel() {
        sample()
        agent.addOnLine(30, "order" to 2, "walkthrough" to "A")
        agent.addOnLine(10, "order" to 1, "walkthrough" to "A")
        agent.add("order" to 3, "walkthrough" to "A")

        val steps = agent.threads().map { it["order"].asInt to it.string("walkthrough") }

        assertEquals(listOf(3 to "A", 1 to "A", 2 to "A"), steps)
    }

    fun testABatchAnswersPerItemInRequestOrder() {
        sample()
        val response = agent.post(
            "comment_add_batch",
            "items" to listOf(
                mapOf("file" to SAMPLE, "line" to 10, "anchor_text" to lineText(10), "body" to "one"),
                mapOf("file" to SAMPLE, "line" to 10, "anchor_text" to "nowhere to be found", "body" to "two"),
                mapOf("file" to "src/Gone.kt", "body" to "three"),
                "not an object",
                mapOf("body" to "five", "severity" to "major"),
                mapOf("file" to SAMPLE, "body" to "six"),
            ),
        )

        assertEquals(200, response.status)
        assertEquals(2, response["created"]!!.asInt)
        val results = response.json.getAsJsonArray("results").map { it.asJsonObject }
        assertEquals(10, results[0]["line"].asInt)
        assertEquals("stale_anchor", results[1].string("reason"))
        assertNotNull(results[1]["error"])
        assertNotNull(results[2]["open_projects"])
        assertTrue(results[3].string("error")!!.contains("JSON object"))
        assertTrue(results[4].string("error")!!.contains("'blocker'"))
        assertEquals(setOf("thread_id", "file", "status"), results[5].keySet())
        assertEquals(listOf(results[0].id, results[5].id).sorted(), agent.ids().sorted())
    }

    fun testABatchEnvelopeLendsIdentityAndProjectToItsItems() {
        sample()
        val response = agent.post(
            "comment_add_batch",
            "author_name" to "Claude · design", "author_id" to "claude-design",
            "items" to listOf(
                mapOf("file" to SAMPLE, "body" to "mine"),
                mapOf("file" to SAMPLE, "body" to "theirs", "author_name" to "Claude · impl", "author_id" to "claude-impl"),
            ),
        )

        assertEquals(2, response["created"]!!.asInt)
        val authors = agent.threads().map { it.messages().single().getAsJsonObject("author").string("id") }
        assertEquals(setOf("claude-design", "claude-impl"), authors.toSet())
    }

    fun testABatchEnvelopeProjectThatMatchesNothingFailsEveryItemWithoutOne() {
        sample()
        val response = agent.post(
            "comment_add_batch",
            "project" to "elsewhere",
            "items" to listOf(mapOf("file" to SAMPLE, "body" to "a"), mapOf("file" to SAMPLE, "body" to "b", "project" to project.name)),
        )

        val results = response.json.getAsJsonArray("results").map { it.asJsonObject }
        assertEquals(1, response["created"]!!.asInt)
        assertTrue(results[0].string("error")!!.contains("'elsewhere'"))
        assertNotNull(results[1]["thread_id"])
    }

    fun testABatchEnvelopeToIsNotLentToItems() {
        sample()
        agent.post("comment_add_batch", "to" to "claude-review", "items" to listOf(mapOf("file" to SAMPLE, "body" to "a")))

        assertNull(agent.threads().single().messages().single()["to"])
    }

    fun testABatchWithoutAnItemsArrayIsATeaching400() {
        assertRefused(agent.post("comment_add_batch"), 400, "missing 'items'")
        assertRefused(agent.post("comment_add_batch", "items" to mapOf("body" to "b")), 400, "missing 'items'")
    }

    fun testAnEmptyBatchCreatesNothing() {
        val response = agent.post("comment_add_batch", "items" to emptyList<Any>())

        assertEquals(0, response["created"]!!.asInt)
        assertEquals(0, response.json.getAsJsonArray("results").size())
    }

    fun testAnAgentCommentInAFileOutOfSightNotifiesTheUser() {
        sample()
        val shown = notifications()

        agent.addOnLine(12, "body" to "Look **here**")

        assertEquals(1, shown.size)
        assertEquals("Claude · $SAMPLE:12", shown.single().title)
        assertEquals("Look here", shown.single().content)
        assertEquals(listOf("Open Thread"), shown.single().actions.map { it.templateText })
    }

    fun testTheNotificationOpensTheThread() {
        sample()
        val shown = notifications()
        agent.addOnLine(12)
        val notification = shown.single()

        Notification.fire(notification, notification.actions.single(), null)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(11, editor.caretModel.logicalPosition.line)
        assertNotNull(UIUtil.findComponentOfType(editor.contentComponent, ThreadPanel::class.java))
    }

    fun testAProjectThreadNotifiesWithTheProjectNameWhenTheMarginTabIsNotInFront() {
        sample()
        myFixture.openFileInEditor(myFixture.findFileInTempDir(SAMPLE))
        val shown = notifications()

        agent.add("body" to "Build is slow")

        assertEquals("Claude · ${project.name}", shown.single().title)
    }

    fun testAProjectThreadDoesNotNotifyWhileTheMarginTabIsInFront() {
        FileEditorManager.getInstance(project).openFile(MarginFile, true)
        assertTrue(ProjectTab.isInFront(project))
        val shown = notifications()

        agent.add("body" to "Build is slow")

        assertEquals(emptyList<Notification>(), shown)
    }

    fun testAnAgentCommentInTheFileTheUserIsReadingDoesNotNotify() {
        sample()
        myFixture.openFileInEditor(myFixture.findFileInTempDir(SAMPLE))
        val shown = notifications()

        agent.addOnLine(12)

        assertEquals(emptyList<Notification>(), shown)
    }

    fun testNoNotificationWhenTheUserTurnedThemOff() {
        sample()
        val settings = MarginalisSettings.getInstance().state
        settings.notifyOnAgentReply = false
        try {
            val shown = notifications()
            agent.addOnLine(12)
            assertEquals(emptyList<Notification>(), shown)
        } finally {
            settings.notifyOnAgentReply = true
        }
    }

    fun testARelayedCommentNeverNotifies() {
        sample()
        val shown = notifications()

        agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(42))

        assertEquals(emptyList<Notification>(), shown)
    }

    fun testAnAgentReplyNotifiesTheUser() {
        sample()
        val id = agent.addOnLine(12)
        val shown = notifications()

        agent.reply(id, "One more thing")

        assertEquals("One more thing", shown.single().content)
    }

    private fun notifications(): List<Notification> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "Marginalis") shown += notification
                }
            },
        )
        return object : AbstractList<Notification>() {
            override val size: Int get() = settle().size
            override fun get(index: Int): Notification = settle()[index]
            private fun settle(): List<Notification> {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                return shown
            }
        }
    }
}
