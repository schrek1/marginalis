package dev.marginalis.plugin.testing

import com.intellij.ide.ui.IdeUiService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.impl.IdeUiServiceImpl
import com.intellij.testFramework.replaceService
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class FakeGitHub(disposable: Disposable) {

    private sealed interface Answer {
        class Picture(val bytes: ByteArray) : Answer
        class Status(val code: Int) : Answer
        data object Unreachable : Answer
    }

    private val answers = ConcurrentHashMap<String, Answer>()

    val requests: MutableList<String> = CopyOnWriteArrayList()

    init {
        ApplicationManager.getApplication().replaceService(IdeUiService::class.java, Network(), disposable)
    }

    fun serves(url: String, colour: Color) {
        answers[url] = Answer.Picture(png(colour))
    }

    fun servesBytes(url: String, bytes: ByteArray) {
        answers[url] = Answer.Picture(bytes)
    }

    fun answers(url: String, status: Int) {
        answers[url] = Answer.Status(status)
    }

    fun isUnreachable(url: String) {
        answers[url] = Answer.Unreachable
    }

    fun requestsFor(url: String): Int = requests.count { it == url }

    private inner class Network : IdeUiServiceImpl() {
        override fun openHttpConnection(url: String): URLConnection {
            requests += url
            return when (val answer = answers[url] ?: Answer.Unreachable) {
                Answer.Unreachable -> throw IOException("$url is unreachable")
                is Answer.Status -> Response(url, answer.code, ByteArray(0))
                is Answer.Picture -> Response(url, HttpURLConnection.HTTP_OK, answer.bytes)
            }
        }
    }

    private class Response(url: String, private val code: Int, private val body: ByteArray) : HttpURLConnection(URI(url).toURL()) {
        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getResponseMessage(): String = if (code == HTTP_OK) "OK" else "Status $code"

        override fun getInputStream(): InputStream =
            if (code == HTTP_OK) ByteArrayInputStream(body) else throw IOException("Server returned HTTP response code: $code")

        override fun getErrorStream(): InputStream? = null
    }

    companion object {
        fun profileUrl(login: String): String = "https://github.com/$login.png?size=64"

        val cache: Path get() = PathManager.getSystemDir().resolve("marginalis/github-avatars")

        fun png(colour: Color, side: Int = 64): ByteArray {
            val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = colour
            g.fillRect(0, 0, side, side)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }

        fun cached(login: String, colour: Color, age: Duration = Duration.ZERO): Path =
            store(cache.resolve("$login.png"), png(colour), age)

        fun cachedMissing(login: String, age: Duration = Duration.ZERO): Path =
            store(cache.resolve("$login.missing"), ByteArray(0), age)

        private fun store(file: Path, bytes: ByteArray, age: Duration): Path {
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(age)))
            return file
        }

        fun forget(login: String) {
            Files.deleteIfExists(cache.resolve("$login.png"))
            Files.deleteIfExists(cache.resolve("$login.missing"))
        }
    }
}
