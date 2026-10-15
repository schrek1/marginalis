"""A walkthrough shaped like a real review: 33 steps spread over a deep multi-module tree, numbered out of tree
order, 9 of them already resolved (Guided shows 24). Shaped like a real review walked in the margin.

`write_files(project)` creates the source files; `steps()` lists (order, file, 1-based line) for every step.
"""
from __future__ import annotations

from pathlib import Path

BACKEND = "acme/backend"
COMMON_MAIN = f"{BACKEND}/shop-common/src/main/java/com/acme/backend/common"
COMMON_TEST = f"{BACKEND}/shop-common/src/test/java/com/acme/backend/common"
ORDERS_MAIN = f"{BACKEND}/shop-orders/src/main/java/com/acme/orders"
ORDERS_TEST = f"{BACKEND}/shop-orders/src/test/java/com/acme/orders"
BILLING_MAIN = f"{BACKEND}/shop-billing/src/main/java/com/acme/billing"
BILLING_TEST = f"{BACKEND}/shop-billing/src/test/java/com/acme/billing"
SIT = "acme/system-integration-tests/src/test/java/com/acme/systemintegration"

# file -> step numbers in it, in the order they sit in the file
LAYOUT: dict[str, list[int]] = {
    f"{COMMON_MAIN}/customer/settings/api/CustomerSettingsToMapper.java": [16],
    f"{COMMON_MAIN}/customer/settings/CustomerSettingsProvider.java": [8],
    f"{COMMON_MAIN}/customer/settings/HasSortOrder.java": [9],
    f"{COMMON_MAIN}/customer/settings/JdbcCustomerSettingsRepository.java": [14],
    f"{COMMON_MAIN}/catalog/product/Product.java": [15, 1, 2],
    f"{COMMON_TEST}/catalog/product/ProductTest.java": [21],
    f"{COMMON_TEST}/customer/settings/CustomerSettingsRepositoryTest.java": [22],
    f"{ORDERS_MAIN}/OrderSettingsProvider.java": [3, 10, 11, 12, 4],
    f"{ORDERS_TEST}/api/OrderSettingsConfigurationTest.java": [28, 29, 30, 31],
    f"{ORDERS_TEST}/OrderSettingsProviderTest.java": [23, 24, 25, 26, 27, 5],
    f"{BILLING_MAIN}/api/to/converter/InvoiceSettingsToMapper.java": [19, 17],
    f"{BILLING_MAIN}/InvoiceSettingsProvider.java": [13, 6],
    f"{BILLING_TEST}/api/controller/InvoiceSettingsConfigurationTest.java": [33, 18],
    f"{BILLING_TEST}/InvoiceSettingsProviderTest.java": [32, 7],
    f"{SIT}/CustomerProductFixture.java": [20],
}
RESOLVED = {1, 2, 3, 4, 5, 6, 7, 17, 18}
LABEL = "SHOP-42"
LINES_PER_FILE = 60
LINE_STEP = 8


def line_of(index_in_file: int) -> int:
    return 5 + index_in_file * LINE_STEP


def write_files(project: Path) -> None:
    for path in LAYOUT:
        target = project / path
        target.parent.mkdir(parents=True, exist_ok=True)
        name = target.stem
        body = [f"class {name} {{"] + [f"    int {name[0].lower()}{name[1:]}Field{n} = {n};" for n in range(2, LINES_PER_FILE)] + ["}"]
        target.write_text("\n".join(body) + "\n", encoding="utf-8")


def steps() -> list[tuple[int, str, int]]:
    return sorted(
        (order, path, line_of(index))
        for path, orders in LAYOUT.items()
        for index, order in enumerate(orders)
    )


if __name__ == "__main__":
    assert sorted(order for order, _, _ in steps()) == list(range(1, 34))
    print(len(steps()), "steps,", len(steps()) - len(RESOLVED), "open")
