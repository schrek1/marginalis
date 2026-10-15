package dev.marginalis.plugin.transport

import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.ThreadStatus
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.Relays
import dev.marginalis.plugin.testing.SecondProject
import java.nio.file.Files

class MultipleProjectsTest : HttpContractTestCase() {

    private val sampleText = (1..60).joinToString("\n") { lineText(it) } + "\n"

    private fun twin(projectPath: String = "twin", extra: Map<String, String> = emptyMap()) =
        SecondProject.open(projectPath, mapOf("$projectPath/$SAMPLE" to sampleText) + extra)

    // FixMe: Known issue - #59 opening a second project logs an IDE error: the Margin tab is one light file shared by every project
    fun testOpeningASecondProjectLogsASharedMarginTabError() {
        twin().use { second -> assertFalse(second.sharedMarginTabErrors.isEmpty()) }
    }

    fun testPingListsEveryOpenProjectWithItsBranch() {
        twin(extra = mapOf("twin/.git/HEAD" to "ref: refs/heads/feature/login\n")).use { second ->
            val projects = agent.get("ping").json.getAsJsonArray("projects").map { it.asJsonObject }

            assertEquals(listOf(project.name, second.name), projects.map { it.string("name") })
            val listed = projects.single { it.string("name") == second.name }
            assertEquals(second.path, listed.string("path"))
            assertEquals("feature/login", listed.string("branch"))
        }
    }

    fun testADetachedHeadIsNamedByItsCommit() {
        twin(extra = mapOf("twin/.git/HEAD" to "0123456789abcdef0123456789abcdef01234567\n")).use { second ->
            assertEquals("01234567", branchOf(second))
        }
    }

    fun testALinkedWorktreeIsNamedByItsOwnBranch() {
        val files = mapOf(
            "twin/.git" to "gitdir: ../main/.git/worktrees/twin\n",
            "main/.git/worktrees/twin/HEAD" to "ref: refs/heads/review\n",
        )
        twin(extra = files).use { second -> assertEquals("review", branchOf(second)) }
    }

    fun testABranchIsFoundInAnEnclosingRepository() {
        val files = mapOf("repo/.git/HEAD" to "ref: refs/heads/main\n", "repo/modules/twin/$SAMPLE" to sampleText)
        SecondProject.open("repo/modules/twin", files).use { second -> assertEquals("main", branchOf(second)) }
    }

    fun testAGitDirectoryWithoutHeadNamesNoBranch() {
        twin(extra = mapOf("twin/.git/config" to "[core]\n")).use { second -> assertNull(branchOf(second)) }
    }

    fun testASharedPathResolvesToTheFirstOpenProjectUnlessOneIsNamed() {
        sample()
        twin().use { second ->
            val first = agent.add("file" to SAMPLE, "project" to null)
            val byName = agent.add("file" to SAMPLE, "project" to second.name)
            val byPath = agent.add("file" to SAMPLE, "project" to second.path)
            val bySuffix = agent.add("file" to SAMPLE, "project" to second.root.parent.fileName.toString() + "/twin")

            assertEquals(listOf(first), store.threads.all().map { it.id })
            assertEquals(listOf(byName, byPath, bySuffix), MarginalisStore.getInstance(second.project).threads.all().map { it.id })
        }
    }

    fun testAProjectThreadNeedsAProjectWhenSeveralAreOpen() {
        twin().use { second ->
            val refused = agent.post("comment_add", "body" to "b", "project" to null)

            assertRefused(refused, 404, "pass 'project'")
            val open = refused.json.getAsJsonArray("open_projects").map { it.asJsonObject }
            assertEquals(listOf(project.name, second.name), open.map { it.string("name") })
            assertEquals(second.path, open[1].string("path"))
            agent.add("project" to second.name)
            assertEquals(1, MarginalisStore.getInstance(second.project).threads.all().size)
        }
    }

    fun testWaitAndIdentitiesNeedAProjectWhenSeveralAreOpen() {
        twin().use {
            assertRefused(agent.get("comment_wait", "project" to null), 404, "pass 'project'")
            assertRefused(agent.get("comment_identities", "project" to null), 404, "pass 'project'")
            assertNotNull(agent.get("comment_identities", "project" to null)["open_projects"])
        }
    }

    fun testABareListingSpansEveryProjectAndCarriesNoRound() {
        sample()
        twin().use { second ->
            val here = agent.addOnLine(5)
            val there = Agent(second.project, "Claude", "claude-main").addOnLine(5)
            user.submitRound()

            val bare = agent.get("comment_list", "project" to null)

            val threads = bare.json.getAsJsonArray("threads").map { it.asJsonObject }
            assertEquals(mapOf(here to project.name, there to second.name), threads.associate { it.id to it.string("project") })
            assertNull(bare["handed_back_at"])
            assertNotNull(agent.get("comment_list")["handed_back_at"])
            assertEquals(listOf(there), agent.ids("project" to second.name))
        }
    }

    fun testABareSweepConsumesReceiptsInEveryProject() {
        sample()
        twin().use { second ->
            val there = Agent(second.project, "Claude · review", "claude-review").addOnLine(5)

            agent.get("comment_list", "project" to null)

            assertEquals(0, Agent(second.project, "Claude", "claude-main").summaryOf(there)["unread"].asInt)
        }
    }

    fun testRepliesAndStatusChangesFindTheThreadInAnyProject() {
        twin().use { second ->
            val there = Agent(second.project, "Claude", "claude-main").add("file" to SAMPLE)

            assertEquals(200, agent.post("comment_reply", "thread_id" to there, "body" to "b").status)
            assertEquals("resolved", agent.post("comment_resolve", "thread_id" to there)["status"]!!.asString)
            assertEquals("open", agent.post("comment_reopen", "thread_id" to there)["status"]!!.asString)
        }
    }

    fun testResolveAllResolvesEveryUnresolvedThreadInEveryProject() {
        sample()
        twin().use { second ->
            agent.addOnLine(5, "intent" to "fyi")
            agent.addOnLine(9)
            eraseLine(9)
            agent.threads()
            agent.post("comment_resolve", "thread_id" to agent.add())
            Agent(second.project, "Claude", "claude-main").add("file" to SAMPLE)

            val response = agent.post("comment_resolve_all", "project" to second.name)

            assertEquals(3, response["resolved"]!!.asInt)
            assertTrue(store.threads.all().all { it.status is ThreadStatus.Resolved })
            assertTrue(MarginalisStore.getInstance(second.project).threads.all().all { it.status is ThreadStatus.Resolved })
            assertEquals(setOf("Claude"), agent.threads().map { it.string("resolved_by") }.toSet())
        }
    }

    fun testResolveAllWithAFileResolvesThatPathInEachProject() {
        sample()
        sample("src/Other.kt")
        twin().use { second ->
            agent.addOnLine(5)
            val other = agent.addOnLine(5, file = "src/Other.kt")
            Agent(second.project, "Claude", "claude-main").addOnLine(5)

            assertEquals(2, agent.post("comment_resolve_all", "file" to SAMPLE)["resolved"]!!.asInt)
            assertEquals(listOf(other), agent.ids("status" to "open", "project" to null))
        }
    }

    fun testClearAllDeletesEveryThreadInEveryProject() {
        sample()
        twin().use { second ->
            agent.addOnLine(5)
            agent.post("comment_resolve", "thread_id" to agent.add())
            Agent(second.project, "Claude", "claude-main").add("file" to SAMPLE)

            assertEquals(3, agent.post("comment_clear_all", "project" to project.name)["cleared"]!!.asInt)
            assertEquals(emptyList<String>(), agent.ids("project" to null))
        }
    }

    fun testClearAllWithAFileClearsThatPathOnly() {
        sample()
        sample("src/Other.kt")
        twin().use { second ->
            agent.addOnLine(5)
            val kept = agent.addOnLine(5, file = "src/Other.kt")
            Agent(second.project, "Claude", "claude-main").add("file" to SAMPLE)

            assertEquals(2, agent.post("comment_clear_all", "file" to SAMPLE)["cleared"]!!.asInt)
            assertEquals(listOf(kept), agent.ids("project" to null))
        }
    }

    fun testRelayingIsIdempotentWithinAProjectOnly() {
        sample()
        twin().use { second ->
            val here = agent.add("file" to SAMPLE, "relayed" to Relays.reviewComment(1001))

            val unscoped = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "project" to null, "relayed" to Relays.reviewComment(1001))
            val there = agent.post("comment_add", "file" to SAMPLE, "body" to "b", "project" to second.name, "relayed" to Relays.reviewComment(1001))

            assertEquals(here, unscoped["thread_id"]!!.asString)
            assertTrue(unscoped["existing"]!!.asBoolean)
            assertNull(there["existing"])
            assertEquals(1, MarginalisStore.getInstance(second.project).threads.all().size)
        }
    }

    fun testAWaitOnAProjectThatClosesAnswersClosing() {
        val second = twin()
        try {
            val waiting = agent.startWait("project" to second.name)

            PlatformTestUtil.forceCloseProjectWithoutSaving(second.project)

            val wake = agent.await(waiting)
            assertFalse(wake["handed_back"]!!.asBoolean)
            assertEquals("closing", wake["reason"]!!.asString)
        } finally {
            second.close()
        }
    }

    fun testThreadsAreSavedWithTheProject() {
        twin().use { second ->
            val id = agent.add("file" to SAMPLE, "project" to second.name)
            val saved = second.root.resolve(".idea/marginalis.json")

            PlatformTestUtil.waitWithEventsDispatching("threads never saved", { Files.exists(saved) && Files.readString(saved).contains(id) }, 10)
        }
    }

    private fun branchOf(second: SecondProject): String? =
        agent.get("ping").json.getAsJsonArray("projects").map { it.asJsonObject }.single { it.string("name") == second.name }.string("branch")
}
