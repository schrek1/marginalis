package dev.marginalis.plugin.ui.tab


class HeaderTest : MarginTabTestCase() {

    fun testFileThreadsWaitingOnYouAreLinkedFromTheTabAndOneClickOpensTheFirst() {
        sample()
        agent.addOnLine(30, "body" to "Line thirty")
        agent.addOnLine(5, "body" to "Line five")
        assertEquals("Also your move: 2 threads in files ›", tab.alsoYourMove())

        tab.clickAlsoYourMove()

        assertFalse(tab.isInFront)
        assertEquals(5, user.editor().caretModel.logicalPosition.line + 1)
        assertTrue(user.shownText(user.panel()).contains("Line five"))
    }

    fun testTheLinkCountsOneThreadAndGoesWhenNothingInFilesWaits() {
        sample()
        val five = agent.addOnLine(5, "body" to "Line five")
        assertEquals("Also your move: 1 thread in files ›", tab.alsoYourMove())

        agent.post("comment_resolve", "thread_id" to five)

        assertNull(tab.alsoYourMove())
    }

    fun testSubmitRoundIsOffWhileNoAgentListensAndWakesTheListenerWithYourReplies() {
        val alpha = agent.ask("Which release name?")
        assertEquals(listOf("Submit round", "Stop agents", "Sort: Oldest first"), tab.toolbar())
        assertFalse(tab.isEnabled("Submit round"))

        val waiting = agent.startWait()
        assertTrue(tab.isEnabled("Submit round"))
        tab.panel("Which release name?").let {
            user.type(it, "Call it Oak.")
            user.submit(it)
        }
        tab.press("Submit round")

        val wake = agent.await(waiting)
        assertEquals("hand_back", wake["reason"]!!.asString)
        assertEquals(listOf(alpha), wake.json.getAsJsonArray("awaiting").map { it.asJsonObject.id })
    }

    fun testEveryToolbarActionUpdatesOffTheUiThread() {
        agent.ask("Which release name?")

        assertEquals(emptyList<String>(), tab.actionsUpdatingOnTheUiThread())
    }

    fun testTheHeaderShowsWhoListensAndWhoWorks() {
        assertEquals(emptyList<String>(), tab.presence())

        val waiting = agent.startWait("stay" to true)
        assertEquals(listOf("Claude · listening · just now"), tab.presence())
        val listening = tab.presenceIcons().single()

        tab.press("Submit round")
        agent.await(waiting)

        assertEquals(listOf("Claude · working · just now"), tab.presence())
        assertNotSame(listening, tab.presenceIcons().single())
    }

    fun testAnAgentThatAnswersOneRoundIsMarkedOneShot() {
        agent.startWait()

        assertEquals(listOf("Claude · listening · just now · one-shot"), tab.presence())
    }

    fun testStoppingAnAgentFromTheTabEndsItsWait() {
        val waiting = agent.startWait("stay" to true)

        tab.stop("Claude")

        assertEquals("stopped", agent.await(waiting)["reason"]!!.asString)
        assertEquals(emptyList<String>(), tab.presence())
    }

    fun testANewProjectThreadStartsAsADraftAtTheBottomAndReachesTheAgentWhenSubmitted() {
        agent.ask("Existing question")

        tab.newProjectThread()
        assertEquals(listOf("✉ Existing question ● [expanded]", "[draft] new comment — unsent"), shown())

        user.type(tab.draft()!!, "Plan the 0.3 release")
        user.submit(tab.draft()!!)

        assertNull(tab.draft())
        assertEquals(listOf("✉ Existing question ● [expanded]", "✈ Plan the 0.3 release [expanded]"), shown())
        val started = agent.threads().single { it.messages().first().string("body") == "Plan the 0.3 release" }
        assertFalse(started.has("file"))
    }

    fun testABlankDraftClosesAwayButAWrittenOneStays() {
        tab.newProjectThread()
        user.type(tab.draft()!!, "Plan the 0.3 release")
        user.press(tab.draft()!!, "Close")
        assertNotNull(tab.draft())

        user.type(tab.draft()!!, "")
        user.press(tab.draft()!!, "Close")

        assertNull(tab.draft())
        assertEquals(listOf(EMPTY_STATE, NEW_PROJECT_THREAD), shown())
    }

    fun testTheEmptyStatesLinkStartsAProjectThreadToo() {
        tab.clickEmptyStateLink()

        assertEquals(listOf("[draft] new comment — unsent"), shown())
    }

    fun testTheToolWindowsNewProjectThreadOpensAClosedTabWithADraft() {
        tab.close()
        val window = user.openToolWindow()

        window.press("Comment on Project")
        tab.settle()

        assertTrue(tab.isInFront)
        assertEquals(listOf("[draft] new comment — unsent"), shown())
    }

    fun testStartingANewProjectThreadAgainKeepsTheDraftYouWereWriting() {
        tab.newProjectThread()
        user.type(tab.draft()!!, "Plan the 0.3 release")

        tab.newProjectThread()

        assertEquals("Plan the 0.3 release", user.composer(tab.draft()!!).text)
        assertSame(tab.draft(), tab.editor.preferredFocusedComponent)
    }

    fun testWithoutADraftTheTabItselfTakesTheFocus() {
        agent.ask("Which release name?")

        assertSame(tab.editor.component, tab.editor.preferredFocusedComponent)
    }
}
