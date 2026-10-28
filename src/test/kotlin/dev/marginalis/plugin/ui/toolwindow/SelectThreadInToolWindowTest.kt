package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.editor.LogicalPosition
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

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

    fun testSelectInMarginalisSelectsTheOpenStepWhereverTheEditorsCaretIs() {
        // GIVEN - step 27 is open in its editor, and the caret has moved to the file's last line
        val panel = user.open(steps.getValue(27))
        val editor = user.editor()
        editor.caretModel.moveToLogicalPosition(LogicalPosition(editor.document.lineCount - 1, 0))

        // WHEN - the step's own Select in Marginalis is pressed
        user.press(panel, "Select in Marginalis")
        window.settle()

        // THEN - the step is selected in its Guided section, not the file under the caret
        window.selected().shouldNotBeNull() shouldContain "(27/33)"
        window.selectedPath().first() shouldStartWith "Guided SHOP-42"
    }

    fun testTheTitleBarHasNoSelectOpenedThreadButton() {
        window.toolbar() shouldNotContain "Select Opened Thread"
    }

    private fun selectInMarginalis(threadId: String) {
        user.press(user.open(threadId), "Select in Marginalis")
        window.settle()
    }
}
