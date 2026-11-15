package dev.marginalis.plugin.ui.diff

interface ReviewFiles {
    val absolutePaths: List<String?>
    val shownIndex: Int

    fun show(index: Int)
}
