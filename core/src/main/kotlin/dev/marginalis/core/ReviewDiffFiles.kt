package dev.marginalis.core

object ReviewDiffFiles {

    fun switchTarget(stepFile: String, projectRoot: String, diffFiles: List<String?>, shownIndex: Int): Int? =
        indexOf(stepFile, projectRoot, diffFiles)?.takeIf { it != shownIndex }

    fun indexOf(stepFile: String, projectRoot: String, diffFiles: List<String?>): Int? =
        diffFiles.indexOf("$projectRoot/$stepFile").takeIf { it >= 0 }
}
