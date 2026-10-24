package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.Agent

// KeepingYourPlaceTest with the default order, where new threads arrive at the bottom.
class KeepingYourPlaceOldestFirstTest : TabInFrontTestCase() {

    private fun paragraphs(count: Int): String = (1..count).joinToString("\n\n") { "Paragraph $it." }

    private fun Agent.longAsk(title: String, count: Int = 40): String = ask("$title\n\n${paragraphs(count)}")

    fun testAReplyBoxStaysPutWhenAThreadAboveGrowsPastTheView() {
        val alpha = agent.ask("Alpha question")
        agent.ask("Bravo question")
        agent.longAsk("Charlie question")
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.", caretAt = 4)
        val before = tab.topOnScreen(box)

        agent.reply(alpha, paragraphs(80))
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
        assertEquals(4, box.editor!!.caretModel.offset)
    }

    fun testTheThreadYouReadGrowsDownwardsPushingTheNextOneDown() {
        val alpha = agent.longAsk("Alpha question")
        agent.ask("Bravo question")
        tab.scrollToBottom()
        val alphaBefore = tab.topOnScreen(tab.card("Alpha"))
        val bravoBefore = tab.topOnScreen(tab.card("Bravo"))

        agent.reply(alpha, paragraphs(10))
        tab.settle()

        assertEquals(alphaBefore, tab.topOnScreen(tab.card("Alpha")))
        assertTrue(tab.topOnScreen(tab.card("Bravo")) > bravoBefore)
    }

    fun testAtTheTopANewThreadLeavesTheViewAlone() {
        agent.longAsk("Alpha question", count = 60)
        assertTrue(tab.scrolls)

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(0, tab.scrolledTo)
        assertTrue(tab.topInView(tab.card("Alpha")) in 0..100)
    }

    fun testAtTheBottomANewThreadArrivesInView() {
        agent.longAsk("Alpha question", count = 60)
        tab.scrollToBottom()

        agent.ask("Fresh question")
        tab.settle()

        val fresh = tab.card("Fresh question")
        val freshInView = tab.topInView(fresh)..tab.topInView(fresh) + fresh.height
        assertTrue("the new thread spans $freshInView of a ${tab.viewHeight} px view", freshInView.first >= 0 && freshInView.last <= tab.viewHeight)
    }

    fun testAReplyBoxAtTheBottomStaysPutWhenANewThreadArrivesBelowIt() {
        agent.longAsk("Alpha question", count = 60)
        agent.ask("Bravo question")
        tab.scrollToBottom()
        val box = user.writeIn(tab.panel("Bravo"), "Call it Oak.")
        val before = tab.topOnScreen(box)

        agent.ask("Fresh question")
        tab.settle()

        assertEquals(before, tab.topOnScreen(box))
        assertEquals("Call it Oak.", box.text)
    }
}
