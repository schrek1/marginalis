package dev.marginalis.core

/** Which of a review diff's files a walkthrough step can switch the diff to. */
object ReviewDiffFiles {

    /**
     * @param stepFile the step's file, relative to the project root
     * @param projectRoot the project root, absolute
     * @param diffFiles the diff's files, absolute, in the diff's order
     * @param shownIndex the file the diff shows now, or -1
     * @return the index to switch to; null when the diff has no such file or already shows it
     */
    fun switchTarget(stepFile: String, projectRoot: String, diffFiles: List<String>, shownIndex: Int): Int? {
        val wanted = normalized(projectRoot).trimEnd('/') + "/" + normalized(stepFile).trimStart('/')
        val index = diffFiles.indexOfFirst { normalized(it) == wanted }
        return index.takeIf { it >= 0 && it != shownIndex }
    }

    private fun normalized(path: String): String = path.replace('\\', '/')
}
