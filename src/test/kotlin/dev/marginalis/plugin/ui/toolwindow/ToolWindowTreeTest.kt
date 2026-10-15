package dev.marginalis.plugin.ui.toolwindow

import dev.marginalis.plugin.testing.Agent

class ToolWindowTreeTest : ToolWindowTestCase() {

    fun testAnEmptyMarginShowsNoRowsAndSaysSo() {
        assertEquals(0, window.tree.rowCount)
        assertEquals("No margin threads yet", window.emptyText())
    }

    // FixMe: Known issue - #51 the Guided section is a folder tree ordered by each folder's smallest step, not the order the walkthrough is read
    fun testAWalkthroughThatHopsBetweenFilesIsGuidedFolderByFolder() {
        interleaved()

        assertEquals(
            """
            Guided SHOP-77  17
              acme/backend  17
                shop-orders/src  13
                  main  8
                    java/com/acme/orders  7
                      api  2
                        OrderController.java ✉2
                          ✉ (10/18)  L30  Step 10
                          ✉ (18/18)  L45  Step 18
                      OrderService.java ✉4
                        ✉ (2/18)  L20  Step 2
                        ✉ (3/18)  L28  Step 3
                        ✉ (6/18)  L41  Step 6
                        ✉ (9/18)  L55  Step 9
                      OrderRepository.java ✉1
                        ✉ (7/18)  L15  Step 7
                    resources/db/changelog  1
                      add-loyalty-discount.sql ✉1
                        ✉ (11/18)  L3  Step 11
                  test/java/com/acme/orders  5
                    discount  2
                      LoyaltyDiscountPolicyTest.java ✉2
                        ✉ (15/18)  L12  Step 15
                        ✉ (16/18)  L30  Step 16
                    OrderServiceTest.java ✉3
                      ✉ (12/18)  L14  Step 12
                      ✉ (13/18)  L36  Step 13
                      ✉ (17/18)  L58  Step 17
                shop-common/src/main/java/com/acme/backend/common  4
                  discount  3
                    LoyaltyDiscountPolicy.java ✉3
                      ✉ (4/18)  L8  Step 4
                      ✉ (5/18)  L22  Step 5
                      ✉ (14/18)  L40  Step 14
                  order  1
                    Order.java ✉1
                      ✉ (8/18)  L10  Step 8
            """.trimIndent(),
            section("Guided"),
        )
    }

    fun testOpenThreadsAreListedAgainByPathWithoutStepNumbers() {
        interleaved()

        assertEquals(listOf("Guided SHOP-77  17", "Open  17", "Resolved  1"), sections())
        assertEquals(
            """
            Open  17
              acme/backend  17
                shop-common/src/main/java/com/acme/backend/common  4
                  discount  3
                    LoyaltyDiscountPolicy.java ✉3
                      ✉ L8  Step 4
                      ✉ L22  Step 5
                      ✉ L40  Step 14
                  order  1
                    Order.java ✉1
                      ✉ L10  Step 8
                shop-orders/src  13
                  main  8
                    java/com/acme/orders  7
                      api  2
                        OrderController.java ✉2
                          ✉ L30  Step 10
                          ✉ L45  Step 18
                      OrderRepository.java ✉1
                        ✉ L15  Step 7
                      OrderService.java ✉4
                        ✉ L20  Step 2
                        ✉ L28  Step 3
                        ✉ L41  Step 6
                        ✉ L55  Step 9
                    resources/db/changelog  1
                      add-loyalty-discount.sql ✉1
                        ✉ L3  Step 11
                  test/java/com/acme/orders  5
                    discount  2
                      LoyaltyDiscountPolicyTest.java ✉2
                        ✉ L12  Step 15
                        ✉ L30  Step 16
                    OrderServiceTest.java ✉3
                      ✉ L14  Step 12
                      ✉ L36  Step 13
                      ✉ L58  Step 17
            """.trimIndent(),
            section("Open"),
        )
    }

    fun testTheResolvedSectionStartsFolded() {
        interleaved()

        assertEquals("Resolved  1", section("Resolved"))
        assertFalse(window.isExpanded("Resolved"))

        window.toggleFolding("Resolved")

        assertEquals(
            """
            Resolved  1
              acme/backend/shop-orders/src/main/java/com/acme/orders/api  1
            """.trimIndent(),
            section("Resolved"),
        )

        window.toggleFolding("acme/backend/shop-orders/src/main/java/com/acme/orders/api")
        window.toggleFolding("OrderController.java", under = "Resolved")

        assertEquals(
            """
            Resolved  1
              acme/backend/shop-orders/src/main/java/com/acme/orders/api  1
                OrderController.java
                  L12  Step 1
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testADeepWalkthroughCountsItsOpenStepsAndNumbersThemOutOfTheWholeWalk() {
        val steps = deep()

        assertEquals(listOf("Guided SHOP-42  24", "Open  24", "Resolved  9"), sections())
        val guided = section("Guided")
        assertEquals("acme  24", guided.lines()[1].trim())
        assertEquals("backend  23", guided.lines()[2].trim())
        assertTrue(guided, guided.contains("(8/33)  L5  Step 8"))
        assertTrue(guided, guided.contains("(33/33)  L5  Step 33"))
        assertFalse(guided, guided.contains("(1/33)"))
        assertEquals(24, Regex("""\((\d+)/33\)""").findAll(guided).count())
        assertEquals(33, steps.size)
    }

    fun testStepNumbersKeepTheWholeWalksTotalAsStepsAreResolved() {
        sample()
        val first = agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.addOnLine(9, "order" to 3, "walkthrough" to "tour", "body" to "three")

        agent.post("comment_resolve", "thread_id" to first)

        assertEquals(
            """
            Guided tour  2
              src  2
                Sample.kt ✉2
                  ✉ (2/3)  L6  two
                  ✉ (3/3)  L9  three
            """.trimIndent(),
            section("Guided"),
        )
    }

    fun testAWalkthroughWithoutALabelIsSimplyGuided() {
        sample()
        agent.addOnLine(3, "order" to 1, "body" to "one")
        agent.addOnLine(6, "order" to 2, "body" to "two")

        assertEquals(listOf("Guided  2", "Open  2"), sections())
        assertTrue(section("Guided"), section("Guided").contains("(1/2)  L3  one"))
    }

    fun testTwoWalkthroughsEachGetASectionAndTheirStepsShowJustTheirNumber() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "SHOP-42", "body" to "first walk, one")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "SHOP-42", "body" to "first walk, two")
        agent.addOnLine(9, "order" to 1, "walkthrough" to "r2", "body" to "second walk")

        assertEquals(listOf("Guided SHOP-42  2", "Guided r2  1", "Open  3"), sections())
        assertTrue(section("Guided SHOP-42"), section("Guided SHOP-42").contains("(1/2)  L3  first walk, one"))
        assertTrue(section("Guided r2"), section("Guided r2").contains("(1/1)  L9  second walk"))
    }

    fun testProjectFileAndLineThreadsEachSayWhereTheyAre() {
        sample()
        agent.addOnLine(12, "body" to "on a line")
        agent.add("file" to SAMPLE, "body" to "on the file")
        agent.add("body" to "on the project")
        agent.addOnLine(4, "body" to "earlier line")

        assertEquals(
            """
            Open  4
              Project  1
                ✉ project  on the project
              src  3
                Sample.kt ✉3
                  ✉ file  on the file
                  ✉ L4  earlier line
                  ✉ L12  on a line
            """.trimIndent(),
            window.outline(),
        )
    }

    fun testProjectStepsOfAWalkthroughComeFirstInStepOrder() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "code")
        agent.add("order" to 3, "walkthrough" to "tour", "body" to "wrap up")
        agent.add("order" to 2, "walkthrough" to "tour", "body" to "the big picture")

        assertEquals(
            """
            Guided tour  3
              Project  2
                ✉ (2/3)  project  the big picture
                ✉ (3/3)  project  wrap up
              src  1
                Sample.kt ✉1
                  ✉ (1/3)  L3  code
            """.trimIndent(),
            section("Guided"),
        )
    }

    fun testFoldersWithASingleChildFolderAreJoinedIntoOneRow() {
        sample("a/b/c/Deep.kt")
        sample("a/b/Side.kt")
        agent.addOnLine(2, "body" to "deep", file = "a/b/c/Deep.kt")
        agent.addOnLine(2, "body" to "side", file = "a/b/Side.kt")

        assertEquals(
            """
            Open  2
              a/b  2
                c  1
                  Deep.kt ✉1
                    ✉ L2  deep
                Side.kt ✉1
                  ✉ L2  side
            """.trimIndent(),
            window.outline(),
        )
    }

    fun testEachSectionCountsItsOpenBlockers() {
        sample()
        agent.addOnLine(3, "severity" to "blocker", "body" to "must fix")
        agent.addOnLine(6, "severity" to "blocker", "body" to "also must fix")
        val solved = agent.addOnLine(9, "severity" to "blocker", "body" to "fixed already")
        agent.addOnLine(12, "order" to 1, "severity" to "blocker", "body" to "a blocking step")
        agent.post("comment_resolve", "thread_id" to solved)

        assertEquals(listOf("Guided  1  ·  1 blocker", "Open  3  ·  3 blockers", "Resolved  1"), sections())
    }

    fun testANitsPreviewIsGreyedAndOthersAreNot() {
        sample()
        agent.addOnLine(3, "severity" to "nit", "body" to "tiny thing")
        agent.addOnLine(6, "body" to "real thing")

        assertTrue(window.isGreyed("tiny thing", "tiny thing"))
        assertFalse(window.isGreyed("real thing", "real thing"))
        assertTrue(window.isGreyed("real thing", "L6"))
    }

    fun testARowPreviewsTheFirstMessageAsPlainTextShortenedToSeventyCharacters() {
        sample()
        val id = agent.addOnLine(3, "body" to "**Bold** start, " + "word ".repeat(30))
        agent.reply(id, "a later message")

        val row = window.rows().single { it.contains("L3") }

        val preview = row.substringAfter("✉ L3  ")
        assertTrue(row, preview.startsWith("Bold start, word word"))
        assertTrue(row, preview.endsWith("..."))
        assertEquals(70, preview.length)
        assertFalse(row, row.contains("a later message"))
    }

    fun testTurnSignalsShowWhoseMoveItIsOnEachThreadAndAreTalliedOnTheFile() {
        sample()
        val answered = agent.addOnLine(3, "body" to "your move")
        agent.addOnLine(6, "body" to "still yours")
        val resolved = agent.addOnLine(9, "body" to "done")
        user.reply(answered, "Over to you.")
        agent.post("comment_resolve", "thread_id" to resolved)

        assertEquals(
            """
            Open  2
              src  2
                Sample.kt ✉1 ✈1
                  ✈ L3  your move
                  ✉ L6  still yours
            Resolved  1
            """.trimIndent(),
            window.outline(),
        )
        assertEquals("Sample.kt, your move 1, agent's move 1", window.spoken("Sample.kt"))
    }

    fun testAThreadRowTellsAScreenReaderWhoseMoveItIsAndWhoTookPart() {
        sample()
        val id = agent.addOnLine(3, "body" to "Extract this.")
        reviewer.reply(id, "Agreed.")

        assertEquals("L3  Extract this., your move, with Claude, Claude · review", window.spoken("Extract this."))
        assertEquals("Open  1", window.spoken("Open"))
    }

    fun testAThreadWithManyVoicesShowsThreeFacesAndCountsTheRest() {
        sample()
        val id = agent.addOnLine(3, "body" to "Extract this.")
        reviewer.reply(id, "Agreed.")
        Agent(project, "Claude · docs", "claude-docs").reply(id, "Document it.")
        Agent(project, "Claude · tests", "claude-tests").reply(id, "Test it.")
        user.reply(id, "Will do.")

        assertEquals(
            "L3  Extract this., agent's move, with Claude, Claude · review, Claude · docs, 2 more",
            window.spoken("Extract this."),
        )
        assertEquals("+2", window.participantsShown("Extract this."))
        assertNull(window.participantsShown("Open"))
    }

    fun testAnUnlabelledWalkthroughBesideALabelledOneGetsItsOwnGuidedSection() {
        sample()
        agent.addOnLine(3, "order" to 1, "body" to "plain one")
        agent.addOnLine(6, "order" to 2, "body" to "plain two")
        agent.addOnLine(9, "order" to 1, "walkthrough" to "r2", "body" to "labelled")

        assertEquals(listOf("Guided  2", "Guided r2  1", "Open  3"), sections())
        assertTrue(section("Guided  2"), section("Guided  2").contains("(2/2)  L6  plain two"))
        assertTrue(section("Guided r2"), section("Guided r2").contains("(1/1)  L9  labelled"))
    }
}
