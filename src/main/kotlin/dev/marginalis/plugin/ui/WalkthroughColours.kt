package dev.marginalis.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.store.MarginalisStore
import java.awt.Color

object WalkthroughColours {
    val palette: List<JBColor> = listOf(
        JBColor(Color(0x3B, 0x7B, 0xE0), Color(0x4C, 0x8D, 0xF6)),
        JBColor(Color(0xC9, 0x55, 0x9A), Color(0xE0, 0x6F, 0xB0)),
        JBColor(Color(0x16, 0x99, 0x8B), Color(0x1F, 0xB5, 0xA5)),
        JBColor(Color(0x7F, 0x96, 0x20), Color(0x9D, 0xB5, 0x2B)),
    )

    fun of(project: Project, shown: List<CommentThread>): JBColor? =
        Walkthrough.colourSlot(MarginalisStore.getInstance(project).threads.all(), shown, palette.size)?.let(palette::get)
}
