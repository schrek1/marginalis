package dev.marginalis.plugin.ui.toolwindow

import dev.marginalis.plugin.testing.MarginToolWindow

class ResolvingAWalkthroughTest : ToolWindowTestCase() {

    fun testResolveWalkthroughResolvesItsOpenAndOrphanedStepsAndMovesItToResolved() {
        val steps = tourWithAnOpenAnOrphanedAndAResolvedStep()

        val asked = window.chooseFromContextMenu("tour", "Resolve Walkthrough", answer = MarginToolWindow.YES)

        assertEquals("Resolve the 1 open step(s) of tour? The walkthrough moves to the Resolved log.", asked)
        assertEquals(listOf("resolved", "resolved", "resolved"), steps.map(::status))
        assertEquals(listOf("Open  1", "Resolved  3"), sections())
    }

    fun testResolveWalkthroughAsksFirstAndDoesNothingOnNo() {
        val steps = tourWithAnOpenAnOrphanedAndAResolvedStep()

        window.chooseFromContextMenu("tour", "Resolve Walkthrough", answer = MarginToolWindow.NO)

        assertEquals(listOf("open", "orphaned", "resolved"), steps.map(::status))
        assertEquals(listOf("tour  3", "Open  1"), sections())
    }

    fun testResolveWalkthroughWarnsWhenOpenStepsAreBlockers() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "severity" to "blocker", "body" to "must fix")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "fine")

        val asked = window.chooseFromContextMenu("tour", "Resolve Walkthrough", answer = MarginToolWindow.NO)

        assertEquals(
            "Resolve the 2 open step(s) of tour? 1 of them are blockers — resolve only if their outcomes genuinely landed. " +
                "The walkthrough moves to the Resolved log.",
            asked,
        )
    }

    fun testResolveWalkthroughWarnsWhenAnOrphanedStepItWillResolveIsABlocker() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "fine")
        agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "severity" to "blocker", "body" to "lost blocker")
        eraseLine(20)

        val asked = window.chooseFromContextMenu("tour", "Resolve Walkthrough", answer = MarginToolWindow.NO)

        assertTrue(asked, asked.contains("1 of them are blockers"))
    }

    fun testResolveWalkthroughWithOnlyOrphanedStepsLeftAsksNothing() {
        sample()
        val done = agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        val lost = agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "body" to "lost step")
        agent.post("comment_resolve", "thread_id" to done)
        eraseLine(20)

        window.chooseFromContextMenu("tour", "Resolve Walkthrough")

        assertEquals("resolved", status(lost))
        assertEquals(listOf("Resolved  2"), sections())
    }

    fun testResolveWalkthroughIsOfferedOnlyOnAnActiveWalkthroughsTitle() {
        tourWithAnOpenAnOrphanedAndAResolvedStep()
        agent.post("comment_resolve", "thread_id" to agent.addOnLine(9, "order" to 1, "walkthrough" to "done", "body" to "finished"))
        window.toggleFolding("Resolved")

        assertTrue(window.contextMenu("tour").contains("Resolve Walkthrough"))
        assertFalse(window.contextMenu("(1/3)").contains("Resolve Walkthrough"))
        assertFalse(window.contextMenu("Open").contains("Resolve Walkthrough"))
        assertFalse(window.contextMenu("loose").contains("Resolve Walkthrough"))
        assertFalse(window.contextMenu("done", under = "Resolved").contains("Resolve Walkthrough"))
    }

    private fun tourWithAnOpenAnOrphanedAndAResolvedStep(): List<String> {
        sample()
        val steps = listOf(
            agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "open step"),
            agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "body" to "lost step"),
            agent.addOnLine(6, "order" to 3, "walkthrough" to "tour", "body" to "done step"),
        )
        agent.addOnLine(12, "body" to "loose")
        agent.post("comment_resolve", "thread_id" to steps[2])
        eraseLine(20)
        return steps
    }
}
