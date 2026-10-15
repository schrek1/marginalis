package dev.marginalis.plugin.testing

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

class WalkthroughFixture(
    val label: String,
    val steps: List<Step>,
    val resolved: Set<Int>,
    private val linesPerFile: Int,
) {
    data class Step(val order: Int, val file: String, val line: Int)

    val files: List<String> get() = steps.map { it.file }.distinct()

    fun lineText(file: String, line: Int): String = lines(file)[line - 1]

    fun writeInto(fixture: CodeInsightTestFixture) {
        files.forEach { fixture.addFileToProject(it, lines(it).joinToString("\n") + "\n") }
    }

    fun postBy(agent: Agent): Map<Int, String> {
        val items = steps.sortedBy { it.order }.map { step ->
            mapOf(
                "file" to step.file,
                "line" to step.line,
                "anchor_text" to lineText(step.file, step.line),
                "order" to step.order,
                "walkthrough" to label,
                "body" to "Step ${step.order}",
            )
        }
        val results = agent.post("comment_add_batch", "items" to items).json.getAsJsonArray("results")
        val ids = steps.sortedBy { it.order }.zip(results).associate { (step, result) -> step.order to result.asJsonObject["thread_id"].asString }
        resolved.forEach { agent.post("comment_resolve", "thread_id" to ids.getValue(it)) }
        return ids
    }

    private fun lines(file: String): List<String> {
        val name = file.substringAfterLast('/').substringBeforeLast('.')
        return if (file.endsWith(".sql")) {
            (1 until linesPerFile).map { "-- ${name.replace('-', '_')}, line $it" }
        } else {
            listOf("class $name {") + (2 until linesPerFile).map { "    int field$it = $it;" } + "}"
        }
    }

    companion object {
        private const val ORDERS = "acme/backend/shop-orders/src"
        private const val ORDERS_MAIN = "$ORDERS/main/java/com/acme/orders"
        private const val ORDERS_TEST = "$ORDERS/test/java/com/acme/orders"
        private const val COMMON_MAIN = "acme/backend/shop-common/src/main/java/com/acme/backend/common"
        private const val COMMON_TEST = "acme/backend/shop-common/src/test/java/com/acme/backend/common"
        private const val BILLING_MAIN = "acme/backend/shop-billing/src/main/java/com/acme/billing"
        private const val BILLING_TEST = "acme/backend/shop-billing/src/test/java/com/acme/billing"
        private const val SYSTEM_TESTS = "acme/system-integration-tests/src/test/java/com/acme/systemintegration"

        fun interleaved(): WalkthroughFixture {
            val controller = "$ORDERS_MAIN/api/OrderController.java"
            val service = "$ORDERS_MAIN/OrderService.java"
            val repository = "$ORDERS_MAIN/OrderRepository.java"
            val policy = "$COMMON_MAIN/discount/LoyaltyDiscountPolicy.java"
            val order = "$COMMON_MAIN/order/Order.java"
            val migration = "$ORDERS/main/resources/db/changelog/add-loyalty-discount.sql"
            val serviceTest = "$ORDERS_TEST/OrderServiceTest.java"
            val policyTest = "$ORDERS_TEST/discount/LoyaltyDiscountPolicyTest.java"
            val steps = listOf(
                controller to 12, service to 20, service to 28, policy to 8, policy to 22, service to 41,
                repository to 15, order to 10, service to 55, controller to 30, migration to 3, serviceTest to 14,
                serviceTest to 36, policy to 40, policyTest to 12, policyTest to 30, serviceTest to 58, controller to 45,
            ).mapIndexed { index, (file, line) -> Step(index + 1, file, line) }
            return WalkthroughFixture("SHOP-77", steps, resolved = setOf(1), linesPerFile = 70)
        }

        fun deep(): WalkthroughFixture {
            val layout = mapOf(
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
                "$SYSTEM_TESTS/CustomerProductFixture.java" to listOf(20),
            )
            val steps = layout.flatMap { (file, orders) -> orders.mapIndexed { index, order -> Step(order, file, 5 + index * 8) } }
            return WalkthroughFixture("SHOP-42", steps, resolved = setOf(1, 2, 3, 4, 5, 6, 7, 17, 18), linesPerFile = 60)
        }
    }
}
