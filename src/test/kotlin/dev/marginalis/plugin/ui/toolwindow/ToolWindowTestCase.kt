package dev.marginalis.plugin.ui.toolwindow

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.marginalis.plugin.testing.MarginToolWindow
import dev.marginalis.plugin.testing.WalkthroughFixture
import dev.marginalis.plugin.transport.HttpContractTestCase

abstract class ToolWindowTestCase : HttpContractTestCase() {

    protected val window: MarginToolWindow by lazy { user.openToolWindow() }

    protected fun section(title: String): String {
        val lines = window.outline().lines()
        val start = lines.indexOfFirst { it.startsWith(title) }
        check(start >= 0) { "no section '$title' in\n${window.outline()}" }
        val end = (start + 1 until lines.size).firstOrNull { !lines[it].startsWith(" ") } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    protected fun sections(): List<String> = window.outline().lines().filter { it.isNotEmpty() && !it.startsWith(" ") }

    protected fun interleaved(): Map<Int, String> = WalkthroughFixture.interleaved().run {
        writeInto(myFixture)
        postBy(agent)
    }

    protected fun deep(): Map<Int, String> = WalkthroughFixture.deep().run {
        writeInto(myFixture)
        postBy(agent)
    }

    protected fun openFile(): String? = FileEditorManager.getInstance(project).selectedTextEditor
        ?.let { FileDocumentManager.getInstance().getFile(it.document)?.name }

    protected fun caretLine(): Int = user.editor().caretModel.logicalPosition.line + 1

    protected fun status(threadId: String): String? = agent.threads().singleOrNull { it.id == threadId }?.string("status")
}
