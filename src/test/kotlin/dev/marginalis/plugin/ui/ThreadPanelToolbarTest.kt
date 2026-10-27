package dev.marginalis.plugin.ui

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.LeavingAStep
import dev.marginalis.core.Reference
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.transport.HttpContractTestCase
import java.awt.datatransfer.DataFlavor

class ThreadPanelToolbarTest : HttpContractTestCase() {

    private val other = "src/Other.kt"

    private fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    private fun shownIn(panel: ThreadPanel): String = user.shownText(panel)

    private fun openPanelSaying(text: String): ThreadPanel = user.panels().single { shownIn(it).contains(text) }

    private fun openFileName(): String? = FileDocumentManager.getInstance().getFile(user.editor().document)?.name

    private fun caretLine(): Int = user.editor().caretModel.logicalPosition.line + 1

    private fun enabled(panel: ThreadPanel): Set<String> =
        user.toolbar(panel).filterValues { it.isEnabledAndVisible }.keys

    private fun clipboard(): String = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!!

    private fun withAutoAdvance(on: Boolean, test: () -> Unit) {
        val state = MarginalisSettings.getInstance().state
        val before = state.walkthroughAutoAdvance
        state.walkthroughAutoAdvance = on
        try {
            test()
        } finally {
            state.walkthroughAutoAdvance = before
        }
    }

    private fun leavingAStep(choice: LeavingAStep, test: () -> Unit) {
        val settings = MarginalisSettings.getInstance()
        val before = settings.leavingAStep
        settings.leavingAStep = choice
        try {
            test()
        } finally {
            settings.leavingAStep = before
        }
    }

    private fun openStepBodies(editor: Editor = user.editor()): List<String> =
        user.panels(editor).map(::shownIn).mapNotNull { shown -> STEP_BODIES.firstOrNull { it in shown } }.sorted()

    private fun editorOf(fileName: String): Editor =
        EditorFactory.getInstance().allEditors.single { FileDocumentManager.getInstance().getFile(it.document)?.name == fileName }

    private fun tour(): List<String> {
        sample()
        sample(other, lines = 20)
        return listOf(
            agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "body" to "Step one"),
            agent.addOnLine(15, "order" to 2, "walkthrough" to "tour", "body" to "Step two"),
            agent.addOnLine(8, "order" to 3, "walkthrough" to "tour", "body" to "Step three", file = other),
        )
    }

    fun testResolvingFromThePanelTellsTheAgentAndClosesThePanel() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.press(panel, "Resolve")

        val listed = agent.threads().single()
        assertEquals("resolved", listed.string("status"))
        assertEquals(Authors.user.displayName, listed.string("resolved_by"))
        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testReopeningFromThePanelTellsTheAgent() {
        sample()
        val id = agent.addOnLine(5)
        agent.post("comment_resolve", "thread_id" to id)
        val panel = user.open(id)

        user.press(panel, "Reopen")

        assertEquals("open", agent.threads().single().string("status"))
        assertTrue(user.toolbar(panel).containsKey("Resolve"))
        assertTrue(shownIn(panel).lines().contains("open"))
    }

    fun testResolvingAStepMovesOnToTheNextStepWhenAutoAdvanceIsOn() = withAutoAdvance(true) {
        val steps = tour()
        val panel = user.open(steps[0])

        user.press(panel, "Resolve")

        assertEquals("resolved", agent.threads().single { it.id == steps[0] }.string("status"))
        assertTrue(shownIn(user.panel()).contains("Step two"))
        assertEquals(15, caretLine())
    }

    fun testResolvingTheLastStepInAnotherFileStaysPut() = withAutoAdvance(true) {
        val steps = tour()
        val panel = user.open(steps[2])

        user.press(panel, "Resolve")

        assertEquals("Other.kt", openFileName())
        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testResolvingAStepStaysPutWhenAutoAdvanceIsOff() = withAutoAdvance(false) {
        val steps = tour()
        val panel = user.open(steps[0])

        user.press(panel, "Resolve")

        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals(5, caretLine())
    }

    fun testResolvingAStepAdvancesIntoAnotherFile() = withAutoAdvance(true) {
        val steps = tour()
        val panel = user.open(steps[1])

        user.press(panel, "Resolve")

        assertEquals("Other.kt", openFileName())
        assertEquals(8, caretLine())
        assertTrue(shownIn(user.panel()).contains("Step three"))
    }

    fun testStepArrowsAreEnabledOnlyWhereThereIsAStepToGoTo() {
        val steps = tour()

        assertEquals(setOf("Next Step", "Last Step"), enabled(user.open(steps[0])).filter { it.endsWith("Step") }.toSet())
        assertEquals(setOf("First Step", "Previous Step", "Next Step", "Last Step"), enabled(user.open(steps[1])).filter { it.endsWith("Step") }.toSet())
        assertEquals(setOf("First Step", "Previous Step"), enabled(user.open(steps[2])).filter { it.endsWith("Step") }.toSet())
    }

    fun testStepArrowsWalkTheTourAcrossFiles() {
        val steps = tour()

        user.press(user.open(steps[0]), "Next Step")
        assertTrue(shownIn(user.panel()).contains("Step two"))
        assertEquals(15, caretLine())

        user.press(user.panel(), "Last Step")
        assertEquals("Other.kt", openFileName())
        assertTrue(shownIn(user.panel()).contains("Step three"))

        user.press(user.panel(), "Previous Step")
        assertEquals("Sample.kt", openFileName())
        assertTrue(shownIn(user.panel()).contains("Step two"))

        user.press(user.panel(), "First Step")
        assertEquals(5, caretLine())
        assertTrue(shownIn(user.panel()).contains("Step one"))
    }

    fun testNextStepFoldsTheStepYouLeaveByDefault() {
        val steps = tour()

        user.press(user.open(steps[0]), "Next Step")

        assertEquals(listOf("Step two"), openStepBodies())
    }

    fun testNextStepKeepsTheStepYouLeaveOpenWhenChosen() = leavingAStep(LeavingAStep.KEEP_OPEN) {
        val steps = tour()

        user.press(user.open(steps[0]), "Next Step")

        assertEquals(listOf("Step one", "Step two"), openStepBodies())
        assertEquals(15, caretLine())
    }

    fun testAStepInAnotherFileStaysOpenInItsTabWhenYouStepBackFromIt() = leavingAStep(LeavingAStep.KEEP_OPEN) {
        val steps = tour()
        user.press(user.open(steps[1]), "Next Step")
        assertEquals("Other.kt", openFileName())

        user.press(user.panel(), "Previous Step")

        assertEquals("Sample.kt", openFileName())
        assertEquals(listOf("Step two"), openStepBodies())
        assertEquals(listOf("Step three"), openStepBodies(editorOf("Other.kt")))
    }

    fun testStepArrowsOnAResolvedThreadStartTheWalkAtTheFirstOpenThread() {
        val steps = tour()
        agent.post("comment_resolve", "thread_id" to steps[1])
        val panel = user.open(steps[1])

        assertEquals(setOf("First Step", "Next Step", "Last Step"), enabled(panel).filter { it.endsWith("Step") }.toSet())
        user.press(panel, "Next Step")

        assertTrue(shownIn(user.panel()).contains("Step one"))
    }

    fun testCopyReferenceCopiesTheThreadsShortReference() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)
        assertEquals("Copy ${Reference.of(id)} to cite this thread", user.toolbar(panel).getValue("Copy Reference").description)

        user.press(panel, "Copy Reference")

        assertEquals(Reference.of(id).toString(), clipboard())
        assertEquals(id, agent.threads("ref" to clipboard()).single().id)
    }

    fun testEachMessageCopiesItsOwnReference() {
        sample()
        val id = agent.addOnLine(5, "body" to "First.")
        val messageId = agent.reply(id, "Second.")["message_id"]!!.asString
        val panel = user.open(id)

        user.clickLinkWithTooltip(panel, "Copy reference: ${Reference.of(messageId)}")

        assertEquals(Reference.of(messageId).toString(), clipboard())
    }

    fun testDeletingAsksFirstAndKeepsTheThreadWhenTheUserSaysNo() {
        sample()
        val id = agent.addOnLine(5)
        agent.reply(id, "More.")
        val panel = user.open(id)

        val asked = user.pressDelete(panel, confirm = false)

        assertEquals("Delete this thread (2 message(s))? Unlike resolving, deletion keeps no record. This cannot be undone.", asked)
        assertEquals(listOf(id), agent.ids())
        assertEquals(1, user.panels().size)
    }

    fun testDeletingOnceConfirmedRemovesTheThreadForTheAgentAndClosesThePanel() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.pressDelete(panel, confirm = true)

        assertEquals(emptyList<String>(), agent.ids())
        assertEquals(emptyList<ThreadPanel>(), user.panels())
    }

    fun testDeletingARelayedThreadWarnsThatGitHubIsUntouchedAndItWillNotComeBack() {
        sample()
        val id = agent.add("file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))
        val panel = user.open(id)

        val asked = user.pressDelete(panel, confirm = true)

        assertTrue(asked, asked.endsWith(" $RELAYED_STAYS_DELETED"))
        val again = agent.post("comment_add", "file" to SAMPLE, "line" to 5, "anchor_text" to lineText(5), "body" to "From Mona.", "relayed" to Relays.reviewComment(101))
        assertEquals(409, again.status)
    }

    fun testCloseHidesThePanelAndLeavesTheThreadAlone() {
        sample()
        val id = agent.addOnLine(5)
        val panel = user.open(id)

        user.press(panel, "Close")

        assertEquals(emptyList<ThreadPanel>(), user.panels())
        assertEquals("open", agent.threads().single().string("status"))
    }

    fun testLiveIsUnavailableWhileNoAgentListens() {
        sample()
        val id = agent.addOnLine(5)
        val live = user.toolbar(user.open(id)).getValue("No agent is listening — live needs Claude waiting")

        assertTrue(live.isVisible)
        assertFalse(live.isEnabled)
    }

    fun testLiveIsOfferedWhileTheThreadsAgentWaitsAndShowsItListening() {
        sample()
        val id = agent.addOnLine(5)
        val waiting = agent.startWait()
        val panel = user.open(id)

        assertTrue(user.toolbar(panel).getValue("Go Live: each Submit wakes Claude with this thread").isEnabled)
        user.press(panel, "Go Live: each Submit wakes Claude with this thread")

        assertTrue(user.toolbar(panel).getValue("Live: each Submit wakes Claude with this thread").isEnabled)
        assertTrue(shownIn(panel).lines().contains("Claude is listening"))
        user.type(panel, "Over to you.")
        user.submit(panel)
        val woke = agent.await(waiting)
        assertEquals("live", woke["reason"]!!.asString)
        assertEquals(listOf(id), woke.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
        settle()
        assertTrue(shownIn(panel).lines().contains("Claude is working…"))
    }

    fun testLiveAsksForAMentionWhenSeveralAgentsListenOnTheUsersThread() {
        sample()
        user.comment(SAMPLE, 5, "Anyone?")
        val id = agent.ids().single()
        agent.startWait()
        reviewer.startWait()
        val panel = user.open(id)

        val live = user.toolbar(panel).getValue("Several agents are listening — @ one to go live")

        assertFalse(live.isEnabled)
    }

    fun testADraftOffersNoThreadActions() {
        sample()
        user.comment(SAMPLE, 5)

        val toolbar = user.toolbar(user.panel())

        assertEquals(setOf("Close"), toolbar.filterValues { it.isEnabledAndVisible }.keys)
        assertTrue(shownIn(user.panel()).lines().contains("new comment — unsent"))
    }

    fun testResolvingALooseThreadMovesOnToTheNextOpenThreadWhenAutoAdvanceIsOn() = withAutoAdvance(true) {
        sample()
        sample(other, lines = 20)
        val here = agent.addOnLine(8, "body" to "Loose here", file = other)
        agent.addOnLine(5, "body" to "Loose elsewhere")

        user.press(user.open(here), "Resolve")

        assertEquals("Sample.kt", openFileName())
        assertEquals(5, caretLine())
        assertTrue(shownIn(user.panel()).contains("Loose elsewhere"))
    }

    fun testTheNextAndPreviousOccurrenceShortcutsWalkTheSteps() {
        val steps = tour()

        user.pressShortcut(user.open(steps[0]), IdeActions.ACTION_NEXT_OCCURENCE)
        assertTrue(shownIn(user.panel()).contains("Step two"))

        user.pressShortcut(user.panel(), IdeActions.ACTION_PREVIOUS_OCCURENCE)
        assertTrue(shownIn(user.panel()).contains("Step one"))
    }

    private companion object {
        val STEP_BODIES = listOf("Step one", "Step two", "Step three")
    }
}
