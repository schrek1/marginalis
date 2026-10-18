package dev.marginalis.plugin.sandbox

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ShardMapTest {

    private val map = ShardMap(
        shards = 2,
        scenarios = mapOf("Long" to ShardMap.Placement(1, 300.0), "Short" to ShardMap.Placement(2, 120.0)),
    )

    @Test
    fun `a mapped scenario runs on its own shard`() {
        map.shardOf("Long") shouldBe 1
    }

    @Test
    fun `a scenario missing from the map runs on the shard with the least mapped time`() {
        map.shardOf("New") shouldBe 2
    }

    @Test
    fun `the harness's own tests run on the first shard only`() {
        listOf(map.runsOn(1, scenario = null), map.runsOn(2, scenario = null)) shouldBe listOf(true, false)
    }

    @Test
    fun `without a measured map the scenarios split by name, half of them on each shard`() {
        // GIVEN - a branch that has no shard map yet
        val scenarios = listOf("Delta", "Alpha", "Charlie", "Bravo", "Echo")

        // WHEN - the scenarios are split by name alone
        val byName = ShardMap.byName(scenarios, shards = 2)

        // THEN - sorted by name, they alternate between the shards
        scenarios.associateWith(byName::shardOf) shouldBe
            mapOf("Alpha" to 1, "Bravo" to 2, "Charlie" to 1, "Delta" to 2, "Echo" to 1)
    }

    @Test
    fun `the scenario classes are found on the classpath`() {
        ShardMap.scenarioClasses() shouldContain "ApiReplyRerendersTest"
    }

    @Test
    fun `a shard asked for in another count than the map's is refused`() {
        val refusal = shouldThrow<IllegalArgumentException> { map.shardIndex("2/3") }

        refusal.message shouldContain "sandbox/shards.json splits the scenarios into 2 shards, not 3"
    }

    @Test
    fun `the map is read from the file the rebuild script writes`(@TempDir dir: Path) {
        // GIVEN - the file as scripts/shard_map.py writes it
        val file = dir.resolve("shards.json")
        file.writeText(
            """
            {
              "shards": 2,
              "scenarios": {
                "Long": {"shard": 1, "seconds": 300.0},
                "Short": {"shard": 2, "seconds": 120.0}
              }
            }
            """.trimIndent(),
        )

        // WHEN - the harness reads it
        val read = ShardMap.read(file)

        // THEN - it holds every scenario's shard and duration
        read shouldBe map
    }
}
