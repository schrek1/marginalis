package dev.marginalis.plugin.store

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.marginalis.core.ThreadsCodec
import java.nio.file.Files
import java.nio.file.Path

object MarginalisPersistence {
    private val log = logger<MarginalisPersistence>()

    private fun storageFile(project: Project): Path? =
        project.guessProjectDir()?.toNioPathOrNull()?.resolve(".idea")?.resolve("marginalis.json")

    @Synchronized
    fun save(project: Project, document: ThreadsCodec.Document) {
        val path = storageFile(project) ?: return
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, ThreadsCodec.encode(document.threads, document.handedBackAt, document.deletedRelays))
        } catch (e: Exception) {
            log.warn("Failed to save margin threads to $path", e)
        }
    }

    fun load(project: Project): ThreadsCodec.Document {
        val path = storageFile(project) ?: return ThreadsCodec.Document.EMPTY
        if (!Files.exists(path)) return ThreadsCodec.Document.EMPTY
        return try {
            ThreadsCodec.decodeDocument(Files.readString(path))
        } catch (e: Exception) {
            log.warn("Failed to load margin threads from $path — starting empty", e)
            ThreadsCodec.Document.EMPTY
        }
    }
}
