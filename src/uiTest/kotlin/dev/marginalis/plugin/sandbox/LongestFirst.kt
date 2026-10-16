package dev.marginalis.plugin.sandbox

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * How long each scenario class took in its last run on this machine, in `<runtime>/durations-kotlin.json`; a class
 * that has not run yet counts as 3 minutes.
 */
object Durations {
    private val file = Sandbox.runtime.resolve("durations-kotlin.json")
    val unknown: Duration = Duration.ofMinutes(3)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Synchronized
    fun all(): Map<String, Duration> {
        if (!file.exists()) return emptyMap()
        val stored = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull() ?: return emptyMap()
        return stored.keySet().associateWith { Duration.ofMillis((stored.get(it).asDouble * 1000).toLong()) }
    }

    /** Scenarios end at the same time; the file is rewritten whole under one lock, and replaced atomically. */
    @Synchronized
    fun record(className: String, took: Duration) {
        val seconds = (all() + (className to took)).mapValues { (_, duration) -> duration.toMillis() / 1000.0 }.toSortedMap()
        Files.createDirectories(file.parent)
        val written = file.resolveSibling("${file.fileName}.tmp")
        written.writeText(gson.toJson(seconds))
        Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Starts the longest scenarios first, as `run_all.py --jobs` does, so the run does not end on one long straggler. */
class LongestFirst : ClassOrderer {
    override fun orderClasses(context: ClassOrdererContext) {
        val durations = Durations.all()
        context.classDescriptors.sortWith(
            compareByDescending<ClassDescriptor> { durations[it.testClass.name] ?: Durations.unknown }
                .thenBy { it.testClass.name },
        )
    }
}
