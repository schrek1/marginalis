package dev.marginalis.plugin.transport

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.guessProjectDir
import dev.marginalis.plugin.testing.Agent
import dev.marginalis.plugin.testing.MarginUser
import dev.marginalis.plugin.testing.MarginalisTestCase
import java.time.Instant

abstract class HttpContractTestCase : MarginalisTestCase() {

    protected val agent by lazy { Agent(project, "Claude", "claude-main") }
    protected val reviewer by lazy { Agent(project, "Claude · review", "claude-review") }
    protected val user by lazy { MarginUser(project, testRootDisposable) }

    protected fun sample(path: String = SAMPLE, lines: Int = 60): String {
        myFixture.addFileToProject(path, (1..lines).joinToString("\n") { lineText(it) } + "\n")
        return path
    }

    protected fun lineText(line: Int) = "val line$line = $line"

    protected fun Agent.add(vararg fields: Pair<String, Any?>): String {
        val response = post("comment_add", "body" to "note", *fields)
        assertEquals(response.body, 200, response.status)
        return response["thread_id"]!!.asString
    }

    protected fun Agent.addOnLine(line: Int, vararg fields: Pair<String, Any?>, file: String = SAMPLE): String =
        add("file" to file, "line" to line, "anchor_text" to lineText(line), *fields)

    protected fun Agent.reply(threadId: String, body: String = "answer", vararg fields: Pair<String, Any?>): Agent.Response {
        val response = post("comment_reply", "thread_id" to threadId, "body" to body, *fields)
        assertEquals(response.body, 200, response.status)
        return response
    }

    protected fun Agent.threads(vararg params: Pair<String, Any?>): List<JsonObject> {
        val response = get("comment_list", *params)
        assertEquals(response.body, 200, response.status)
        return response.json.getAsJsonArray("threads").map { it.asJsonObject }
    }

    protected fun Agent.ids(vararg params: Pair<String, Any?>): List<String> = threads(*params).map { it.id }

    protected fun Agent.summaryOf(threadId: String): JsonObject =
        threads("summary" to true).single { it.id == threadId }

    protected val JsonObject.id: String get() = this["thread_id"].asString

    protected fun JsonObject.messages(): List<JsonObject> = getAsJsonArray("messages").map { it.asJsonObject }

    protected fun JsonObject.string(field: String): String? = get(field)?.takeIf { !it.isJsonNull }?.asString

    protected fun JsonElement?.strings(): List<String> = (this as JsonArray).map { it.asString }

    protected fun Agent.Response.error(): String = this["error"]!!.asString

    protected fun Agent.Response.instant(field: String): Instant = Instant.parse(this[field]!!.asString)

    protected fun document(path: String = SAMPLE): Document =
        FileDocumentManager.getInstance().getDocument(project.guessProjectDir()!!.findFileByRelativePath(path)!!)!!

    protected fun eraseLine(line: Int, path: String = SAMPLE) {
        val document = document(path)
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(document.getLineEndOffset(line - 2), document.getLineEndOffset(line - 1))
        }
    }

    protected fun rewrite(path: String = SAMPLE, lineAt: (Int) -> String = { "// rewritten $it" }) {
        val document = document(path)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText((1..60).joinToString("\n", transform = lineAt))
        }
    }

    protected fun insertLinesAbove(line: Int, count: Int, path: String = SAMPLE) {
        val document = document(path)
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.getLineStartOffset(line - 1), "// inserted\n".repeat(count))
        }
    }

    protected fun assertRefused(response: Agent.Response, status: Int, mentioning: String? = null) {
        assertEquals(response.body, status, response.status)
        val error = response.error()
        assertFalse(error.isBlank())
        mentioning?.let { assertTrue("'$error' should mention '$it'", error.contains(it)) }
    }

    protected companion object {
        const val SAMPLE = "src/Sample.kt"
    }
}
