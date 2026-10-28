package dev.marginalis.plugin.ui

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.transport.HttpContractTestCase
import java.awt.Container
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

class ThreadPanelHeaderTest : HttpContractTestCase() {

    fun testInANarrowPanelTheStatusStopsWhereTheStepButtonsStart() {
        // GIVEN - a step's header, as one side of a side-by-side review diff gives it
        val header = headerOfAStep()

        // WHEN - the header is laid out narrower than the status and the buttons together
        layOut(header, width = 340)

        // THEN - the status shortens up to the buttons, which stay inside the header
        assertTrue("the status ends at ${status(header).maxX}, the buttons start at ${buttons(header).x}", status(header).maxX <= buttons(header).x)
        assertTrue("the buttons span ${buttons(header)} in a header ${header.width} wide", buttons(header).x >= 0 && buttons(header).maxX <= header.width)
        assertTrue("the status is ${status(header).width} wide of ${statusLabel(header).preferredSize.width}", status(header).width < statusLabel(header).preferredSize.width)
    }

    fun testInAVeryNarrowPanelTheButtonsFoldAndTheStatusKeepsItsFirstWord() {
        // GIVEN - a step's header
        val header = headerOfAStep()

        // WHEN - the header is narrower than the buttons alone
        layOut(header, width = 200)

        // THEN - the buttons shrink inside the header and the status still says "open…"
        assertTrue("the status ends at ${status(header).maxX}, the buttons start at ${buttons(header).x}", status(header).maxX <= buttons(header).x)
        assertTrue("the buttons span ${buttons(header)} in a header ${header.width} wide", buttons(header).x >= 0 && buttons(header).maxX <= header.width)
        assertTrue("the status is ${status(header).width} wide, its first word needs ${statusLabel(header).minimumSize.width}", status(header).width >= statusLabel(header).minimumSize.width)
    }

    fun testInAWidePanelTheStatusAndEveryButtonShowInFull() {
        // GIVEN - a step's header
        val header = headerOfAStep()

        // WHEN - the header has room for everything
        layOut(header, width = 900)

        // THEN - nothing is shortened or folded
        assertEquals(statusLabel(header).preferredSize.width, status(header).width)
        assertEquals(toolbar(header).preferredSize.width, buttons(header).width)
    }

    private fun headerOfAStep(): JComponent {
        sample()
        val id = agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "body" to "Step one")
        val panel = user.open(id)
        val toolbar = UIUtil.uiTraverser(panel).filter(ActionToolbar::class.java).first() as ActionToolbarImpl
        PlatformTestUtil.waitForFuture(toolbar.updateActionsAsync())
        return generateSequence(toolbar.component.parent) { it.parent }.filterIsInstance<JComponent>()
            .first { header -> UIUtil.uiTraverser(header).filter(JLabel::class.java).any { it.text.orEmpty().startsWith("open") } }
    }

    private fun layOut(header: JComponent, width: Int) {
        header.setSize(width, header.preferredSize.height)
        fun layOutAll(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layOutAll)
        }
        layOutAll(header)
    }

    private fun statusLabel(header: JComponent): JLabel =
        UIUtil.uiTraverser(header).filter(JLabel::class.java).first { it.text.orEmpty().startsWith("open") }

    private fun toolbar(header: JComponent): JComponent =
        UIUtil.uiTraverser(header).filter(ActionToolbar::class.java).first()!!.component

    private fun status(header: JComponent): Rectangle = inHeader(header, statusLabel(header))

    private fun buttons(header: JComponent): Rectangle = inHeader(header, toolbar(header))

    private fun inHeader(header: JComponent, component: JComponent): Rectangle =
        SwingUtilities.convertRectangle(component.parent, component.bounds, header)
}
