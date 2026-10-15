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
    fun `Windows separators and a trailing slash on the root still match`() {
        val windowsDiff = listOf("D:\\work\\shop-api\\src\\Cart.kt", "D:\\work\\shop-api\\src\\Order.kt")

        assertEquals(1, ReviewDiffFiles.switchTarget("src/Order.kt", "D:/work/shop-api/", windowsDiff, shownIndex = 0))
    }
}
