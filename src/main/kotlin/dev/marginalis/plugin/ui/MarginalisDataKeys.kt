package dev.marginalis.plugin.ui

import com.intellij.openapi.actionSystem.DataKey
import dev.marginalis.core.CommentThread

object MarginalisDataKeys {
    val THREAD: DataKey<CommentThread> = DataKey.create("marginalis.thread")
}
