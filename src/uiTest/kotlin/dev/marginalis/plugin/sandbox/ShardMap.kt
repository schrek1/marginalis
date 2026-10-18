package dev.marginalis.plugin.sandbox

import com.google.gson.JsonParser
import org.junit.platform.engine.FilterResult
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.PostDiscoveryFilter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * `sandbox/shards.json`: which of CI's e2e shards runs each scenario class (by simple name), and how long it takes.
 * CI's e2e-plan writes it in every run (`scripts/shard_map.py`); a scenario missing from it still runs.
 */
data class ShardMap(val shards: Int, val scenarios: Map<String, Placement>) {

    data class Placement(val shard: Int, val seconds: Double)

    /** A scenario the map does not know yet joins the shard with the least mapped time, the first on a tie. */
    fun shardOf(scenario: String): Int = scenarios[scenario]?.shard
        ?: (1..shards).minBy { shard -> scenarios.values.filter { it.shard == shard }.sumOf { it.seconds } }

    /** [scenario] is null for the harness's own tests, which take seconds and run on the first shard. */
    fun runsOn(shard: Int, scenario: String?): Boolean = if (scenario == null) shard == 1 else shardOf(scenario) == shard

    /** The shard index of an `<index>/<count>` spec, whose count must be the map's. */
    fun shardIndex(spec: String): Int {
        val (index, count) = spec.split('/').map(String::toInt)
        require(count == shards) { "sandbox/shards.json splits the scenarios into $shards shards, not $count: $spec" }
        require(index in 1..count) { "a shard is <index>/<count>, the index from 1: $spec" }
        return index
    }

    companion object {
        val file: Path get() = Sandbox.harness.resolve("shards.json")

        fun read(file: Path): ShardMap {
            val map = JsonParser.parseString(file.readText()).asJsonObject
            val scenarios = map.getAsJsonObject("scenarios")
            return ShardMap(
                shards = map.get("shards").asInt,
                scenarios = scenarios.keySet().associateWith { name ->
                    scenarios.getAsJsonObject(name).let { Placement(it.get("shard").asInt, it.get("seconds").asDouble) }
                },
            )
        }

        fun readIfPresent(): ShardMap? = file.takeIf { it.exists() }?.let(::read)

        /** The split without measured durations (a branch with no map yet): sorted by name, alternating. */
        fun byName(scenarios: List<String>, shards: Int): ShardMap = ShardMap(
            shards,
            scenarios.sorted().withIndex().associate { (position, name) -> name to Placement(position % shards + 1, 0.0) },
        )

        /** The simple names of the scenario classes, from the compiled scenarios package. */
        fun scenarioClasses(): List<String> =
            SandboxScenario::class.java.classLoader.getResources(SCENARIOS.replace('.', '/')).toList()
                .filter { it.protocol == "file" }
                .flatMap { packageDir -> Files.list(Path.of(packageDir.toURI())).use { files -> files.map { it.fileName.toString() }.toList() } }
                .filter { it.endsWith(".class") }
                .map { it.removeSuffix(".class") }
                .filter(::isScenarioName)
                .distinct()
                .sorted()

        /** A scenario class by its name: a top-level class of the scenarios package, not a Kotlin file facade. */
        fun scenarioOf(className: String): String? = className.removePrefix("$SCENARIOS.").takeIf { simpleName ->
            className.startsWith("$SCENARIOS.") && '.' !in simpleName && isScenarioName(simpleName)
        }

        private fun isScenarioName(simpleName: String) = '$' !in simpleName && !simpleName.endsWith("Kt")

        private const val SCENARIOS = "dev.marginalis.plugin.sandbox.scenarios"
    }
}

/**
 * Keeps the scenario classes of one CI shard when `marginalis.sandbox.shard` is set (`-Pmarginalis.sandbox.shard=1/2`).
 * A scenario missing from the map is noted in `<runtime>/shard-map-notes.txt`, which CI turns into a warning.
 */
class ShardFilter : PostDiscoveryFilter {
    private val spec: String? = System.getProperty("marginalis.sandbox.shard")
    private val measured: Boolean = spec != null && ShardMap.file.exists()
    private val map: ShardMap? = spec?.let {
        if (measured) ShardMap.read(ShardMap.file) else ShardMap.byName(ShardMap.scenarioClasses(), it.substringAfter('/').toInt())
    }
    private val shard: Int? = spec?.let { map!!.shardIndex(it) }
    // Lazy: the same classpath runs the IDE Starter tests, which never touch the sandbox.
    private val notes: Path by lazy { Sandbox.runtime.resolve("shard-map-notes.txt") }

    init {
        if (spec != null) {
            notes.deleteIfExists()
            if (!measured) note("no shard map (e2e-plan found no scenario durations); the scenarios are split by name, half on each shard.")
        }
    }

    private val noted = mutableSetOf<String>()

    // The launcher asks about the leaves, the test methods; a class left without methods is dropped with them.
    override fun apply(descriptor: TestDescriptor): FilterResult {
        if (map == null || shard == null) return FilterResult.included("no shard asked for")
        val className = testClassName(descriptor) ?: return FilterResult.included("not in a test class")
        val scenario = ShardMap.scenarioOf(className)
        if (!map.runsOn(shard, scenario)) return FilterResult.excluded("runs on another shard")
        if (scenario != null && scenario !in map.scenarios && noted.add(scenario)) {
            note("$scenario is not in the shard map yet (no green run on schreko_stable measured it); it ran on shard $shard.")
        }
        return FilterResult.included("runs on shard $shard")
    }

    private fun testClassName(descriptor: TestDescriptor): String? = when (val source = descriptor.source.orElse(null)) {
        is ClassSource -> source.className
        is MethodSource -> source.className
        else -> descriptor.parent.orElse(null)?.let(::testClassName)
    }

    private fun note(text: String) {
        println("WARN  $text")
        Files.createDirectories(notes.parent)
        Files.writeString(notes, "$text\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
