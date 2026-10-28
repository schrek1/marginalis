package dev.marginalis.plugin.ui

import com.intellij.ide.FileEditorProvider
import com.intellij.ide.SelectInContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.marginalis.core.CommentThread
import dev.marginalis.plugin.ui.tab.MarginFile

class ThreadSelectInContext(private val project: Project, val thread: CommentThread) : SelectInContext {

    private val file: VirtualFile = thread.file?.let { project.guessProjectDir()?.findFileByRelativePath(it) } ?: MarginFile.of(project)

    override fun getProject(): Project = project

    override fun getVirtualFile(): VirtualFile = file

    override fun getSelectorInFile(): Any? = file.takeIf { it !is MarginFile }?.let { PsiManager.getInstance(project).findFile(it) }

    override fun getFileEditorProvider(): FileEditorProvider? = null
}
