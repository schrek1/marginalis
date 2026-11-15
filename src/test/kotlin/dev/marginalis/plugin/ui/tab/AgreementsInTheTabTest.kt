package dev.marginalis.plugin.ui.tab

class AgreementsInTheTabTest : TabInFrontTestCase() {

    private fun agreeWith(question: String) = user.clickLink(tab.panel(question), "Agree")

    private fun answer(question: String, reply: String) = tab.panel(question).let {
        user.type(it, reply)
        user.submit(it)
    }

    fun testAnAgreedProjectThreadShowsWhatTheAgentDidWithIt() {
        val id = agent.ask("Shall we call it Oak?")

        agreeWith("Shall we call it Oak?")
        assertEquals(listOf("👍 Shall we call it Oak? [expanded]"), shown())

        agent.answerAgreement(id, "working")
        assertEquals(listOf("⏳ Shall we call it Oak? [expanded]"), shown())

        agent.answerAgreement(id, "acknowledged")
        assertEquals(listOf("Shall we call it Oak? [expanded]"), shown())
    }

    fun testTheHeaderAndTheTitleCountNoAgentsMoveForAnAgree() {
        agent.ask("Shall we call it Oak?")
        agent.ask("Which release day?")
        answer("Which release day?", "Friday.")

        agreeWith("Shall we call it Oak?")

        assertEquals("2 open · 0 your move · 1 agent's move", tab.counts())
        assertEquals("✈ 1", tab.title())
    }

    fun testAnsweringAnAgreeMovesItsCardInRecentActivityOrderAndRepeatingTheAnswerDoesNot() {
        tab.sortBy("Recent activity")
        val alpha = agent.ask("Alpha question")
        val bravo = agent.ask("Bravo question")
        agreeWith("Alpha question")
        agent.reply(bravo, "More on bravo")
        assertEquals(listOf("✉ Bravo question [expanded]", "👍 Alpha question [expanded]"), shown())

        agent.answerAgreement(alpha, "working")
        assertEquals(listOf("⏳ Alpha question [expanded]", "✉ Bravo question [expanded]"), shown())

        agent.reply(bravo, "Still more on bravo")
        agent.answerAgreement(alpha, "working")
        assertEquals(listOf("✉ Bravo question [expanded]", "⏳ Alpha question [expanded]"), shown())
    }
}
