"""A walkthrough that hops between files the way a real review does: 18 steps over 8 files, the reviewer leaving a
file and coming back to it (the service four times), with a few same-file runs. Unlike `deep_walkthrough`, step order
and file order disagree, so the Guided layouts differ visibly.

`write_files(project)` creates the source files; `steps()` lists (order, file, 1-based line) for every step.
"""
from __future__ import annotations

from pathlib import Path

ORDERS = "acme/backend/shop-orders/src"
MAIN = f"{ORDERS}/main/java/com/acme/orders"
TEST = f"{ORDERS}/test/java/com/acme/orders"
COMMON = "acme/backend/shop-common/src/main/java/com/acme/backend/common"

CONTROLLER = f"{MAIN}/api/OrderController.java"
SERVICE = f"{MAIN}/OrderService.java"
REPOSITORY = f"{MAIN}/OrderRepository.java"
POLICY = f"{COMMON}/discount/LoyaltyDiscountPolicy.java"
ORDER = f"{COMMON}/order/Order.java"
MIGRATION = f"{ORDERS}/main/resources/db/changelog/add-loyalty-discount.sql"
SERVICE_TEST = f"{TEST}/OrderServiceTest.java"
POLICY_TEST = f"{TEST}/discount/LoyaltyDiscountPolicyTest.java"

STEPS: list[tuple[int, str, int]] = [
    (1, CONTROLLER, 12),
    (2, SERVICE, 20),
    (3, SERVICE, 28),
    (4, POLICY, 8),
    (5, POLICY, 22),
    (6, SERVICE, 41),
    (7, REPOSITORY, 15),
    (8, ORDER, 10),
    (9, SERVICE, 55),
    (10, CONTROLLER, 30),
    (11, MIGRATION, 3),
    (12, SERVICE_TEST, 14),
    (13, SERVICE_TEST, 36),
    (14, POLICY, 40),
    (15, POLICY_TEST, 12),
    (16, POLICY_TEST, 30),
    (17, SERVICE_TEST, 58),
    (18, CONTROLLER, 45),
]
RESOLVED = {1}
LABEL = "SHOP-77"
LINES_PER_FILE = 70


def write_files(project: Path) -> None:
    for path in {path for _, path, _ in STEPS}:
        target = project / path
        target.parent.mkdir(parents=True, exist_ok=True)
        name = target.stem.replace("-", "_")
        if target.suffix == ".sql":
            body = [f"-- {name}, line {n}" for n in range(1, LINES_PER_FILE)]
        else:
            body = [f"class {target.stem} {{"] + [f"    int field{n} = {n};" for n in range(2, LINES_PER_FILE)] + ["}"]
        target.write_text("\n".join(body) + "\n", encoding="utf-8")


def steps() -> list[tuple[int, str, int]]:
    return sorted(STEPS)


if __name__ == "__main__":
    assert [order for order, _, _ in steps()] == list(range(1, len(STEPS) + 1))
    print(len(STEPS), "steps over", len({path for _, path, _ in STEPS}), "files,", len(STEPS) - len(RESOLVED), "open")
