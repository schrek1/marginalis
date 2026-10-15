package dev.marginalis.plugin.transport

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.marginalis.plugin.settings.MarginalisSettings

class AnchoringTest : HttpContractTestCase() {

    fun testErasingTheAnchoredLineOrphansTheThread() {
        sample()
        val id = agent.addOnLine(20)

        eraseLine(20)

        val orphan = agent.threads().single()
        assertEquals(id, orphan.id)
        assertEquals("orphaned", orphan.string("status"))
        assertEquals(20, orphan["line"].asInt)
    }

    // FixMe: Known issue - #55 an orphan's anchor_text shows whatever now sits at its old line number, not the content it lost
    fun testAnOrphansAnchorTextIsTheNeighbouringLine() {
        sample()
        agent.addOnLine(20)

        eraseLine(20)

        assertEquals(lineText(21), agent.threads().single().string("anchor_text"))
    }

    // FixMe: Known issue - #53 deleting a thread's line with Delete Line moves the thread onto the next line instead of orphaning it
    fun testDeletingTheAnchoredLineMovesTheThreadOntoTheNextLine() {
        sample()
        agent.addOnLine(20)
        myFixture.openFileInEditor(myFixture.findFileInTempDir(SAMPLE))
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(19, 2))

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_DELETE_LINE)

        val thread = agent.threads().single()
        assertEquals("open", thread.string("status"))
        assertEquals(20, thread["line"].asInt)
        assertEquals(lineText(21), thread.string("anchor_text"))
    }

    fun testEmptyingTheAnchoredLineKeepsTheThreadOpenOnTheBlankLine() {
        sample()
        agent.addOnLine(20)
        val document = document()

        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(document.getLineStartOffset(19), document.getLineEndOffset(19))
        }

        val thread = agent.threads().single()
        assertEquals("open", thread.string("status"))
        assertEquals("", thread.string("anchor_text"))
    }

    fun testAnOrphanIsRescuedToAFreshVerifiedAnchor() {
        sample()
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()
        insertLinesAbove(5, 1)

        val response = agent.post("comment_reanchor", "thread_id" to id, "line" to 30, "anchor_text" to lineText(25))

        assertEquals(setOf("thread_id", "line", "status"), response.json.keySet())
        assertEquals(25, response["line"]!!.asInt)
        assertEquals("open", response["status"]!!.asString)
        val rescued = agent.threads().single()
        assertEquals("open", rescued.string("status"))
        assertEquals(25, rescued["line"].asInt)
        assertEquals(lineText(25), rescued.string("anchor_text"))
    }

    fun testARescueWithoutAnchorTextTakesTheLineAsGiven() {
        sample()
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()

        val response = agent.post("comment_reanchor", "thread_id" to id, "line" to 30)

        assertEquals(30, response["line"]!!.asInt)
        assertEquals(lineText(31), agent.threads().single().string("anchor_text"))
    }

    fun testARescueNamingTheThreadsOwnFileIsAccepted() {
        sample()
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()

        assertEquals(200, agent.post("comment_reanchor", "thread_id" to id, "line" to 3, "file" to SAMPLE).status)
    }

    fun testARescueToAnotherFileIsATeaching400() {
        sample()
        sample("src/Other.kt")
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()

        assertRefused(agent.post("comment_reanchor", "thread_id" to id, "line" to 3, "file" to "src/Other.kt"), 400, "cross-file")
    }

    fun testARescueWithAStaleAnchorLeavesTheThreadOrphaned() {
        sample()
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()

        val response = agent.post("comment_reanchor", "thread_id" to id, "line" to 3, "anchor_text" to "gone for good")

        assertRefused(response, 409, "re-read")
        assertEquals("stale_anchor", response["reason"]!!.asString)
        assertEquals("orphaned", agent.threads().single().string("status"))
    }

    fun testRescuingAnOpenThreadIsRefusedAsNotOrphaned() {
        sample()
        val id = agent.addOnLine(20)

        val response = agent.post("comment_reanchor", "thread_id" to id, "line" to 3)

        assertRefused(response, 409, "not orphaned")
        assertEquals("not_orphaned", response["reason"]!!.asString)
    }

    fun testRescuingAResolvedThreadIsRefusedAsNotOrphaned() {
        sample()
        val id = agent.addOnLine(20)
        agent.post("comment_resolve", "thread_id" to id)

        assertEquals("not_orphaned", agent.post("comment_reanchor", "thread_id" to id, "line" to 3)["reason"]!!.asString)
    }

    fun testFileAndProjectThreadsHaveNoAnchorToRescue() {
        sample()
        val fileLevel = agent.add("file" to SAMPLE)
        val projectLevel = agent.add()

        assertRefused(agent.post("comment_reanchor", "thread_id" to fileLevel, "line" to 3), 400, "the file as a whole")
        assertRefused(agent.post("comment_reanchor", "thread_id" to projectLevel, "line" to 3), 400, "the project as a whole")
    }

    fun testARescueNeedsAThreadAndALine() {
        sample()
        val id = agent.addOnLine(20)

        assertRefused(agent.post("comment_reanchor", "line" to 3), 400, "missing 'thread_id'")
        assertRefused(agent.post("comment_reanchor", "thread_id" to "nope", "line" to 3), 404, "nope")
        assertRefused(agent.post("comment_reanchor", "thread_id" to id), 400, "'line'")
        assertRefused(agent.post("comment_reanchor", "thread_id" to id, "line" to "3"), 400, "'line'")
    }

    fun testARescueInAFileThatNoLongerExistsIsA404() {
        val file = myFixture.addFileToProject(SAMPLE, (1..30).joinToString("\n") { lineText(it) }).virtualFile
        val id = agent.addOnLine(20)
        eraseLine(20)
        agent.threads()
        WriteAction.runAndWait<Throwable> { file.delete(this) }

        assertRefused(agent.post("comment_reanchor", "thread_id" to id, "line" to 3), 404, "no longer exists")
    }

    // FixMe: Known issue - #55 a thread stays open while its file is deleted; it orphans only after an IDE restart
    fun testAThreadWhoseFileIsDeletedStaysOpenWhileTheIdeRuns() {
        val file = myFixture.addFileToProject(SAMPLE, (1..30).joinToString("\n") { lineText(it) }).virtualFile
        val fileLevel = agent.add("file" to SAMPLE)
        val lineLevel = agent.addOnLine(20)

        WriteAction.runAndWait<Throwable> { file.delete(this) }

        val statuses = agent.threads().associate { it.id to it.string("status") }
        assertEquals(mapOf(fileLevel to "open", lineLevel to "open"), statuses)
    }

    fun testRescueAllSearchesTheWholeFileAndAnswersPerThread() {
        sample()
        val moved = agent.addOnLine(10)
        val gone = agent.addOnLine(40)
        agent.addOnLine(50)
        val fileLevel = agent.add("file" to SAMPLE)
        val document = document()
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText((1..60).joinToString("\n") { if (it == 55) lineText(10) else "// rewritten $it" })
        }
        agent.threads()

        val response = agent.post("comment_reanchor_all", "file" to SAMPLE)

        assertEquals(SAMPLE, response["file"]!!.asString)
        assertEquals(1, response["rescued"]!!.asInt)
        val results = response.json.getAsJsonArray("results").map { it.asJsonObject }
        val byId = results.associateBy { it.id }
        assertEquals(55, byId.getValue(moved)["line"].asInt)
        assertEquals("open", byId.getValue(moved).string("status"))
        assertNull(byId.getValue(gone)["line"])
        assertEquals("orphaned", byId.getValue(gone).string("status"))
        assertFalse(fileLevel in byId)
        assertEquals("open", agent.threads().single { it.id == moved }.string("status"))
    }

    fun testAnOrphanPastTheEndOfARewrittenFileKeepsItsLostText() {
        sample()
        agent.addOnLine(50)
        val document = document()

        WriteCommandAction.runWriteCommandAction(project) { document.setText("// short now\n") }

        val orphan = agent.threads().single()
        assertEquals("orphaned", orphan.string("status"))
        assertEquals(lineText(50), orphan.string("anchor_text"))
    }

    fun testFilesWithoutTextCannotBeRescuedOrNavigatedByLine() {
        val image = myFixture.addFileToProject("logo.png", "").virtualFile
        WriteAction.runAndWait<Throwable> { image.setBinaryContent(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0)) }

        assertRefused(agent.post("comment_reanchor_all", "file" to "logo.png"), 400, "no text document")
        assertRefused(agent.post("navigate", "file" to "logo.png", "line" to 1), 400, "no text document")
    }

    fun testRescueAllWithNoOrphansIsAnEmptyList() {
        sample()
        agent.addOnLine(10)

        val response = agent.post("comment_reanchor_all", "file" to SAMPLE)

        assertEquals(200, response.status)
        assertEquals(0, response.json.getAsJsonArray("results").size())
        assertEquals(0, response["rescued"]!!.asInt)
    }

    fun testRescueAllNeedsAFileThatExists() {
        assertRefused(agent.post("comment_reanchor_all"), 400, "missing 'file'")
        val unknown = agent.post("comment_reanchor_all", "file" to "src/Gone.kt")
        assertRefused(unknown, 404, "src/Gone.kt")
        assertNotNull(unknown["open_projects"])
    }

    fun testNavigateOpensTheFileWithTheCaretOnTheLine() {
        sample()

        val response = agent.post("navigate", "file" to SAMPLE, "line" to 25, "anchor_text" to lineText(27))

        assertEquals(setOf("navigated", "file", "line", "line_adjusted"), response.json.keySet())
        assertTrue(response["navigated"]!!.asBoolean)
        assertEquals(27, response["line"]!!.asInt)
        assertTrue(response["line_adjusted"]!!.asBoolean)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(SAMPLE, editor.virtualFile!!.path.removePrefix("/src/"))
        assertEquals(26, editor.caretModel.logicalPosition.line)
    }

    fun testNavigateWithoutALineOpensTheFileAtTheTop() {
        sample()

        val response = agent.post("navigate", "file" to SAMPLE)

        assertEquals(setOf("navigated", "file"), response.json.keySet())
        assertEquals(0, FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.logicalPosition.line)
    }

    fun testNavigateWithAStaleAnchorIsA409() {
        sample()

        val response = agent.post("navigate", "file" to SAMPLE, "line" to 25, "anchor_text" to "nowhere")

        assertRefused(response, 409)
        assertEquals("stale_anchor", response["reason"]!!.asString)
    }

    fun testNavigateRefusesMalformedRequests() {
        sample()

        assertRefused(agent.post("navigate", "line" to 3), 400, "missing 'file'")
        assertRefused(agent.post("navigate", "file" to SAMPLE, "anchor_text" to "x"), 400, "'anchor_text' without 'line'")
        assertRefused(agent.post("navigate", "file" to SAMPLE, "line" to "3"), 400, "'line' must be an integer")
        val unknown = agent.post("navigate", "file" to "src/Gone.kt")
        assertRefused(unknown, 404, "src/Gone.kt")
        assertNotNull(unknown["open_projects"])
    }

    fun testNavigateIsForbiddenWhenTheUserSwitchedAgentNavigationOff() {
        sample()
        val settings = MarginalisSettings.getInstance().state
        settings.navigationEnabled = false
        try {
            assertRefused(agent.post("navigate", "file" to SAMPLE, "line" to 3), 403, "disabled")
            assertRefused(agent.post("navigate", "file" to "src/Gone.kt"), 403, "disabled")
            assertRefused(agent.post("navigate", "line" to 3), 400, "missing 'file'")
            assertFalse(FileEditorManager.getInstance(project).isFileOpen(myFixture.findFileInTempDir(SAMPLE)))
        } finally {
            settings.navigationEnabled = true
        }
    }
}
