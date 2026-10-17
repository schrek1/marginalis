package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.marginalis.core.ProjectTabOrder
import dev.marginalis.plugin.testing.AllowReflection

class ProjectTabOrderTest : MarginTabTestCase() {

    private val sort get() = ProjectTabSort.getInstance(project)

    fun testByDefaultTheOldestThreadComesFirstAndAReplyDoesNotMoveIt() {
        val oldest = agent.ask("Thread a")
        agent.ask("Thread b")
        agent.ask("Thread c")

        agent.reply(oldest, "news on the oldest thread")

        assertEquals(listOf("Thread a", "Thread b", "Thread c"), listed())
    }

    fun testProjectStepsPostedOutOfOrderReadInStepOrder() {
        postSteps(3, 1, 2)

        assertEquals(listOf("Step 1", "Step 2", "Step 3"), listed())
    }

    fun testTheSortMenuStoresTheChoiceForTheProject() {
        tab.sortBy("Newest first")

        assertEquals(ProjectTabOrder.NEWEST_FIRST, sort.order)
    }

    fun testNewestFirstListsTheLatestThreadFirstAndKeepsStepsInStepOrder() {
        listOf("a", "b", "c").forEach { agent.ask("Thread $it") }
        postSteps(3, 1, 2)

        tab.sortBy("Newest first")

        assertEquals(listOf("Step 1", "Step 2", "Step 3", "Thread c", "Thread b", "Thread a"), listed())
    }

    fun testRecentActivityPutsTheThreadWithTheLatestReplyFirst() {
        listOf("a", "b", "c").forEach { agent.ask("Thread $it") }
        val middle = agent.threads().single { it.messages().first().string("body") == "Thread b" }.id
        tab.sortBy("Recent activity")

        agent.reply(middle, "news on b")

        assertEquals("Thread b", listed().first())
    }

    fun testWithOldestFirstANewThreadsDraftSitsBelowTheThreads() {
        listOf("a", "b", "c").forEach { agent.ask("Thread $it") }

        tab.newProjectThread()

        assertEquals(listOf("Thread a", "Thread b", "Thread c", DRAFT), listed())
    }

    fun testWithNewestFirstANewThreadsDraftSitsAboveTheThreads() {
        listOf("a", "b", "c").forEach { agent.ask("Thread $it") }
        tab.newProjectThread()

        tab.sortBy("Newest first")

        assertEquals(listOf(DRAFT, "Thread c", "Thread b", "Thread a"), listed())
    }

    fun testTheSortChoiceIsSavedAndReadBack() {
        tab.sortBy("Newest first")

        val saved = JDOMUtil.writeElement(XmlSerializer.serialize(sort.state))
        val reopened = ProjectTabSort().apply {
            loadState(XmlSerializer.deserialize(JDOMUtil.load(saved), ProjectTabSort.State::class.java))
        }

        assertEquals(ProjectTabOrder.NEWEST_FIRST, reopened.order)
    }

    @AllowReflection("the storage name and file are what keeps the choice apart from other settings; only the @State annotation states them")
    fun testTheSortChoiceIsKeptInTheWorkspaceFileUnderItsOwnName() {
        val storage = ProjectTabSort::class.java.getAnnotation(State::class.java)

        assertEquals("MarginalisProjectTabSort", storage.name)
        assertEquals(listOf(StoragePathMacros.WORKSPACE_FILE), storage.storages.map { it.value })
    }

    private fun postSteps(vararg orders: Int) = orders.forEach { order ->
        agent.add("body" to "Step $order", "order" to order, "walkthrough" to "tour")
    }

    // Each card by its first words, the draft as DRAFT: the order is the point, not the marks around it.
    private fun listed(): List<String> = shown().map { row ->
        if (row.startsWith("[draft]")) DRAFT else Regex("""(Thread \w|Step \d)""").find(row)!!.value
    }

    private companion object {
        const val DRAFT = "draft"
    }
}
