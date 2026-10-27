package dev.marginalis.plugin.avatars

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.messages.Topic
import dev.marginalis.core.AvatarSource
import dev.marginalis.core.Face
import dev.marginalis.core.GitHubAvatarCachePolicy
import dev.marginalis.core.GitHubAvatarCachePolicy.FailedFetch
import dev.marginalis.core.sources
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.settings.Pictures
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.io.path.extension

fun interface AvatarsListener {
    fun avatarsChanged()

    companion object {
        val TOPIC: Topic<AvatarsListener> = Topic(AvatarsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

@Service(Service.Level.APP)
class AvatarImages : Disposable {

    private class Loaded(val image: BufferedImage?, val retryAt: Instant? = null) {
        private val scaled = ConcurrentHashMap<Int, BufferedImage>()

        fun isDue(now: Instant): Boolean = retryAt != null && !now.isBefore(retryAt)

        fun at(side: Int): BufferedImage? = image?.let { image -> scaled.computeIfAbsent(side, image::scaledTo) }
    }

    private class Request(val key: String, val fetch: () -> Loaded)

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val loading: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loader = AppExecutorUtil.createBoundedApplicationPoolExecutor("Marginalis avatars", 2)
    private val policy = GitHubAvatarCachePolicy()
    @Volatile private var disposed = false

    fun image(face: Face, side: Int): BufferedImage? {
        for (source in face.sources(MarginalisSettings.getInstance().state.showGithubAvatars)) {
            val request = requestFor(source) ?: return null
            val known = loaded[request.key]
            if (known == null) {
                load(request)
                return null
            }
            if (known.isDue(Instant.now())) load(request)
            known.at(side)?.let { return it }
        }
        return null
    }

    private fun requestFor(source: AvatarSource): Request? = when (source) {
        is AvatarSource.Picture -> Request("picture:${source.key}") { Loaded(picture(source.key)) }
        is AvatarSource.GitHub -> Request("github:${source.cacheName}") { github(source) }
        AvatarSource.Monogram -> null
    }

    private fun load(request: Request) {
        val key = request.key
        if (disposed || !loading.add(key)) return
        loader.execute {
            loaded[key] = try {
                request.fetch()
            } catch (e: Exception) {
                LOG.info("Avatar for $key is unavailable for now: $e")
                Loaded(loaded[key]?.image, policy.retryAt(Instant.now()))
            }
            loading.remove(key)
            if (!disposed) announceArrival()
        }
    }

    private fun announceArrival() {
        val application = ApplicationManager.getApplication()
        application.invokeLater(
            { application.messageBus.syncPublisher(AvatarsListener.TOPIC).avatarsChanged() },
            ModalityState.any(),
            { disposed },
        )
    }

    private fun picture(key: String): BufferedImage? = Pictures.stored(key)?.let { ImageIO.read(it.toFile()) }

    private fun github(source: AvatarSource.GitHub): Loaded {
        val name = fileSafe(source.cacheName)
        val png = GITHUB_CACHE.resolve("$name.$PNG")
        val missing = GITHUB_CACHE.resolve("$name.missing")
        val stored = listOf(png, missing).filter(Files::isRegularFile).maxByOrNull(::modifiedAt)
        if (stored != null && !policy.needsFetch(modifiedAt(stored), Instant.now())) return remembered(stored)
        val bytes = try {
            HttpRequests.request(source.url)
                .productNameAsUserAgent()
                .connectTimeout(CONNECT_TIMEOUT_MILLIS)
                .readTimeout(READ_TIMEOUT_MILLIS)
                .readBytes(null)
        } catch (e: IOException) {
            val status = (e as? HttpRequests.HttpStatusException)?.statusCode
            return when (policy.afterFailure(status, staleKept = stored == png)) {
                FailedFetch.MARK_MISSING -> {
                    Files.createDirectories(GITHUB_CACHE)
                    Files.deleteIfExists(png)
                    Files.write(missing, ByteArray(0))
                    remembered(missing)
                }
                FailedFetch.USE_STALE -> Loaded(ImageIO.read(png.toFile()), policy.retryAt(Instant.now()))
                FailedFetch.RETRY_LATER -> throw e
            }
        }
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("${source.url} is not a picture")
        Files.createDirectories(GITHUB_CACHE)
        val partial = Files.createTempFile(GITHUB_CACHE, name, ".part")
        try {
            Files.write(partial, bytes)
            Files.move(partial, png, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(partial)
        }
        Files.deleteIfExists(missing)
        return Loaded(image)
    }

    private fun remembered(stored: Path): Loaded =
        Loaded(if (stored.extension == PNG) ImageIO.read(stored.toFile()) else null)

    override fun dispose() {
        disposed = true
        loader.shutdownNow()
    }

    private fun modifiedAt(file: Path): Instant = Files.getLastModifiedTime(file).toInstant()

    private fun fileSafe(name: String): String = name.replace(UNSAFE_FILE_CHARS, "_")

    companion object {
        private val LOG = logger<AvatarImages>()
        private val GITHUB_CACHE: Path = PathManager.getSystemDir().resolve("marginalis/github-avatars")
        private val UNSAFE_FILE_CHARS = Regex("[^a-z0-9._\\[\\]-]")
        private const val PNG = "png"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val READ_TIMEOUT_MILLIS = 10_000

        /** Null while the plugin unloads: the platform hands out no light service of a class loader on its way out. */
        fun getInstance(): AvatarImages? = ApplicationManager.getApplication().getService(AvatarImages::class.java)
    }
}
