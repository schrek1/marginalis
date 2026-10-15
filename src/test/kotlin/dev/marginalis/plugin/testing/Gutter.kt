package dev.marginalis.plugin.testing

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import dev.marginalis.plugin.ui.ThreadGutterIconRenderer

object Gutter {

    fun linesShowing(project: Project, document: Document, text: String): List<Int> {
        val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return emptyList()
        return markup.allHighlighters
            .filter { (it.gutterIconRenderer as? ThreadGutterIconRenderer)?.tooltipText?.contains(text) == true }
            .map { document.getLineNumber(it.startOffset) + 1 }
            .sorted()
    }
}
