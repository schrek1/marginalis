package dev.marginalis.plugin.testing

import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.PlatformTestUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.plugin.store.MarginalisStore
import java.nio.file.Files
import java.nio.file.Path

abstract class SavedProjectTestCase : MarginalisTestCase() {

    private var opened: SecondProject? = null

    protected val shop: SecondProject get() = opened!!

    protected val agent: Agent get() = Agent(shop.project, "Claude", "claude-main")

    protected val reviewer: Agent get() = Agent(shop.project, "Claude · review", "claude-review")

    protected val user: MarginUser get() = MarginUser(shop.project, testRootDisposable)

    protected val shopStore: MarginalisStore get() = MarginalisStore.getInstance(shop.project)

    override fun tearDown() {
        try {
            opened?.close()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            opened = null
            super.tearDown()
        }
    }

    protected fun openShop(files: Map<String, String> = mapOf(SAMPLE to sampleText()), saved: String? = null): SecondProject {
        val all = files.mapKeys { (path, _) -> "shop/$path" } + listOfNotNull(saved?.let { "shop/.idea/marginalis.json" to it })
        return SecondProject.open("shop", all).also { opened = it }
    }

    protected fun reopen(whileClosed: (Path) -> Unit = {}): SecondProject = shop.reopen(whileClosed).also { opened = it }

    protected fun sampleText(lines: Int = 60): String = (1..lines).joinToString("\n") { lineText(it) } + "\n"

    protected fun lineText(line: Int) = "val line$line = $line"

    protected fun Agent.add(vararg fields: Pair<String, Any?>): String {
        val response = post("comment_add", "body" to "note", *fields)
        assertEquals(response.body, 200, response.status)
        return response["thread_id"]!!.asString
    }

    protected fun Agent.addOnLine(line: Int, vararg fields: Pair<String, Any?>, file: String = SAMPLE): String =
        add("file" to file, "line" to line, "anchor_text" to lineText(line), *fields)

    protected fun Agent.reply(threadId: String, body: String) {
        val response = post("comment_reply", "thread_id" to threadId, "body" to body)
        assertEquals(response.body, 200, response.status)
    }

    protected fun Agent.threads(vararg params: Pair<String, Any?>): List<JsonObject> {
        val response = get("comment_list", *params)
        assertEquals(response.body, 200, response.status)
        return response.json.getAsJsonArray("threads").map { it.asJsonObject }
    }

    protected fun Agent.thread(threadId: String, vararg params: Pair<String, Any?>): JsonObject =
        threads(*params).single { it["thread_id"].asString == threadId }

    protected fun Agent.summaryOf(threadId: String): JsonObject = thread(threadId, "summary" to true)

    protected fun JsonObject.string(field: String): String? = get(field)?.takeIf { !it.isJsonNull }?.asString

    protected fun JsonObject.messages(): List<JsonObject> = getAsJsonArray("messages").map { it.asJsonObject }

    protected fun waitForSave(containing: String) =
        waitUntil("'$containing' is saved") { Files.exists(shop.savedThreads) && Files.readString(shop.savedThreads).contains(containing) }

    protected fun waitUntil(what: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("never true: $what", condition, 10)

    protected fun document(path: String = SAMPLE): Document =
        FileDocumentManager.getInstance().getDocument(shop.project.guessProjectDir()!!.findFileByRelativePath(path)!!)!!

    protected fun editInIde(edit: (Document) -> Unit, path: String = SAMPLE) {
        val document = document(path)
        WriteCommandAction.runWriteCommandAction(shop.project) { edit(document) }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    protected fun gutterLinesShowing(text: String, path: String = SAMPLE): List<Int> = Gutter.linesShowing(shop.project, document(path), text)

    protected fun storedThread(threadId: String): CommentThread = shopStore.threads.byId(threadId)!!

    protected fun rewrite(root: Path, path: String, text: String) {
        Files.writeString(root.resolve(path), text)
    }

    protected companion object {
        const val SAMPLE = "src/Sample.kt"
    }
}
