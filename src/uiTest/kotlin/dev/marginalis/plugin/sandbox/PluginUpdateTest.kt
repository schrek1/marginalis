package dev.marginalis.plugin.sandbox

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PluginUpdateTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the next version is the installed plugin under a higher version, every other file as it was`() {
        // GIVEN - an installed plugin: its own jar with the descriptor, and a library jar
        val plugin = dir.resolve("marginalis")
        val descriptor = """<idea-plugin><id>dev.marginalis.plugin</id><version>0.2.3</version></idea-plugin>"""
        write(plugin.resolve("lib/marginalis-0.2.3.jar"), jar("META-INF/plugin.xml" to descriptor, "dev/A.class" to "code"))
        val library = jar("dev/Core.class" to "core")
        write(plugin.resolve("lib/core.jar"), library)

        // WHEN - the next build is made from it
        val zip = dir.resolve("update/marginalis.zip")
        val version = PluginUpdate.nextVersion(plugin, zip)

        // THEN - the zip holds the same plugin folder, with the descriptor's version raised
        version shouldBe "0.2.3.1"
        val entries = entries(Files.readAllBytes(zip))
        entries.keys.toList() shouldContainExactly listOf("marginalis/lib/core.jar", "marginalis/lib/marginalis-0.2.3.jar")
        entries.getValue("marginalis/lib/core.jar").toList() shouldBe library.toList()
        val pluginJar = entries(entries.getValue("marginalis/lib/marginalis-0.2.3.jar")).mapValues { (_, bytes) -> bytes.toString(Charsets.UTF_8) }
        pluginJar shouldBe mapOf(
            "META-INF/plugin.xml" to """<idea-plugin><id>dev.marginalis.plugin</id><version>0.2.3.1</version></idea-plugin>""",
            "dev/A.class" to "code",
        )
    }

    private fun write(file: Path, bytes: ByteArray) {
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
    }

    private fun jar(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun entries(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.associate { entry -> entry.name to input.readAllBytes() }
    }
}
