package dev.marginalis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewDiffFilesTest {

    private val root = "/work/shop-api"
    private val diff = listOf(
        "/work/shop-api/src/Cart.kt",
        "/work/shop-api/src/Order.kt",
        "/work/shop-api/README.md",
    )

    @Test
    fun `a step in another file of the diff switches to that file`() {
        assertEquals(1, ReviewDiffFiles.switchTarget("src/Order.kt", root, diff, shownIndex = 0))
    }

    @Test
    fun `a step in a file the diff does not show has nothing to switch to`() {
        assertNull(ReviewDiffFiles.switchTarget("src/Invoice.kt", root, diff, shownIndex = 0))
    }

    @Test
    fun `a step in the file the diff already shows is not a switch`() {
        assertNull(ReviewDiffFiles.switchTarget("src/Cart.kt", root, diff, shownIndex = 0))
    }

    @Test
    fun `a diff with no file shown yet switches to the step's file`() {
        assertEquals(0, ReviewDiffFiles.switchTarget("src/Cart.kt", root, diff, shownIndex = -1))
    }

    @Test
    fun `a same-named file outside the project root is not the step's file`() {
        val other = listOf("/work/shop-web/src/Order.kt")

        assertNull(ReviewDiffFiles.switchTarget("src/Order.kt", root, other, shownIndex = -1))
    }

    @Test
    fun `a file the diff cannot name is never a target, and the files after it keep their place`() {
        val unnamed = listOf("/work/shop-api/src/Cart.kt", null, "/work/shop-api/src/Order.kt")

        assertEquals(2, ReviewDiffFiles.switchTarget("src/Order.kt", root, unnamed, shownIndex = 0))
        assertNull(ReviewDiffFiles.switchTarget("src/Invoice.kt", root, unnamed, shownIndex = 0))
    }

    @Test
    fun `the index of a step's file is found even when the diff already shows it`() {
        assertEquals(0, ReviewDiffFiles.indexOf("src/Cart.kt", root, diff))
        assertEquals(1, ReviewDiffFiles.indexOf("src/Order.kt", root, diff))
        assertNull(ReviewDiffFiles.indexOf("src/Invoice.kt", root, diff))
    }
}
