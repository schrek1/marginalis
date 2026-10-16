package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A review diff holds the review's copy of a file, so a thread's local line is found again in that text by its anchor. */
class ReviewDiffAnchoringTest {

    private val targetBefore = listOf(
        "def parse(text: str) -> list[str]:",
        "    return text.split(\",\")",
        "",
        "",
        "def total(values: list[int]) -> int:",
        "    return sum(values)",
        "",
        "",
        "def legacy_helper() -> None:",
        "    pass",
    )

    // Three lines on top move every surviving line down by three; legacy_helper is gone.
    private val targetAfter = listOf("import csv", "", "") + targetBefore.take(6)

    private val greetingBefore = listOf(
        "def greet(name: str) -> str:",
        "    return f\"Hello, {name}!\"",
        "",
        "",
        "def farewell(name: str) -> str:",
        "    return f\"Goodbye, {name}.\"",
        "",
        "",
        "if __name__ == \"__main__\":",
        "    print(greet(\"sandbox\"))",
        "    print(farewell(\"sandbox\"))",
    )

    // A new greeting, and a docstring that moves farewell's body down by one.
    private val greetingAfter = greetingBefore.take(1) + "    return f\"Hi, {name}!\"" + greetingBefore.subList(2, 5) +
        "    \"\"\"Says goodbye.\"\"\"" + greetingBefore.drop(5)

    @Test
    fun `lines kept by the review land where the review moved them`() {
        assertEquals(listOf(4, 8), listOf(1, 5).map { line -> lineInReview(targetBefore, targetAfter, line) })
    }

    @Test
    fun `a line one docstring further down is still found`() {
        assertEquals(6, lineInReview(greetingBefore, greetingAfter, 5))
    }

    @Test
    fun `a line the review removed has no place in the review's text`() {
        assertNull(lineInReview(targetBefore, targetAfter, 8))
    }

    private fun lineInReview(before: List<String>, after: List<String>, localLine: Int): Int? =
        AnchorPolicy.findAnchor(
            lineCount = after.size,
            lineTextAt = { after[it] },
            nearLine = localLine,
            anchorText = before[localLine],
        )?.line
}
