package dev.marginalis.plugin.ui.toolwindow

import dev.marginalis.core.CommentThread
import dev.marginalis.core.Mark
import dev.marginalis.core.MarkSubject
import dev.marginalis.core.Message
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.settings.TimeFormat
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.ui.MarginalisIcons
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ToolWindowTreeTest : ToolWindowTestCase() {

    fun testAStepsRowWearsTheSignpostWithoutAStripe() {
        sample()
        agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "body" to "Signpost step")

        val mark = window.markShown("Signpost step")

        assertSame(MarginalisIcons.mark(Mark(MarkSubject.LINE, null, step = true)), mark)
        assertEquals(16, mark.iconWidth)
    }

    fun testAStepsHeaderNamesItsWalkthroughAsItsSectionDoesWhenTwoShareAName() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "earlier")
        val later = agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "body" to "later")

        val header = user.shownText(user.open(later)).lines().single { it.startsWith("<html>open · ") }
        val title = header.substringAfter("<b>").substringBefore("</b>")

        assertTrue(title, title.startsWith("tour · "))
        assertTrue(header, header.endsWith("</b> (1/1)</html>"))
        assertTrue(sections().toString(), sections().any { it.startsWith("$title  ") })
        assertTrue(section(title), section(title).contains("later"))
    }

    fun testAFilteredWalkthroughKeepsTheTitleThatTellsItFromItsHiddenNamesake() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "plain")
        val asked = agent.addOnLine(5, "order" to 1, "walkthrough" to "tour", "intent" to "question", "body" to "asked")
        val header = user.shownText(user.open(asked)).lines().single { it.startsWith("<html>open · ") }
        val title = header.substringAfter("<b>").substringBefore("</b>")

        window.filterBy("Questions")

        assertEquals(listOf(title), sections().map { it.substringBeforeLast("  ") })
        assertTrue(title, title.startsWith("tour · "))
    }

    fun testAnEmptyMarginShowsNoRowsAndSaysSo() {
        assertEquals(0, window.tree.rowCount)
        assertEquals("No margin threads yet", window.emptyText())
    }

    fun testAWalkthroughThatHopsBetweenFilesIsListedByStepNumberWithEachRowNamingItsFile() {
        interleaved()

        assertEquals(
            """
            SHOP-77  18
              (1/18)  OrderController.java L12  Step 1
              ✉ (2/18)  OrderService.java L20  Step 2
              ✉ (3/18)  OrderService.java L28  Step 3
              ✉ (4/18)  LoyaltyDiscountPolicy.java L8  Step 4
              ✉ (5/18)  LoyaltyDiscountPolicy.java L22  Step 5
              ✉ (6/18)  OrderService.java L41  Step 6
              ✉ (7/18)  OrderRepository.java L15  Step 7
              ✉ (8/18)  Order.java L10  Step 8
              ✉ (9/18)  OrderService.java L55  Step 9
              ✉ (10/18)  OrderController.java L30  Step 10
              ✉ (11/18)  add-loyalty-discount.sql L3  Step 11
              ✉ (12/18)  OrderServiceTest.java L14  Step 12
              ✉ (13/18)  OrderServiceTest.java L36  Step 13
              ✉ (14/18)  LoyaltyDiscountPolicy.java L40  Step 14
              ✉ (15/18)  LoyaltyDiscountPolicyTest.java L12  Step 15
              ✉ (16/18)  LoyaltyDiscountPolicyTest.java L30  Step 16
              ✉ (17/18)  OrderServiceTest.java L58  Step 17
              ✉ (18/18)  OrderController.java L45  Step 18
            """.trimIndent(),
            section("SHOP-77"),
        )
    }

    fun testByFolderAWalkthroughThatHopsBetweenFilesIsListedFolderByFolder() {
        interleaved()

        window.layOutWalkthroughsBy("By Folder")

        assertEquals(
            """
            SHOP-77  18
              acme/backend  18
                shop-orders/src  14
                  main  9
                    java/com/acme/orders  8
                      api  3
                        OrderController.java ✉2
                          (1/18)  L12  Step 1
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
            section("SHOP-77"),
        )
    }

    fun testOpenListsTheOrdinaryCommentsAloneAndNoStep() {
        interleaved()
        sample()
        agent.addOnLine(5, "body" to "loose")

        assertEquals(listOf("SHOP-77  18", "Open  1"), sections())
        assertEquals(
            """
            Open  1
              src  1
                Sample.kt ✉1
                  ✉ L5  loose
            """.trimIndent(),
            section("Open"),
        )
    }

    fun testTheResolvedSectionStartsFolded() {
        sample()
        val done = agent.addOnLine(5, "body" to "done")
        agent.post("comment_resolve", "thread_id" to done)

        assertEquals("Resolved  1", section("Resolved"))
        assertFalse(window.isExpanded("Resolved"))

        window.toggleFolding("Resolved")
        window.toggleFolding("src", under = "Resolved")
        window.toggleFolding("Sample.kt", under = "Resolved")

        assertEquals(
            """
            Resolved  1
              src  1
                Sample.kt
                  L5  done
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testADeepWalkthroughListsEveryStepNumberedOutOfTheWholeWalk() {
        val steps = deep()

        assertEquals(listOf("SHOP-42  33"), sections())
        val walkthrough = section("SHOP-42").lines().drop(1).map { it.trim() }
        assertEquals("(1/33)  Product.java L13  Step 1", walkthrough.first())
        assertEquals("✉ (8/33)  CustomerSettingsProvider.java L5  Step 8", walkthrough[7])
        assertEquals("✉ (33/33)  InvoiceSettingsConfigurationTest.java L5  Step 33", walkthrough.last())
        assertEquals((1..33).toList(), walkthrough.map { Regex("""\((\d+)/33\)""").find(it)!!.groupValues[1].toInt() })
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
            tour  3
              (1/3)  Sample.kt L3  one
              ✉ (2/3)  Sample.kt L6  two
              ✉ (3/3)  Sample.kt L9  three
            """.trimIndent(),
            section("tour"),
        )
    }

    fun testAWalkthroughWithoutALabelIsTitledWalkthrough() {
        sample()
        agent.addOnLine(3, "order" to 1, "body" to "one")
        agent.addOnLine(6, "order" to 2, "body" to "two")

        assertEquals(listOf("Walkthrough  2"), sections())
        assertTrue(section("Walkthrough"), section("Walkthrough").contains("(1/2)  Sample.kt L3  one"))
    }

    fun testTwoWalkthroughsEachGetASectionAndTheirStepsShowJustTheirNumber() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "SHOP-42", "body" to "first walk, one")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "SHOP-42", "body" to "first walk, two")
        agent.addOnLine(9, "order" to 1, "walkthrough" to "r2", "body" to "second walk")

        assertEquals(listOf("SHOP-42  2", "r2  1"), sections())
        assertTrue(section("SHOP-42"), section("SHOP-42").contains("(1/2)  Sample.kt L3  first walk, one"))
        assertTrue(section("r2"), section("r2").contains("(1/1)  Sample.kt L9  second walk"))
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

    fun testProjectStepsOfAWalkthroughTakeTheirPlaceInStepOrder() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "code")
        agent.add("order" to 3, "walkthrough" to "tour", "body" to "wrap up")
        agent.add("order" to 2, "walkthrough" to "tour", "body" to "the big picture")

        assertEquals(
            """
            tour  3
              ✉ (1/3)  Sample.kt L3  code
              ✉ (2/3)  project  the big picture
              ✉ (3/3)  project  wrap up
            """.trimIndent(),
            section("tour"),
        )
    }

    fun testByFolderProjectStepsOfAWalkthroughComeFirstInStepOrder() {
        window.layOutWalkthroughsBy("By Folder")
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "code")
        agent.add("order" to 3, "walkthrough" to "tour", "body" to "wrap up")
        agent.add("order" to 2, "walkthrough" to "tour", "body" to "the big picture")

        assertEquals(
            """
            tour  3
              Project  2
                ✉ (2/3)  project  the big picture
                ✉ (3/3)  project  wrap up
              src  1
                Sample.kt ✉1
                  ✉ (1/3)  L3  code
            """.trimIndent(),
            section("tour"),
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

        assertEquals(listOf("Walkthrough  1  ·  1 blocker", "Open  2  ·  2 blockers", "Resolved  1"), sections())
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

    fun testAnUnlabelledWalkthroughBesideALabelledOneGetsItsOwnSection() {
        sample()
        agent.addOnLine(3, "order" to 1, "body" to "plain one")
        agent.addOnLine(6, "order" to 2, "body" to "plain two")
        agent.addOnLine(9, "order" to 1, "walkthrough" to "r2", "body" to "labelled")

        assertEquals(listOf("Walkthrough  2", "r2  1"), sections())
        assertTrue(section("Walkthrough  2"), section("Walkthrough  2").contains("(2/2)  Sample.kt L6  plain two"))
        assertTrue(section("r2"), section("r2").contains("(1/1)  Sample.kt L9  labelled"))
    }

    fun testAResolvedStepKeepsItsPlaceInItsWalkthroughAndReopeningItChangesOnlyItsLook() {
        sample()
        val first = agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two")
        agent.addOnLine(9, "body" to "loose")
        agent.post("comment_resolve", "thread_id" to first)

        assertEquals(
            """
            tour  2
              (1/2)  Sample.kt L3  one
              ✉ (2/2)  Sample.kt L6  two
            Open  1
              src  1
                Sample.kt ✉1
                  ✉ L9  loose
            """.trimIndent(),
            window.outline(),
        )

        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to first) }

        assertEquals(listOf("tour  2", "Open  1"), sections())
        assertEquals("✉ (1/2)  Sample.kt L3  one", section("tour").lines()[1].trim())
    }

    fun testAWalkthroughWhoseEveryStepIsResolvedIsListedWithTheResolvedThreads() {
        everyStepResolvedBesideALooseComment()
        listOf("Resolved", "tour").forEach { window.toggleFolding(it, under = "Resolved") }

        assertEquals(listOf("Open  1", "Resolved  2"), sections())
        assertEquals(
            """
            Resolved  2
              tour  2
                (1/2)  Sample.kt L3  one
                (2/2)  Sample.kt L6  two
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testAResolvedWalkthroughComesBeforeTheResolvedCommentsBesideIt() {
        everyStepResolvedBesideALooseComment()
        agent.post("comment_resolve", "thread_id" to agent.addOnLine(12, "body" to "settled"))
        listOf("Resolved", "tour", "src").forEach { window.toggleFolding(it, under = "Resolved") }

        assertEquals(
            """
            Resolved  3
              tour  2
                (1/2)  Sample.kt L3  one
                (2/2)  Sample.kt L6  two
              src  1
                Sample.kt
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testReopeningAStepOfAResolvedWalkthroughBringsTheWholeWalkthroughBack() {
        val steps = everyStepResolvedBesideALooseComment()
        window.outline()

        window.rebuiltBy { agent.post("comment_reopen", "thread_id" to steps.last()) }

        assertEquals(listOf("tour  2", "Open  1"), sections())
        assertEquals(listOf("(1/2)  Sample.kt L3  one", "✉ (2/2)  Sample.kt L6  two"), section("tour").lines().drop(1).map { it.trim() })
    }

    fun testAWalkthroughWithResolvedAndOrphanedStepsButNothingOpenStaysActive() {
        sample()
        val first = agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "body" to "lost step")
        agent.post("comment_resolve", "thread_id" to first)
        eraseLine(20)

        assertEquals(listOf("tour  2"), sections())
        assertTrue(window.isExpanded("tour"))
        assertEquals(listOf("(1/2)  Sample.kt L3  one", "(2/2)  Sample.kt L20  lost step"), section("tour").lines().drop(1).map { it.trim() })
    }

    fun testAnOrphanedStepKeepsItsPlaceInItsWalkthroughAndOrphanedListsOnlyComments() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "body" to "lost step")
        agent.addOnLine(30, "body" to "lost comment")
        eraseLine(30)
        eraseLine(20)

        assertEquals(
            """
            tour  2
              ✉ (1/2)  Sample.kt L3  one
              (2/2)  Sample.kt L20  lost step
            Orphaned  1
              src  1
                Sample.kt
                  L30  lost comment
            """.trimIndent(),
            window.outline(),
        )
    }

    fun testByFolderAnOrphanedStepKeepsItsPlaceInItsWalkthrough() {
        orphanedSecondStep()

        window.layOutWalkthroughsBy("By Folder")

        assertEquals(
            """
            tour  2
              src  2
                Sample.kt ✉1
                  ✉ (1/2)  L3  one
                  (2/2)  L20  lost step
            """.trimIndent(),
            section("tour"),
        )
        assertEquals(listOf("tour  2"), sections())
    }

    fun testByFileAnOrphanedStepKeepsItsPlaceInItsWalkthrough() {
        orphanedSecondStep()

        window.layOutWalkthroughsBy("By File")

        assertEquals(
            """
            tour  2
              Sample.kt  steps 1–2 ✉1
                ✉ (1/2)  L3  one
                (2/2)  L20  lost step
            """.trimIndent(),
            section("tour"),
        )
        assertEquals(listOf("tour  2"), sections())
    }

    fun testByFolderAResolvedWalkthroughIsLaidOutByFolderInsideResolved() {
        everyStepResolvedBesideALooseComment()

        window.layOutWalkthroughsBy("By Folder")
        listOf("Resolved", "tour", "src", "Sample.kt").forEach { window.toggleFolding(it, under = "Resolved") }

        assertEquals(
            """
            Resolved  2
              tour  2
                src  2
                  Sample.kt
                    (1/2)  L3  one
                    (2/2)  L6  two
            """.trimIndent(),
            section("Resolved"),
        )
    }

    fun testByFileAResolvedWalkthroughIsLaidOutByFileInsideResolved() {
        everyStepResolvedBesideALooseComment()

        window.layOutWalkthroughsBy("By File")
        listOf("Resolved", "tour", "Sample.kt").forEach { window.toggleFolding(it, under = "Resolved") }

        assertEquals(
            """
            Resolved  2
              tour  2
                Sample.kt  steps 1–2
                  (1/2)  L3  one
                  (2/2)  L6  two
            """.trimIndent(),
            section("Resolved"),
        )
    }

    private fun orphanedSecondStep() {
        sample()
        agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one")
        agent.addOnLine(20, "order" to 2, "walkthrough" to "tour", "body" to "lost step")
        eraseLine(20)
    }

    private fun everyStepResolvedBesideALooseComment(): List<String> {
        sample()
        val steps = listOf(
            agent.addOnLine(3, "order" to 1, "walkthrough" to "tour", "body" to "one"),
            agent.addOnLine(6, "order" to 2, "walkthrough" to "tour", "body" to "two"),
        )
        agent.addOnLine(9, "body" to "loose")
        steps.forEach { agent.post("comment_resolve", "thread_id" to it) }
        return steps
    }

    fun testTwoUnlabelledWalkthroughsAreTwoSectionsToldApartByWhenTheyStarted() {
        projectStep(walkthroughId = "morning", body = "morning walk", createdAt = today(9, 15))
        projectStep(walkthroughId = "afternoon", body = "afternoon walk", createdAt = today(14, 40))
        val settings = MarginalisSettings.getInstance()
        val before = settings.state.timeFormat
        try {
            settings.state.timeFormat = TimeFormat.TWENTY_FOUR_HOUR.stored

            assertEquals(listOf("Walkthrough · 09:15  1", "Walkthrough · 14:40  1"), sections())
            assertTrue(section("Walkthrough · 09:15"), section("Walkthrough · 09:15").contains("morning walk"))
            assertTrue(section("Walkthrough · 14:40"), section("Walkthrough · 14:40").contains("afternoon walk"))
        } finally {
            settings.state.timeFormat = before
        }
    }

    fun testTwoWalkthroughsPostedOneAfterTheOtherWithoutALabelAreListedApart() {
        sample()
        agent.post("comment_add_batch", "items" to listOf(1, 2).map { mapOf("file" to SAMPLE, "line" to it * 3, "anchor_text" to lineText(it * 3), "order" to it, "body" to "first walk $it") })
        agent.post("comment_add_batch", "items" to listOf(1, 2, 3).map { mapOf("file" to SAMPLE, "line" to it * 10, "anchor_text" to lineText(it * 10), "order" to it, "body" to "second walk $it") })

        val guided = sections()
        assertEquals(2, guided.size)
        assertTrue(guided.toString(), guided.all { it.startsWith("Walkthrough · ") })
        assertEquals(guided.toString(), 2, guided.map { it.substringBeforeLast("  ") }.toSet().size)
        assertTrue(window.outline(), window.outline().contains("(2/2)  Sample.kt L6  first walk 2"))
        assertTrue(window.outline(), window.outline().contains("(3/3)  Sample.kt L30  second walk 3"))
    }

    private fun today(hour: Int, minute: Int): Instant =
        LocalDateTime.now().withHour(hour).withMinute(minute).withSecond(0).atZone(ZoneId.systemDefault()).toInstant()

    private fun projectStep(walkthroughId: String, body: String, createdAt: Instant) {
        val step = CommentThread(null, null, null, createdAt = createdAt, order = 1, walkthroughId = walkthroughId)
        step.addMessage(Message(Authors.user, body))
        MarginalisStore.getInstance(project).threads.add(step)
    }
}
