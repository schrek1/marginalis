package dev.marginalis.plugin.sandbox.fixtures

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.Sandbox
import dev.marginalis.plugin.sandbox.SandboxUi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/** One walkthrough step: its number and where it is anchored (`line` 1-based, as agents send it). */
data class Step(val order: Int, val file: String, val line: Int)

/** A walkthrough over source files the fixture writes into the sandbox project itself. */
interface WalkthroughFixture {
    val label: String
    val resolved: Set<Int>
    val steps: List<Step>

    fun writeFiles(project: Path)
}

/**
 * Writes the fixture's files into the sandbox project, posts every step as the agent and resolves the fixture's
 * resolved ones.
 *
 * @return thread id per step number
 */
fun postWalkthrough(fixture: WalkthroughFixture): Map<Int, String> {
    fixture.writeFiles(Sandbox.project)
    SandboxUi.refreshProjectFiles()
    Thread.sleep(2_000)
    val ids = fixture.steps.associate { step ->
        step.order to Agent.agentAddsStep(step.file, step.line, "Step ${step.order}: review note", step.order, fixture.label)
    }
    fixture.resolved.forEach { order -> Agent.resolveThread(ids.getValue(order)) }
    return ids
}

private fun writeLines(target: Path, lines: List<String>) {
    Files.createDirectories(target.parent)
    target.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
}

/**
 * A walkthrough shaped like a real review: 33 steps spread over a deep multi-module tree, numbered out of tree order,
 * 9 of them already resolved (Guided shows 24); a port of `sandbox/fixtures/deep_walkthrough.py`.
 */
object DeepWalkthrough : WalkthroughFixture {
    private const val BACKEND = "acme/backend"
    private const val COMMON_MAIN = "$BACKEND/shop-common/src/main/java/com/acme/backend/common"
    private const val COMMON_TEST = "$BACKEND/shop-common/src/test/java/com/acme/backend/common"
    private const val ORDERS_MAIN = "$BACKEND/shop-orders/src/main/java/com/acme/orders"
    private const val ORDERS_TEST = "$BACKEND/shop-orders/src/test/java/com/acme/orders"
    private const val BILLING_MAIN = "$BACKEND/shop-billing/src/main/java/com/acme/billing"
    private const val BILLING_TEST = "$BACKEND/shop-billing/src/test/java/com/acme/billing"
    private const val SIT = "acme/system-integration-tests/src/test/java/com/acme/systemintegration"
    private const val LINES_PER_FILE = 60
    private const val LINE_STEP = 8

    /** File to the step numbers in it, in the order they sit in the file. */
    private val layout: Map<String, List<Int>> = linkedMapOf(
        "$COMMON_MAIN/customer/settings/api/CustomerSettingsToMapper.java" to listOf(16),
        "$COMMON_MAIN/customer/settings/CustomerSettingsProvider.java" to listOf(8),
        "$COMMON_MAIN/customer/settings/HasSortOrder.java" to listOf(9),
        "$COMMON_MAIN/customer/settings/JdbcCustomerSettingsRepository.java" to listOf(14),
        "$COMMON_MAIN/catalog/product/Product.java" to listOf(15, 1, 2),
        "$COMMON_TEST/catalog/product/ProductTest.java" to listOf(21),
        "$COMMON_TEST/customer/settings/CustomerSettingsRepositoryTest.java" to listOf(22),
        "$ORDERS_MAIN/OrderSettingsProvider.java" to listOf(3, 10, 11, 12, 4),
        "$ORDERS_TEST/api/OrderSettingsConfigurationTest.java" to listOf(28, 29, 30, 31),
        "$ORDERS_TEST/OrderSettingsProviderTest.java" to listOf(23, 24, 25, 26, 27, 5),
        "$BILLING_MAIN/api/to/converter/InvoiceSettingsToMapper.java" to listOf(19, 17),
        "$BILLING_MAIN/InvoiceSettingsProvider.java" to listOf(13, 6),
        "$BILLING_TEST/api/controller/InvoiceSettingsConfigurationTest.java" to listOf(33, 18),
        "$BILLING_TEST/InvoiceSettingsProviderTest.java" to listOf(32, 7),
        "$SIT/CustomerProductFixture.java" to listOf(20),
    )

    override val label = "SHOP-42"
    override val resolved = setOf(1, 2, 3, 4, 5, 6, 7, 17, 18)
    override val steps: List<Step> = layout
        .flatMap { (file, orders) -> orders.mapIndexed { indexInFile, order -> Step(order, file, 5 + indexInFile * LINE_STEP) } }
        .sortedBy { it.order }

    override fun writeFiles(project: Path) {
        for (file in layout.keys) {
            val target = project.resolve(file)
            val name = target.nameWithoutExtension
            val field = name.replaceFirstChar { it.lowercase() }
            writeLines(target, listOf("class $name {") + (2 until LINES_PER_FILE).map { n -> "    int ${field}Field$n = $n;" } + "}")
        }
    }
}

/**
 * A walkthrough that hops between files the way a real review does: 18 steps over 8 files, the reviewer leaving a file
 * and coming back to it (the service four times), with a few same-file runs; a port of
 * `sandbox/fixtures/interleaved_walkthrough.py`. Step order and file order disagree, so the Guided layouts differ.
 */
object InterleavedWalkthrough : WalkthroughFixture {
    private const val ORDERS = "acme/backend/shop-orders/src"
    private const val MAIN = "$ORDERS/main/java/com/acme/orders"
    private const val TEST = "$ORDERS/test/java/com/acme/orders"
    private const val COMMON = "acme/backend/shop-common/src/main/java/com/acme/backend/common"
    private const val LINES_PER_FILE = 70

    const val CONTROLLER = "$MAIN/api/OrderController.java"
    const val SERVICE = "$MAIN/OrderService.java"
    const val SERVICE_TEST = "$TEST/OrderServiceTest.java"
    private const val REPOSITORY = "$MAIN/OrderRepository.java"
    private const val POLICY = "$COMMON/discount/LoyaltyDiscountPolicy.java"
    private const val ORDER = "$COMMON/order/Order.java"
    private const val MIGRATION = "$ORDERS/main/resources/db/changelog/add-loyalty-discount.sql"
    private const val POLICY_TEST = "$TEST/discount/LoyaltyDiscountPolicyTest.java"

    override val label = "SHOP-77"
    override val resolved = setOf(1)
    override val steps = listOf(
        Step(1, CONTROLLER, 12),
        Step(2, SERVICE, 20),
        Step(3, SERVICE, 28),
        Step(4, POLICY, 8),
        Step(5, POLICY, 22),
        Step(6, SERVICE, 41),
        Step(7, REPOSITORY, 15),
        Step(8, ORDER, 10),
        Step(9, SERVICE, 55),
        Step(10, CONTROLLER, 30),
        Step(11, MIGRATION, 3),
        Step(12, SERVICE_TEST, 14),
        Step(13, SERVICE_TEST, 36),
        Step(14, POLICY, 40),
        Step(15, POLICY_TEST, 12),
        Step(16, POLICY_TEST, 30),
        Step(17, SERVICE_TEST, 58),
        Step(18, CONTROLLER, 45),
    )

    override fun writeFiles(project: Path) {
        for (file in steps.map { it.file }.toSet()) {
            val target = project.resolve(file)
            val name = target.nameWithoutExtension
            val lines = if (file.endsWith(".sql")) {
                (1 until LINES_PER_FILE).map { n -> "-- ${name.replace('-', '_')}, line $n" }
            } else {
                listOf("class $name {") + (2 until LINES_PER_FILE).map { n -> "    int field$n = $n;" } + "}"
            }
            writeLines(target, lines)
        }
    }
}
