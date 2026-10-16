package dev.marginalis.plugin.ui.toolwindow

class SelectThreadInToolWindowTest : ToolWindowTestCase() {

    private lateinit var steps: Map<Int, String>
    private lateinit var plain: String
    private lateinit var resolved: String

    override fun setUp() {
        super.setUp()
        steps = deep()
        sample()
        plain = agent.addOnLine(5, "body" to "A thread outside the walkthrough")
        resolved = agent.addOnLine(9, "body" to "A thread already resolved")
        agent.post("comment_resolve", "thread_id" to resolved)
        window.settle()
    }

    fun testSelectInMarginalisSelectsAStepInItsGuidedSection() {
        selectInMarginalis(steps.getValue(27))

        assertTrue(window.selected(), window.selected()!!.contains("(27/33)"))
        assertTrue(window.selectedPath().toString(), window.selectedPath().first().startsWith("Guided SHOP-42"))
    }

    fun testSelectInMarginalisSelectsAThreadOutsideAnyWalkthroughInOpen() {
        selectInMarginalis(plain)

        assertTrue(window.selected(), window.selected()!!.endsWith("A thread outside the walkthrough"))
        assertTrue(window.selectedPath().toString(), window.selectedPath().first().startsWith("Open"))
    }

    fun testSelectInMarginalisReachesIntoTheFoldedResolvedSection() {
        assertFalse(window.isExpanded("Resolved"))
        user.read(resolved)

        selectInMarginalis(resolved)

        assertTrue(window.selected(), window.selected()!!.endsWith("A thread already resolved"))
        assertTrue(window.selectedPath().toString(), window.selectedPath().first().startsWith("Resolved"))
    }

    fun testSelectOpenedThreadSelectsTheThreadOnTheCaretsLine() {
        user.openFile(SAMPLE, line = 5)

        window.press("Select Opened Thread")

        assertTrue(window.selected(), window.selected()!!.endsWith("A thread outside the walkthrough"))
    }

    fun testSelectOpenedThreadOnALineWithoutAThreadSelectsTheFile() {
        user.openFile(SAMPLE, line = 2)

        window.press("Select Opened Thread")

        assertTrue(window.selected(), window.selected()!!.startsWith("Sample.kt"))
    }

    private fun selectInMarginalis(threadId: String) {
        user.press(user.open(threadId), "Select in Marginalis")
        window.settle()
    }
}
