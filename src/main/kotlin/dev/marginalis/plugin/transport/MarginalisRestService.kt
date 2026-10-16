package dev.marginalis.plugin.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import dev.marginalis.core.Addressee
import dev.marginalis.core.AnchorPolicy
import dev.marginalis.core.Author
import dev.marginalis.core.Classification
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Cursor
import dev.marginalis.core.Identities
import dev.marginalis.core.Identity
import dev.marginalis.core.Intent
import dev.marginalis.core.LiveThread
import dev.marginalis.core.Message
import dev.marginalis.core.Reference
import dev.marginalis.core.Referent
import dev.marginalis.core.RelayOutcome
import dev.marginalis.core.Relayed
import dev.marginalis.core.Resolution
import dev.marginalis.core.ThreadOrder
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.ThreadSummary
import dev.marginalis.core.Turn
import dev.marginalis.core.WaitTimeout
import dev.marginalis.core.Wake
import dev.marginalis.core.getOrElse
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.MarginalisMarkers
import dev.marginalis.plugin.ui.MarkdownRenderer
import dev.marginalis.plugin.ui.WalkthroughNavigator
import dev.marginalis.plugin.ui.tab.ProjectTab
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.RestService
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Properties

// Handlers run on a Netty I/O thread: editor markup is marshalled to the EDT,
// document/VFS reads take read actions. Wire `line` is 1-based; core's is 0-based.
class MarginalisRestService : RestService() {

    private val pluginVersion: String? by lazy {
        javaClass.classLoader.getResourceAsStream("marginalis/plugin-version.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")?.takeIf { it.isNotBlank() }
        }
    }

    override fun getServiceName(): String = "marginalis"

    override fun isMethodSupported(method: HttpMethod): Boolean =
        method === HttpMethod.GET || method === HttpMethod.POST

    // Two cooperating local participants over loopback: skip the built-in
    // server's origin-confirmation dialog, which would block headless calls.
    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean = true

    // Default is 30/min — an agent annotating a file in one turn bursts past that.
    override fun getMaxRequestsPerMinute(): Int = 1000

    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): String? {
        val endpoint = urlDecoder.path().removePrefix("/api/${getServiceName()}").trim('/')
        when (endpoint) {
            "ping" -> sendJson(pingInfo(), request, context)
            "agent_guide" -> sendAgentGuide(request, context)
            "comment_add" -> post(request, context) { handleCommentAdd(it, request, context) }
            "comment_add_batch" -> post(request, context) { handleCommentAddBatch(it, request, context) }
            "comment_reply" -> post(request, context) { handleCommentReply(it, request, context) }
            "comment_resolve" -> post(request, context) { handleStatusChange(it, request, context, resolve = true) }
            "comment_reopen" -> post(request, context) { handleStatusChange(it, request, context, resolve = false) }
            "comment_resolve_all" -> post(request, context) { handleResolveAll(it, request, context) }
            "comment_reanchor" -> post(request, context) { handleReanchor(it, request, context) }
            "comment_reanchor_all" -> post(request, context) { handleReanchorAll(it, request, context) }
            "comment_clear_all" -> post(request, context) { handleClearAll(it, request, context) }
            "comment_list" -> handleCommentList(urlDecoder, request, context)
            "comment_wait" -> handleCommentWait(urlDecoder, request, context)
            "comment_identities" -> handleCommentIdentities(urlDecoder, request, context)
            "navigate" -> post(request, context) { handleNavigate(it, request, context) }
            else -> sendError(HttpResponseStatus.NOT_FOUND, "unknown endpoint '$endpoint'", request, context)
        }
        return null
    }

    // CI checks the served guide mentions every endpoint.
    private fun sendAgentGuide(request: FullHttpRequest, context: ChannelHandlerContext) {
        val guide = javaClass.classLoader.getResourceAsStream("marginalis/agent-guide.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return sendError(HttpResponseStatus.NOT_FOUND, "agent guide resource missing from this build", request, context)
        val bytes = guide.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/markdown; charset=utf-8")
        sendResponse(request, context, response)
    }

    private fun pingInfo(): JsonObject = JsonObject().apply {
        addProperty("status", "ok")
        val appInfo = ApplicationInfo.getInstance()
        addProperty("ide", "${appInfo.versionName} ${appInfo.fullVersion}")
        // Stamped at build time (processResources) rather than read from the
        // plugin manager: both platform lookups are internal API.
        pluginVersion?.let { addProperty("version", it) }
        ApplicationManager.getApplication().runReadAction {
            add("projects", openProjectsJson())
        }
    }

    // Branch is the only thing telling same-layout worktrees apart.
    private fun openProjectsJson(): JsonArray = JsonArray().apply {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            add(
                JsonObject().apply {
                    addProperty("name", project.name)
                    val path = project.guessProjectDir()?.path ?: project.basePath
                    addProperty("path", path)
                    path?.let { p -> GitBranches.of(Path.of(p))?.let { addProperty("branch", it) } }
                },
            )
        }
    }

    private fun agentAuthor(json: JsonObject): Author.Agent =
        agentAuthor(json.stringOrNull("author_name"), json.stringOrNull("author_id"))

    private fun agentAuthor(params: Map<String, List<String>>): Author.Agent =
        agentAuthor(params["author_name"]?.firstOrNull(), params["author_id"]?.firstOrNull())

    private fun agentAuthor(name: String?, id: String?): Author.Agent {
        if (name == null && id == null) return Authors.agent
        return Author.Agent(name ?: Authors.agent.displayName, id)
    }

    private fun post(request: FullHttpRequest, context: ChannelHandlerContext, handler: (JsonObject) -> Unit) {
        if (request.method() !== HttpMethod.POST) {
            return sendError(HttpResponseStatus.METHOD_NOT_ALLOWED, "this endpoint requires POST", request, context)
        }
        val json = try {
            JsonParser.parseString(request.content().toString(Charsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            return sendError(HttpResponseStatus.BAD_REQUEST, "body must be a JSON object", request, context)
        }
        handler(json)
    }

    private fun handleCommentAdd(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val outcome = addComment(json)
        send(outcome.json, outcome.status, request, context)
    }

    // Items succeed or fail independently (200 with per-item errors): making the
    // caller unpick which notes survived a single 409 costs more than a round trip.
    private fun handleCommentAddBatch(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val items = json.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return sendError(
                HttpResponseStatus.BAD_REQUEST,
                "missing 'items': comment_add_batch takes {\"items\": [ … ]}, an array of comment_add payloads. " +
                    "Top-level 'author_name', 'author_id' and 'project' apply to every item unless the item says " +
                    "otherwise.",
                request, context,
            )
        val results = JsonArray()
        var created = 0
        for (element in items) {
            if (!element.isJsonObject) {
                results.add(
                    JsonObject().apply {
                        addProperty("error", "each item must be a JSON object — one comment_add payload per item.")
                    },
                )
                continue
            }
            val outcome = addComment(withBatchDefaults(element.asJsonObject, json))
            if (outcome.created) created++
            results.add(outcome.json)
        }
        sendJson(
            JsonObject().apply {
                add("results", results)
                addProperty("created", created)
            },
            request, context,
        )
    }

    private fun withBatchDefaults(item: JsonObject, envelope: JsonObject): JsonObject {
        val merged = item.deepCopy()
        for (field in BATCH_DEFAULTS) {
            if (!merged.has(field) && envelope.has(field)) merged.add(field, envelope.get(field))
        }
        return merged
    }

    private class AddOutcome(val status: HttpResponseStatus, val json: JsonObject, val created: Boolean = false) {
        companion object {
            fun created(json: JsonObject) = AddOutcome(HttpResponseStatus.OK, json, created = true)
            fun existing(json: JsonObject) = AddOutcome(HttpResponseStatus.OK, json.apply { addProperty("existing", true) })
            fun refused(status: HttpResponseStatus, message: String, reason: String? = null) =
                AddOutcome(status, errorJson(message, reason))
        }
    }

    // Returns an outcome instead of responding so the batch judges anchors by
    // exactly the same rules, rather than becoming a second contract.
    private fun addComment(json: JsonObject): AddOutcome {
        payloadError(json)?.let { return AddOutcome.refused(HttpResponseStatus.BAD_REQUEST, it) }
        val body = json.stringOrNull("body")
            ?: return AddOutcome.refused(
                HttpResponseStatus.BAD_REQUEST,
                "missing 'body': a thread is something said about code. Pass the note itself as 'body'.",
            )
        val anchorText = json.stringOrNull("anchor_text")
        val file = json.stringOrNull("file")
        val wireLine = json.intOrNull("line")
        anchorIntentError(json, file, anchorText)?.let {
            return AddOutcome.refused(HttpResponseStatus.BAD_REQUEST, it)
        }
        val order = json.intOrNull("order")
        val walkthroughLabel = json.stringOrNull("walkthrough")
        val projectFilter = json.stringOrNull("project")
        val classification = Classification.parse(
            intent = json.stringOrNull("intent"),
            severity = json.stringOrNull("severity"),
            label = json.stringOrNull("label"),
        ).getOrElse { return AddOutcome.refused(HttpResponseStatus.BAD_REQUEST, it) }
        val to = Addressee.parse(json.stringOrNull("to"))
            .getOrElse { return AddOutcome.refused(HttpResponseStatus.BAD_REQUEST, it) }
        val relayed = Relayed.parse(json.get("relayed"), to)
            .getOrElse { return AddOutcome.refused(HttpResponseStatus.BAD_REQUEST, it) }
        relayed?.let { existingRelayJson(it, projectFilter) }?.let { return AddOutcome.existing(it) }
        if (relayed != null && deletedRelay(relayed, projectFilter)) {
            return AddOutcome.refused(
                HttpResponseStatus.CONFLICT,
                deletedRelayMessage(relayed),
                DELETED_RELAY,
            )
        }

        val resolved = ApplicationManager.getApplication().runReadAction(
            Computable { if (file == null) resolveProject(projectFilter)?.to(null) else resolveFile(file, projectFilter) },
        ) ?: return AddOutcome(
            HttpResponseStatus.NOT_FOUND,
            resolutionErrorJson(
                if (file == null) projectResolutionFailure(projectFilter) else resolutionFailure(file, projectFilter),
            ),
        )
        val project = resolved.first
        val vFile = resolved.second

        var error: String? = null
        var errorStatus = HttpResponseStatus.BAD_REQUEST
        var errorReason: String? = null
        var thread: CommentThread? = null
        var relaying: JsonObject? = null
        var adjusted = false

        ApplicationManager.getApplication().invokeAndWait {
            val store = MarginalisStore.getInstance(project)
            relaying = relayed?.let { store.threads.relaying(it.key) }?.let { addedJson(it, store.syncLine(it)) }
            if (relaying != null) return@invokeAndWait
            // Agents never create segments — the selection gesture is human.
            val created = if (wireLine == null || file == null || vFile == null) {
                CommentThread(
                    file, line = null, anchorText = null,
                    order = order, walkthrough = walkthroughLabel, severity = classification.severity,
                    intent = classification.intent, label = classification.label,
                )
            } else {
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                if (document == null) {
                    error = "'$file' has no text document (binary or too large?)"
                    return@invokeAndWait
                }
                val placed = when (val outcome = resolveAnchoredLine(document, file, wireLine, anchorText)) {
                    is AnchorOutcome.Stale -> {
                        error = outcome.message
                        errorStatus = HttpResponseStatus.CONFLICT
                        errorReason = STALE_ANCHOR
                        return@invokeAndWait
                    }
                    is AnchorOutcome.Placed -> outcome
                }
                adjusted = placed.adjusted
                CommentThread(
                    file, placed.line, lineText(document, placed.line),
                    order = order, walkthrough = walkthroughLabel, severity = classification.severity,
                    intent = classification.intent, label = classification.label,
                ).also { MarginalisMarkers.attach(project, it, document) }
            }
            val message = Message(agentAuthor(json), body, to = to, relayed = relayed)
            created.addMessage(message)
            MarginalisStore.getInstance(project).threads.add(created)
            // Opening an editor inside this block waits on background reads its lock holds back (30 s seen), and
            // the agent's call waits with it: the tab opens as an event of its own once the call has its answer.
            if (created.isProjectLevel) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) ProjectTab.showBehindSelection(project)
                }
            }
            maybeNotify(project, created, message)
            thread = created
        }

        relaying?.let { return AddOutcome.existing(it) }
        val added = thread ?: return AddOutcome.refused(errorStatus, error ?: "internal error", errorReason)
        return AddOutcome.created(addedJson(added).apply { added.line?.let { addProperty("line_adjusted", adjusted) } })
    }

    private fun existingRelayJson(relayed: Relayed, projectFilter: String?): JsonObject? =
        ApplicationManager.getApplication().runReadAction(
            Computable {
                admittedStores(projectFilter).values.firstNotNullOfOrNull { store ->
                    store.threads.relaying(relayed.key)?.let { addedJson(it, store.syncLine(it)) }
                }
            },
        )

    private fun deletedRelayMessage(relayed: Relayed): String =
        "comment ${relayed.commentId} started a relayed thread the user deleted; it won't be recreated. " +
            "Skip it, and its replies with it."

    private fun deletedRelay(relayed: Relayed, projectFilter: String?): Boolean =
        ApplicationManager.getApplication().runReadAction(
            Computable { admittedStores(projectFilter).values.any { it.threads.isDeletedRelay(relayed.key) } },
        )

    private fun addedJson(thread: CommentThread, line: Int? = thread.line): JsonObject = JsonObject().apply {
        addProperty("thread_id", thread.id)
        thread.file?.let { addProperty("file", it) }
        line?.let { addProperty("line", it + 1) }
        addProperty("status", thread.status.kind.name.lowercase())
    }

    private fun payloadError(json: JsonObject): String? {
        textFieldError(json, TEXT_FIELDS)?.let { return it }
        if (json.has("body") && json.stringOrNull("body")?.isBlank() == true) {
            return "'body' is empty: a thread with nothing said in it is not worth anchoring. Pass the note text."
        }
        if (json.has("order") && json.intOrNull("order") == null) {
            return "'order' must be an integer (1, 2, 3 …) — a step's position in a walkthrough. Omit it for an " +
                "ordinary thread."
        }
        return null
    }

    private fun textFieldError(json: JsonObject, fields: List<Pair<String, String>>): String? =
        fields.firstOrNull { (field, _) -> json.has(field) && json.stringOrNull(field) == null }
            ?.let { (field, what) -> "'$field' must be a string — $what." }

    private fun anchorIntentError(json: JsonObject, file: String?, anchorText: String?): String? = when {
        json.has("line") && json.intOrNull("line") == null ->
            "'line' must be an integer (1-based) — omit it entirely to address the file as a whole."
        file == null && json.intOrNull("line") != null ->
            "'line' without 'file': a line is a place in a file. Pass the 'file' it belongs to, or drop " +
                "'line' too to address the project as a whole."
        !json.has("line") && anchorText != null ->
            "'anchor_text' without 'line': there is nothing to anchor to. Pass the 'line' (1-based) it " +
                "belongs to, or drop 'anchor_text' to address the file as a whole."
        else -> null
    }

    private fun lineText(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

    private fun maybeNotify(project: Project, thread: CommentThread, message: Message) {
        if (!message.notifiesUser || !MarginalisSettings.getInstance().state.notifyOnAgentReply) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val selectedFile = FileEditorManager.getInstance(project).selectedTextEditor
                ?.let { FileDocumentManager.getInstance().getFile(it.document) }
            val selectedRel = selectedFile?.let { file ->
                project.guessProjectDir()?.let { base -> VfsUtilCore.getRelativePath(file, base) }
            }
            if (thread.file != null && selectedRel == thread.file) return@invokeLater
            if (thread.isProjectLevel && ProjectTab.isInFront(project)) return@invokeLater
            val where = thread.file?.plus(thread.line?.let { ":${it + 1}" } ?: "") ?: project.name
            NotificationGroupManager.getInstance().getNotificationGroup("Marginalis")
                .createNotification(
                    StringUtil.escapeXmlEntities("${message.author.displayName} · $where"),
                    StringUtil.escapeXmlEntities(
                        StringUtil.shortenTextWithEllipsis(MarkdownRenderer.previewText(message.body), 120, 0),
                    ),
                    NotificationType.INFORMATION,
                )
                .addAction(
                    NotificationAction.createSimpleExpiring("Open Thread") {
                        WalkthroughNavigator.navigateTo(project, thread)
                    },
                )
                .notify(project)
        }
    }

    private sealed class AnchorOutcome {
        class Placed(val line: Int, val adjusted: Boolean) : AnchorOutcome()
        class Stale(val message: String) : AnchorOutcome()
    }

    private fun resolveAnchoredLine(document: Document, file: String, wireLine: Int, anchorText: String?): AnchorOutcome =
        when (
            val resolved = AnchorPolicy.resolveHint(
                lineCount = document.lineCount,
                lineTextAt = { lineText(document, it) },
                hintLine = wireLine - 1,
                anchorText = anchorText,
            )
        ) {
            is AnchorPolicy.HintResolution.Placed -> AnchorOutcome.Placed(resolved.line, resolved.adjusted)
            is AnchorPolicy.HintResolution.OutOfRange -> AnchorOutcome.Stale(
                "line $wireLine out of range: '$file' has ${resolved.lineCount} lines. Re-read the file.",
            )
            AnchorPolicy.HintResolution.NoMatch -> AnchorOutcome.Stale(
                "anchor_text does not match line $wireLine or the ±${AnchorPolicy.SEARCH_WINDOW} lines " +
                    "around it. The file has probably changed — re-read it.",
            )
        }

    private fun handleCommentReply(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val (project, thread) = lookupThread(json, request, context) ?: return
        textFieldError(json, REPLY_TEXT_FIELDS)
            ?.let { return sendBadRequest(it, request, context) }
        val body = json.stringOrNull("body")
            ?: return sendError(HttpResponseStatus.BAD_REQUEST, "missing 'body'", request, context)
        if (body.isBlank()) {
            return sendBadRequest("'body' is empty: a reply with nothing said in it answers nothing. Pass the reply text.", request, context)
        }
        val to = Addressee.parse(json.stringOrNull("to")).getOrElse { return sendBadRequest(it, request, context) }
        val relayed = Relayed.parse(json.get("relayed"), to).getOrElse { return sendBadRequest(it, request, context) }
        val threads = MarginalisStore.getInstance(project).threads
        val message = Message(agentAuthor(json), body, to = to, relayed = relayed)
        if (relayed == null) {
            thread.addMessage(message)
        } else {
            var outcome: RelayOutcome? = null
            ApplicationManager.getApplication().invokeAndWait { outcome = threads.relayInto(thread, message) }
            when (val relay = outcome) {
                is RelayOutcome.Existing ->
                    return sendJson(replyJson(relay.message, thread).apply { addProperty("existing", true) }, request, context)
                is RelayOutcome.Elsewhere -> return send(
                    errorJson(
                        "comment ${relayed.commentId} is already relayed in thread '${relay.thread.id}'" +
                            "${relay.thread.file?.let { " ($it)" } ?: ""} — a GitHub comment lives in one thread. Reply there, or skip it.",
                        RELAYED_ELSEWHERE,
                    ).apply { addProperty("thread_id", relay.thread.id) },
                    HttpResponseStatus.CONFLICT, request, context,
                )
                RelayOutcome.Deleted ->
                    return sendError(HttpResponseStatus.CONFLICT, deletedRelayMessage(relayed), request, context, DELETED_RELAY)
                is RelayOutcome.Added, null -> Unit
            }
        }
        threads.notifyChanged(thread)
        maybeNotify(project, thread, message)
        sendJson(replyJson(message, thread), request, context)
    }

    private fun replyJson(message: Message, thread: CommentThread): JsonObject = JsonObject().apply {
        addProperty("message_id", message.id)
        addProperty("thread_id", thread.id)
        addProperty("status", thread.status.kind.name.lowercase())
    }

    private fun handleStatusChange(
        json: JsonObject,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        resolve: Boolean,
    ) {
        val (project, thread) = lookupThread(json, request, context) ?: return
        if (resolve) thread.resolve(agentAuthor(json)) else thread.reopen()
        MarginalisStore.getInstance(project).threads.notifyChanged(thread)
        sendJson(
            JsonObject().apply {
                addProperty("thread_id", thread.id)
                addProperty("status", thread.status.kind.name.lowercase())
            },
            request, context,
        )
    }

    private fun handleReanchor(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val (project, thread) = lookupThread(json, request, context) ?: return
        if (thread.line == null) {
            val subject = if (thread.isProjectLevel) "the project" else "the file"
            return sendError(
                HttpResponseStatus.BAD_REQUEST,
                "thread '${thread.id}' has no anchor to re-find — it is about $subject as a whole. A " +
                    "file-level thread orphans only when its file disappears, and reopens by itself when " +
                    "the path comes back; a project-level thread never orphans.",
                request, context,
            )
        }
        val wireLine = json.intOrNull("line")
            ?: return sendError(HttpResponseStatus.BAD_REQUEST, "missing or non-integer 'line' (1-based)", request, context)
        if (thread.status !is ThreadStatus.Orphaned) {
            return sendError(
                HttpResponseStatus.CONFLICT,
                "thread '${thread.id}' is ${thread.status.kind.name.lowercase()}, not orphaned — live anchors don't move.",
                request, context, NOT_ORPHANED,
            )
        }
        json.stringOrNull("file")?.takeIf { it != thread.file }?.let {
            return sendError(
                HttpResponseStatus.BAD_REQUEST,
                "cross-file re-anchor isn't supported (yet) — the thread belongs to '${thread.file}'.",
                request, context,
            )
        }
        val anchorText = json.stringOrNull("anchor_text")
        // Unreachable: core guarantees a thread with a line has a file.
        val path = thread.file
            ?: return sendError(HttpResponseStatus.BAD_REQUEST, "thread '${thread.id}' has no file", request, context)
        val vFile = ApplicationManager.getApplication()
            .runReadAction(Computable { project.guessProjectDir()?.findFileByRelativePath(path) })
            ?: return sendError(
                HttpResponseStatus.NOT_FOUND,
                "'$path' no longer exists in project '${project.name}' — if its content moved to another file, reply " +
                    "saying where and resolve; cross-file re-anchor isn't supported.",
                request, context,
            )

        var error: String? = null
        var errorStatus = HttpResponseStatus.BAD_REQUEST
        var errorReason: String? = null
        var landed = -1
        ApplicationManager.getApplication().invokeAndWait {
            val document = FileDocumentManager.getInstance().getDocument(vFile)
            if (document == null) {
                error = "'$path' has no text document (binary or too large?)"
                return@invokeAndWait
            }
            val placed = when (val outcome = resolveAnchoredLine(document, path, wireLine, anchorText)) {
                is AnchorOutcome.Stale -> {
                    error = outcome.message
                    errorStatus = HttpResponseStatus.CONFLICT
                    errorReason = STALE_ANCHOR
                    return@invokeAndWait
                }
                is AnchorOutcome.Placed -> outcome
            }
            thread.rescueTo(placed.line, lineText(document, placed.line))
            MarginalisMarkers.attach(project, thread, document)
            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
            landed = placed.line
        }
        if (error != null || landed < 0) {
            return sendError(errorStatus, error ?: "internal error", request, context, errorReason)
        }
        sendJson(
            JsonObject().apply {
                addProperty("thread_id", thread.id)
                addProperty("line", landed + 1)
                addProperty("status", thread.status.kind.name.lowercase())
            },
            request, context,
        )
    }

    // No line hint and a whole-file search window: after a rewrite the old line
    // number is worthless, the anchor text is not.
    private fun handleReanchorAll(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val file = json.stringOrNull("file")
            ?: return sendError(
                HttpResponseStatus.BAD_REQUEST,
                "missing 'file': comment_reanchor_all rescues the orphans of one file. Pass its project-relative " +
                    "path, e.g. 'src/App.kt'.",
                request, context,
            )
        val projectFilter = json.stringOrNull("project")
        val (project, vFile) = ApplicationManager.getApplication()
            .runReadAction(Computable { resolveFile(file, projectFilter) })
            ?: return sendResolutionError(resolutionFailure(file, projectFilter), request, context)

        val results = JsonArray()
        var rescued = 0
        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            val document = FileDocumentManager.getInstance().getDocument(vFile)
            if (document == null) {
                error = "'$file' has no text document (binary or too large?)"
                return@invokeAndWait
            }
            val store = MarginalisStore.getInstance(project)
            // A file-level orphan waits for its file to return, not for content.
            val orphans = store.threads.all()
                .filter { it.file == file && it.line != null && it.status is ThreadStatus.Orphaned }
            for (thread in orphans) {
                val found = AnchorPolicy.findAnchor(
                    lineCount = document.lineCount,
                    lineTextAt = { lineText(document, it) },
                    nearLine = thread.line ?: 0,
                    anchorText = thread.anchorText ?: "",
                    segment = thread.segment,
                    window = document.lineCount,
                )
                if (found == null) {
                    results.add(
                        JsonObject().apply {
                            addProperty("thread_id", thread.id)
                            addProperty("status", thread.status.kind.name.lowercase())
                        },
                    )
                    continue
                }
                thread.rescueTo(found.line, lineText(document, found.line))
                MarginalisMarkers.attach(project, thread, document)
                store.threads.notifyChanged(thread)
                rescued++
                results.add(
                    JsonObject().apply {
                        addProperty("thread_id", thread.id)
                        addProperty("line", found.line + 1)
                        addProperty("status", thread.status.kind.name.lowercase())
                    },
                )
            }
        }
        error?.let { return sendError(HttpResponseStatus.BAD_REQUEST, it, request, context) }
        sendJson(
            JsonObject().apply {
                addProperty("file", file)
                add("results", results)
                addProperty("rescued", rescued)
            },
            request, context,
        )
    }

    private fun handleResolveAll(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val fileFilter = json.stringOrNull("file")
        val resolver = agentAuthor(json)
        var resolved = 0
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val store = MarginalisStore.getInstance(project)
            for (thread in store.threads.all()) {
                if (fileFilter != null && thread.file != fileFilter) continue
                if (thread.status is ThreadStatus.Resolved) continue
                thread.resolve(resolver)
                store.threads.notifyChanged(thread)
                resolved++
            }
        }
        sendJson(JsonObject().apply { addProperty("resolved", resolved) }, request, context)
    }

    private fun handleClearAll(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val fileFilter = json.stringOrNull("file")
        var cleared = 0
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val store = MarginalisStore.getInstance(project)
            if (fileFilter == null) {
                cleared += store.clearAll().size
            } else {
                for (thread in store.threads.all().filter { it.file == fileFilter }) {
                    store.threads.remove(thread.id)
                    cleared++
                }
            }
        }
        sendJson(JsonObject().apply { addProperty("cleared", cleared) }, request, context)
    }

    private fun lookupThread(
        json: JsonObject,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Pair<Project, CommentThread>? {
        val threadId = json.stringOrNull("thread_id")
        if (threadId == null) {
            sendError(HttpResponseStatus.BAD_REQUEST, "missing 'thread_id'", request, context)
            return null
        }
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            MarginalisStore.getInstance(project).threads.byId(threadId)?.let { return project to it }
        }
        sendError(HttpResponseStatus.NOT_FOUND, "no thread with id '$threadId'", request, context)
        return null
    }

    private fun handleCommentList(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ) {
        val params = urlDecoder.parameters()
        val fileFilter = params["file"]?.firstOrNull()
        val statusFilter = params["status"]?.firstOrNull()?.let {
            try {
                ThreadStatus.Kind.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                return sendError(HttpResponseStatus.BAD_REQUEST, "invalid status '$it' (open|resolved|orphaned)", request, context)
            }
        }
        val intentFilter = Intent.parse(params["intent"]?.firstOrNull())
            .getOrElse { return sendBadRequest(it, request, context) }
        val awaitingFilter = Turn.parse(params["awaiting"]?.firstOrNull())
            .getOrElse { return sendBadRequest(it, request, context) }
        val unreadOnly = params["unread_only"]?.firstOrNull()?.toBoolean() ?: false
        val summaryOnly = params["summary"]?.firstOrNull()?.toBoolean() ?: false
        val projectFilter = params["project"]?.firstOrNull()
        val updatedAfter = Cursor.parse(
            "updated_after",
            params["updated_after"]?.firstOrNull(),
            "pass back the 'updated_at' of the newest thread your last listing returned.",
        ).getOrElse { return sendBadRequest(it, request, context) }
        val reference = Reference.parse(params["ref"]?.firstOrNull())
            .getOrElse { return sendBadRequest(it, request, context) }
        val callerKey = callerKey(params)
        val referent = reference?.let { ref ->
            val stores = ApplicationManager.getApplication().runReadAction(Computable { admittedStores(projectFilter) })
            when (val resolution = ref.resolveIn(stores.values.flatMap { it.threads.all() })) {
                is Resolution.Found -> resolution.referent
                is Resolution.Ambiguous -> return send(ambiguityJson(resolution, stores), HttpResponseStatus.BAD_REQUEST, request, context)
                Resolution.Unknown -> return sendResolutionError(
                    "no thread or message matches $ref in ${projectFilter?.let { "a project matching '$it'" } ?: "any open project"}.",
                    request, context,
                )
            }
        }

        val threadsJson = JsonArray()
        var markedSeen = 0
        val listedStores = mutableListOf<MarginalisStore>()

        ApplicationManager.getApplication().runReadAction {
            for ((project, store) in admittedStores(projectFilter)) {
                listedStores += store
                val listed = store.threads.query(
                    file = fileFilter,
                    status = statusFilter,
                    intent = intentFilter,
                    awaiting = awaitingFilter,
                    awaitingFor = callerKey,
                    unreadFor = if (unreadOnly) callerKey else null,
                    updatedAfter = updatedAfter,
                ).filter { referent == null || it === referent.thread }.sortedWith(ThreadOrder.byAnchor)
                if (summaryOnly) {
                    listed.forEach { threadsJson.add(summaryJson(project, store, it, callerKey)) }
                } else {
                    for (rendered in renderThreads(project, store, listed, callerKey, referent?.message)) {
                        markedSeen += rendered.newlySeen
                        threadsJson.add(rendered.json)
                    }
                }
            }
        }

        sendJson(
            JsonObject().apply {
                add("threads", threadsJson)
                addProperty("marked_seen", markedSeen)
                listedStores.singleOrNull()?.handBack?.lastAt?.let { addProperty("handed_back_at", it.iso()) }
            },
            request, context,
        )
    }

    private fun admittedStores(projectFilter: String?): Map<Project, MarginalisStore> =
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed && (projectFilter == null || projectMatches(it, projectFilter)) }
            .associateWith { MarginalisStore.getInstance(it) }

    private fun ambiguityJson(ambiguous: Resolution.Ambiguous, stores: Map<Project, MarginalisStore>): JsonObject =
        JsonObject().apply {
            addProperty("error", ambiguous.reason)
            add("candidates", JsonArray().apply { ambiguous.candidates.forEach { add(candidateJson(it, stores)) } })
        }

    private fun candidateJson(candidate: Referent, stores: Map<Project, MarginalisStore>): JsonObject = JsonObject().apply {
        addProperty("ref", candidate.fullReference.toString())
        addProperty("thread_id", candidate.thread.id)
        candidate.message?.let { addProperty("message_id", it.id) }
        stores.entries.firstOrNull { (_, store) -> store.threads.byId(candidate.thread.id) != null }?.let { (project, _) ->
            addProperty("project", project.name)
        }
        candidate.thread.file?.let { addProperty("file", it) }
    }

    private class RenderedThread(val json: JsonObject, val newlySeen: Int)

    private fun renderThreads(
        project: Project,
        store: MarginalisStore,
        threads: List<CommentThread>,
        callerKey: String,
        referenced: Message? = null,
    ): List<RenderedThread> =
        threads.map { markSeenAndRender(project, store, it, callerKey, referenced) }

    private fun markSeenAndRender(
        project: Project,
        store: MarginalisStore,
        thread: CommentThread,
        callerKey: String,
        referenced: Message?,
    ): RenderedThread {
        val messagesJson = JsonArray()
        var newlySeenCount = 0
        for (message in thread.messages) {
            val newlySeen = !message.seenBy(callerKey)
            if (newlySeen) {
                message.markSeenBy(callerKey)
                newlySeenCount++
            }
            messagesJson.add(
                JsonObject().apply {
                    addProperty("message_id", message.id)
                    add("author", authorJson(message.author))
                    addProperty("body", message.body)
                    addProperty("created_at", message.createdAt.iso())
                    add("seen_by", JsonArray().apply { message.seenBy.sorted().forEach(::add) })
                    message.to?.let { addProperty("to", it.wire) }
                    if (message.agrees) addProperty("agrees", true)
                    message.relayed?.let { add("relayed", it.toJson()) }
                    if (newlySeen) addProperty("newly_seen", true)
                    if (message === referenced) addProperty("referenced", true)
                },
            )
        }
        val json = threadHeaderJson(project, store, thread).apply { add("messages", messagesJson) }
        return RenderedThread(json, newlySeenCount)
    }

    private fun summaryJson(project: Project, store: MarginalisStore, thread: CommentThread, callerKey: String): JsonObject {
        val summary = ThreadSummary.of(thread, callerKey)
        return threadHeaderJson(project, store, thread).apply {
            addProperty("messages", summary.messages)
            addProperty("unread", summary.unread)
            summary.lastAuthor?.let { add("last_author", authorJson(it)) }
            summary.awaiting?.let { addProperty("awaiting", it.wireName) }
        }
    }

    private fun threadHeaderJson(project: Project, store: MarginalisStore, thread: CommentThread): JsonObject = JsonObject().apply {
        addProperty("thread_id", thread.id)
        addProperty("project", project.name)
        thread.file?.let { addProperty("file", it) }
        val line = store.syncLine(thread)
        line?.let { addProperty("line", it + 1) }
        currentAnchorText(project, thread, line)?.let { addProperty("anchor_text", it) }
        addProperty("status", thread.status.kind.name.lowercase())
        addProperty("created_at", thread.createdAt.iso())
        addProperty("updated_at", thread.updatedAt.iso())
        thread.segment?.let { seg ->
            add(
                "segment",
                JsonObject().apply {
                    addProperty("exact", seg.exact)
                    if (seg.prefix.isNotEmpty()) addProperty("prefix", seg.prefix)
                    if (seg.suffix.isNotEmpty()) addProperty("suffix", seg.suffix)
                },
            )
        }
        thread.order?.let { addProperty("order", it) }
        thread.walkthrough?.let { addProperty("walkthrough", it) }
        thread.severity?.let { addProperty("severity", it.name.lowercase()) }
        thread.intent?.let { addProperty("intent", it.name.lowercase()) }
        thread.label?.let { addProperty("label", it) }
        thread.resolvedBy?.let { addProperty("resolved_by", it.displayName) }
    }

    // Never block here: execute() runs on a Netty I/O thread, so the request is
    // parked on the project's HandBack and answered later from a pooled thread.
    private fun handleCommentWait(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext) {
        if (request.method() !== HttpMethod.GET) {
            return sendError(HttpResponseStatus.METHOD_NOT_ALLOWED, "comment_wait is a GET", request, context)
        }
        val params = urlDecoder.parameters()
        val since = Cursor.parse(
            "since",
            params["since"]?.firstOrNull(),
            "pass the later of the newest 'updated_at' and the 'handed_back_at' from the sweep or wake that " +
                "started your turn; omit it to wait for the next round.",
        ).getOrElse { return sendBadRequest(it, request, context) }
        val timeout = WaitTimeout.parse(params["timeout"]?.firstOrNull())
            .getOrElse { return sendBadRequest(it, request, context) }
        val projectFilter = params["project"]?.firstOrNull()
        val project = ApplicationManager.getApplication().runReadAction(Computable { resolveProject(projectFilter) })
            ?: return sendResolutionError(
                if (projectFilter == null) {
                    "comment_wait waits on one project: pass 'project' (name or root path) — see open_projects."
                } else {
                    "no open project matches '$projectFilter' — see open_projects."
                },
                request, context,
            )
        val stay = params["stay"]?.firstOrNull()?.toBoolean() ?: false
        val caller = agentAuthor(params)
        val requestHead = DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri(), request.headers().copy())
        val closeFuture = context.channel().closeFuture()

        val waited = MarginalisStore.getInstance(project).handBack.await(since, timeout, caller, stay)
        val cancelOnHangUp = ChannelFutureListener { waited.cancel(false) }
        closeFuture.addListener(cancelOnHangUp)
        waited.whenComplete { _, _ -> closeFuture.removeListener(cancelOnHangUp) }
        waited.whenCompleteAsync(
            { wake, _ ->
                if (waited.isCancelled) return@whenCompleteAsync
                try {
                    sendJson(waitAnswer(project, wake, caller.receiptKey), requestHead, context)
                } catch (e: Exception) {
                    send(
                        JsonObject().apply { addProperty("error", "comment_wait failed: ${e.message}") },
                        HttpResponseStatus.INTERNAL_SERVER_ERROR, requestHead, context,
                    )
                }
            },
            AppExecutorUtil.getAppExecutorService(),
        )
    }

    private fun waitAnswer(project: Project, wake: Wake, callerKey: String): JsonObject =
        when (wake) {
            is Wake.Ending -> notHandedBack(wake.reason)
            is Wake.Delivery ->
                if (project.isDisposed) notHandedBack(Wake.Closing.reason) else deliveryAnswer(project, wake, callerKey)
        }

    private fun notHandedBack(reason: String) = JsonObject().apply {
        addProperty("handed_back", false)
        addProperty("reason", reason)
    }

    private fun deliveryAnswer(project: Project, wake: Wake.Delivery, callerKey: String): JsonObject {
        val store = MarginalisStore.getInstance(project)
        val awaiting = ApplicationManager.getApplication().runReadAction(
            Computable {
                val owed = when (wake) {
                    is Wake.HandedBack -> store.threads.query(awaiting = Turn.AGENT_OWES, awaitingFor = callerKey) +
                        wake.liveThreadIds.mapNotNull(store.threads::byId).filter { LiveThread.hasUnseen(it, callerKey) }
                    is Wake.Live -> wake.threadIds.mapNotNull(store.threads::byId).filter {
                        it.turnFor(callerKey) == Turn.AGENT_OWES || LiveThread.hasUnseen(it, callerKey)
                    }
                }
                renderThreads(project, store, owed.distinct().sortedWith(ThreadOrder.byAnchor), callerKey)
            },
        )
        return JsonObject().apply {
            addProperty("handed_back", true)
            addProperty("reason", wake.reason)
            addProperty("handed_back_at", wake.at.iso())
            add("awaiting", JsonArray().apply { awaiting.forEach { add(it.json) } })
        }
    }

    private fun Instant.iso(): String = DateTimeFormatter.ISO_INSTANT.format(this)

    private fun handleCommentIdentities(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext) {
        if (request.method() !== HttpMethod.GET) {
            return sendError(HttpResponseStatus.METHOD_NOT_ALLOWED, "comment_identities is a GET", request, context)
        }
        val projectFilter = urlDecoder.parameters()["project"]?.firstOrNull()
        val project = ApplicationManager.getApplication().runReadAction(Computable { resolveProject(projectFilter) })
            ?: return sendResolutionError(
                if (projectFilter == null) {
                    "comment_identities lists one project's margin: pass 'project' (name or root path) — see open_projects."
                } else {
                    "no open project matches '$projectFilter' — see open_projects."
                },
                request, context,
            )
        val store = MarginalisStore.getInstance(project)
        val identities = Identities.of(store.threads.all(), Authors.user, store.handBack.waitingAgents)
        sendJson(
            JsonObject().apply {
                addProperty("project", project.name)
                add("identities", JsonArray().apply { identities.forEach { add(identityJson(it)) } })
            },
            request, context,
        )
    }

    private fun identityJson(identity: Identity): JsonObject = JsonObject().apply {
        when (identity) {
            is Identity.User -> {
                addProperty("kind", "user")
                addProperty("name", identity.name)
                addProperty("messages_written", identity.messagesWritten)
            }
            is Identity.Agent -> {
                addProperty("kind", "agent")
                addProperty("name", identity.name)
                addProperty("id", identity.id)
                addProperty("messages_written", identity.messagesWritten)
                addProperty("unread", identity.unread)
                addProperty("waiting", identity.waiting)
            }
        }
    }

    // Navigating with focus is right only because every call is user-solicited.
    private fun handleNavigate(json: JsonObject, request: FullHttpRequest, context: ChannelHandlerContext) {
        val file = json.stringOrNull("file")
            ?: return sendError(HttpResponseStatus.BAD_REQUEST, "missing 'file' (project-relative path)", request, context)
        val anchorText = json.stringOrNull("anchor_text")
        val wireLine = json.intOrNull("line")
        anchorIntentError(json, file, anchorText)?.let {
            return sendError(HttpResponseStatus.BAD_REQUEST, it, request, context)
        }
        val projectFilter = json.stringOrNull("project")

        if (!MarginalisSettings.getInstance().state.navigationEnabled) {
            return sendError(HttpResponseStatus.FORBIDDEN, "navigation is disabled in Marginalis settings", request, context)
        }

        val (project, vFile) = ApplicationManager.getApplication()
            .runReadAction(Computable { resolveFile(file, projectFilter) })
            ?: return sendResolutionError(resolutionFailure(file, projectFilter), request, context)

        var error: String? = null
        var errorStatus = HttpResponseStatus.BAD_REQUEST
        var errorReason: String? = null
        var landedLine = -1
        var adjusted = false

        ApplicationManager.getApplication().invokeAndWait {
            if (wireLine == null) {
                OpenFileDescriptor(project, vFile, 0, 0).navigate(true)
                landedLine = 0
                return@invokeAndWait
            }
            val document = FileDocumentManager.getInstance().getDocument(vFile)
            if (document == null) {
                error = "'$file' has no text document (binary or too large?)"
                return@invokeAndWait
            }
            val placed = when (val outcome = resolveAnchoredLine(document, file, wireLine, anchorText)) {
                is AnchorOutcome.Stale -> {
                    error = outcome.message
                    errorStatus = HttpResponseStatus.CONFLICT
                    errorReason = STALE_ANCHOR
                    return@invokeAndWait
                }
                is AnchorOutcome.Placed -> outcome
            }
            OpenFileDescriptor(project, vFile, placed.line, 0).navigate(true)
            landedLine = placed.line
            adjusted = placed.adjusted
        }

        if (error != null || landedLine < 0) {
            return sendError(errorStatus, error ?: "internal error", request, context, errorReason)
        }
        sendJson(
            JsonObject().apply {
                addProperty("navigated", true)
                addProperty("file", file)
                if (wireLine != null) {
                    addProperty("line", landedLine + 1)
                    addProperty("line_adjusted", adjusted)
                }
            },
            request, context,
        )
    }

    private fun currentAnchorText(project: Project, thread: CommentThread, line: Int?): String? {
        if (line == null) return null
        val vFile = thread.file?.let { project.guessProjectDir()?.findFileByRelativePath(it) }
        val document = vFile?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            ?: return thread.anchorText
        if (line >= document.lineCount) return thread.anchorText
        return lineText(document, line)
    }

    private fun callerKey(params: Map<String, List<String>>): String = agentAuthor(params).receiptKey

    private fun authorJson(author: Author): JsonObject = JsonObject().apply {
        addProperty("kind", if (author is Author.Agent) "agent" else "user")
        addProperty("name", author.displayName)
        (author as? Author.Agent)?.id?.let { addProperty("id", it) }
    }

    private fun resolveFile(relPath: String, projectFilter: String? = null): Pair<Project, VirtualFile>? {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            if (projectFilter != null && !projectMatches(project, projectFilter)) continue
            val base = project.guessProjectDir() ?: continue
            val vFile = base.findFileByRelativePath(relPath) ?: continue
            return project to vFile
        }
        return null
    }

    // Never guess among several open projects: that files the thread in the wrong one.
    private fun resolveProject(projectFilter: String?): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (projectFilter == null) return open.singleOrNull()
        return open.firstOrNull { projectMatches(it, projectFilter) }
    }

    private fun projectMatches(project: Project, filter: String): Boolean {
        if (project.name == filter) return true
        val path = project.guessProjectDir()?.path ?: project.basePath ?: return false
        return path == filter || path.endsWith("/$filter")
    }

    private fun projectResolutionFailure(projectFilter: String?): String =
        if (projectFilter == null) {
            "a thread about the project needs to know which one: pass 'project' (name or root path). " +
                "There is no file here to resolve it by — see open_projects."
        } else {
            "no open project matches '$projectFilter' — see open_projects."
        }

    private fun resolutionFailure(file: String, projectFilter: String?): String =
        if (projectFilter == null) {
            "'$file' not found in any open project (paths are project-relative). " +
                "If several open projects share this layout, pass 'project' — see open_projects."
        } else {
            "'$file' not found in a project matching '$projectFilter' — see open_projects."
        }

    private fun resolutionErrorJson(message: String): JsonObject = JsonObject().apply {
        addProperty("error", message)
        add("open_projects", ApplicationManager.getApplication().runReadAction(Computable { openProjectsJson() }))
    }

    private fun sendResolutionError(message: String, request: FullHttpRequest, context: ChannelHandlerContext) {
        send(resolutionErrorJson(message), HttpResponseStatus.NOT_FOUND, request, context)
    }

    private companion object {
        const val STALE_ANCHOR = "stale_anchor"
        const val NOT_ORPHANED = "not_orphaned"
        const val DELETED_RELAY = "deleted_relay"
        const val RELAYED_ELSEWHERE = "relayed_elsewhere"

        fun errorJson(message: String, reason: String?): JsonObject = JsonObject().apply {
            addProperty("error", message)
            reason?.let { addProperty("reason", it) }
        }

        val BATCH_DEFAULTS = listOf("author_name", "author_id", "project")

        val TEXT_FIELDS = listOf(
            "file" to "the project-relative path, e.g. 'src/App.kt'. Omit it to address the project as a whole",
            "body" to "the note itself",
            "anchor_text" to "the exact text you believe occupies the line",
            "walkthrough" to "a short label, e.g. 'A', grouping the steps of one walk",
            "project" to "a project name or root path, as listed by ping",
            "author_name" to "how you want to be shown in the margin",
            "author_id" to "your stable identity, which read receipts are keyed by",
            "severity" to "exactly 'blocker' or 'nit'",
            "intent" to "exactly 'finding', 'guidance', 'question' or 'fyi'",
            "label" to "a word or two naming the kind of fyi, e.g. 'praise' or 'heads-up'",
            "to" to "one author_id to address (as comment_identities lists it), or 'user'. Omit it to address " +
                "everyone",
        )

        val REPLY_TEXT_FIELDS = TEXT_FIELDS.filter { (field, _) -> field in setOf("body", "author_name", "author_id", "to") }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun sendJson(json: JsonObject, request: HttpRequest, context: ChannelHandlerContext) {
        send(json, HttpResponseStatus.OK, request, context)
    }

    private fun sendError(
        status: HttpResponseStatus,
        message: String,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        reason: String? = null,
    ) {
        send(errorJson(message, reason), status, request, context)
    }

    private fun sendBadRequest(reason: String, request: FullHttpRequest, context: ChannelHandlerContext) {
        sendError(HttpResponseStatus.BAD_REQUEST, reason, request, context)
    }

    private fun send(json: JsonObject, status: HttpResponseStatus, request: HttpRequest, context: ChannelHandlerContext) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        sendResponse(request, context, response)
    }
}
