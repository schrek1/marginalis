package dev.marginalis.plugin.ui.toolwindow

import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class ExpandCollapseTest : ToolWindowTestCase() {

    override fun setUp() {
        super.setUp()
        deep()
        window.layOutGuided("By folder")
    }

    fun testCollapseFoldsOneLevelFurtherOutWithEachPressUpToTheSection() {
        window.click("Step 16", under = GUIDED)

        val foldedPressByPress = LEVELS_FROM_STEP_16.map {
            press(COLLAPSE)
            selectedName()
        }

        assertEquals(LEVELS_FROM_STEP_16, foldedPressByPress)
        assertFalse(window.isExpanded(GUIDED))
        assertTrue(window.isExpanded("Open"))
    }

    fun testCollapseFoldsEverythingInsideTheSectionAsIntelliJDoes() {
        window.click("Step 16", under = GUIDED)
        repeat(LEVELS_FROM_STEP_16.size) { press(COLLAPSE) }

        window.toggleFolding(GUIDED)

        assertEquals(emptyList<String>(), foldersReopeningAsLeftInside(GUIDED))
    }

    fun testPastTheSectionCollapseFoldsEverySection() {
        window.click(GUIDED)

        press(COLLAPSE)
        press(COLLAPSE)

        assertEquals(emptyList<String>(), sectionTitles().filter { window.isExpanded(it) })
    }

    fun testExpandOpensTheSelectedSectionAllTheWayDownAndLeavesTheOthersFolded() {
        foldEverySection()

        press(EXPAND)

        assertEquals(emptyList<String>(), foldedFoldersUnder(GUIDED))
        assertFalse(window.isExpanded("Open"))
    }

    fun testExpandAgainOpensEverySectionAllTheWayDown() {
        foldEverySection()

        press(EXPAND)
        press(EXPAND)

        assertEquals(emptyList<String>(), sectionTitles().flatMap(::foldedFoldersUnder))
    }

    fun testExpandOnAFoldedFolderOpensThatFolder() {
        window.toggleFolding(ORDERS, under = GUIDED)
        window.click(ORDERS, under = GUIDED)

        press(EXPAND)

        assertTrue(window.isExpanded(ORDERS, under = GUIDED))
    }

    private fun foldEverySection() {
        window.click(GUIDED)
        press(COLLAPSE)
        press(COLLAPSE)
    }

    // The button's name follows the selection (Folder, Section, All Sections), so press whichever it shows now.
    private fun press(names: List<String>) {
        val shown = names.first { name -> runCatching { window.action(name) }.isSuccess }
        window.press(shown)
    }

    private fun selectedName(): String? = window.selected()?.substringBefore("  ")?.trim()

    private fun sectionTitles(): List<String> = sections().map { it.substringBefore("  ") }

    // A tree remembers a hidden node's expanded state, so open the levels one by one as a reader would.
    private fun foldersReopeningAsLeftInside(section: String): List<String> {
        val stillOpen = mutableListOf<String>()
        fun walk(node: DefaultMutableTreeNode) {
            node.children().toList().map { it as DefaultMutableTreeNode }.filterNot { it.isLeaf }.forEach { child ->
                val path = TreePath(child.path)
                if (window.tree.isExpanded(path)) stillOpen += name(path) else window.tree.expandPath(path)
                walk(child)
            }
        }
        walk(sectionNode(section))
        return stillOpen
    }

    private fun foldedFoldersUnder(section: String): List<String> {
        val sectionNode = sectionNode(section)
        return sectionNode.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
            .filter { it !== sectionNode && !it.isLeaf }
            .map { TreePath(it.path) }
            .filterNot { window.tree.isExpanded(it) }
            .map(::name)
    }

    private fun sectionNode(section: String): DefaultMutableTreeNode {
        val tree = window.tree
        val root = tree.model.root as DefaultMutableTreeNode
        return root.children().toList().map { it as DefaultMutableTreeNode }
            .single { tree.getRowForPath(TreePath(it.path)).let { row -> row >= 0 && window.rowText(row).startsWith(section) } }
    }

    private fun name(path: TreePath): String = (path.lastPathComponent as DefaultMutableTreeNode).userObject.toString()

    private companion object {
        const val GUIDED = "Guided SHOP-42"
        const val ORDERS = "shop-orders/src"
        val LEVELS_FROM_STEP_16 = listOf(
            "api", "customer/settings", "main/java/com/acme/backend/common", "shop-common/src", "backend", "acme", GUIDED,
        )
        val COLLAPSE = listOf("Collapse Folder", "Collapse Section", "Collapse All Sections", "Collapse")
        val EXPAND = listOf("Expand Folder", "Expand Section", "Expand All Sections", "Expand")
    }
}
