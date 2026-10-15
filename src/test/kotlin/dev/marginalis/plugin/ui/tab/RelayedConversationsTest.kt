package dev.marginalis.plugin.ui.tab

import dev.marginalis.plugin.testing.Relays

class RelayedConversationsTest : MarginTabTestCase() {

    private fun relay(id: Long, body: String): String = agent.add("body" to body, "relayed" to Relays.conversationComment(id))

    fun testAPullRequestsConversationFoldsIntoOneGroup() {
        relay(1, "Looks good overall")
        relay(2, "Please add a changelog entry")
        agent.ask("Our own question")

        assertEquals(
            listOf("From GitHub · PR #7  ▸ 2 comments · 2 new", "✉ Our own question ● [expanded]"),
            shown(),
        )
    }

    fun testOpeningTheGroupListsItsCommentsFoldedAndEachOpensOnClick() {
        relay(1, "Looks good overall")
        relay(2, "Please add a changelog entry")

        tab.clickConversation("PR #7")
        val opened = shown()
        assertEquals("From GitHub · PR #7  ▾ 2 comments · 2 new", opened.first())
        assertEquals(2, opened.size - 1)
        assertTrue(opened.toString(), opened.drop(1).none { it.endsWith("[expanded]") })

        tab.click("changelog")
        assertTrue(shown().toString(), shown().any { it.contains("changelog") && it.endsWith("[expanded]") })

        tab.clickConversation("PR #7")
        assertEquals(listOf("From GitHub · PR #7  ▸ 2 comments · 2 new"), shown())
    }

    fun testASingleRelayedCommentCountsAsOneComment() {
        relay(1, "Looks good overall")

        assertEquals(listOf("From GitHub · PR #7  ▸ 1 comment · 1 new"), shown())
    }

    fun testWithGroupingOffEachRelayedCommentIsItsOwnThread() {
        relay(1, "Looks good overall")
        relay(2, "Please add a changelog entry")

        user.changeProjectTabSettings { groupRelayedConversation(false) }

        val rows = shown()
        assertEquals(rows.toString(), 2, rows.size)
        assertTrue(rows.toString(), rows.none { it.startsWith("From GitHub") })
        assertTrue(rows.toString(), rows.none { it.endsWith("[expanded]") })
    }

    fun testAReplyInAGroupedCommentReachesTheAgent() {
        val looks = relay(1, "Looks good overall")
        tab.clickConversation("PR #7")
        tab.click("Looks good")

        tab.panel("Looks good").let {
            user.type(it, "Thanks!")
            user.submit(it)
        }

        assertEquals("Thanks!", agent.threads().single { it.id == looks }.messages().last().string("body"))
    }
}
