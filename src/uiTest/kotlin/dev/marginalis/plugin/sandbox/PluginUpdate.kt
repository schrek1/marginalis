package dev.marginalis.plugin.sandbox

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * The next build of an installed plugin, as Update downloads it: the same code under a higher version. The plugin's
 * folder becomes a distribution zip whose `META-INF/plugin.xml` says `<version>.1`.
 */
object PluginUpdate {

    private val VERSION = Regex("<version>([^<]+)</version>")

    /** Writes the zip of [plugin] (an unpacked plugin folder) to [target]; returns the version it carries. */
    fun nextVersion(plugin: Path, target: Path): String {
        var nextVersion: String? = null
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zip ->
            Files.walk(plugin).use { paths ->
                for (file in paths.filter { it.isRegularFile() }.sorted().toList()) {
                    val entry = "${plugin.name}/${file.relativeTo(plugin).toString().replace('\\', '/')}"
                    val bytes = Files.readAllBytes(file)
                    val descriptor = if (file.name.endsWith(".jar")) withNextVersion(bytes) else null
                    if (descriptor != null) nextVersion = descriptor.second
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(descriptor?.first ?: bytes)
                    zip.closeEntry()
                }
            }
        }
        return nextVersion ?: error("no jar under $plugin holds a META-INF/plugin.xml with a <version>")
    }

    /** The jar with its plugin.xml's version raised, and that version; null for a jar without a plugin.xml. */
    private fun withNextVersion(jar: ByteArray): Pair<ByteArray, String>? {
        var nextVersion: String? = null
        val patched = java.io.ByteArrayOutputStream()
        ZipOutputStream(patched).use { out ->
            ZipInputStream(jar.inputStream()).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    var bytes = input.readAllBytes()
                    if (entry.name == "META-INF/plugin.xml") {
                        val xml = bytes.toString(Charsets.UTF_8)
                        val version = VERSION.find(xml) ?: error("META-INF/plugin.xml has no <version>")
                        nextVersion = "${version.groupValues[1]}.1"
                        bytes = xml.replaceRange(version.range, "<version>$nextVersion</version>").toByteArray(Charsets.UTF_8)
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return nextVersion?.let { version -> patched.toByteArray() to version }
    }
}
