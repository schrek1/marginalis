package dev.marginalis.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

object ProjectPaths {

    fun of(project: Project, file: VirtualFile): String? =
        project.guessProjectDir()?.let { base -> VfsUtilCore.getRelativePath(file, base) }
}
