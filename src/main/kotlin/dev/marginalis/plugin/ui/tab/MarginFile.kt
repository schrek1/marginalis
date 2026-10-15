package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.VirtualFileWithoutContent
import com.intellij.testFramework.LightVirtualFile

// VirtualFileWithoutContent: no Document is ever created for it.
// No file system of its own: the platform can't find it by URL, so it never restores the tab (see ProjectTab.park).
object MarginFile : LightVirtualFile("Margin"), VirtualFileWithoutContent {

    init {
        isWritable = false
    }
}
