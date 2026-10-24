package dev.marginalis.plugin.ui.tab

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile

// The platform restores editor tabs by URL; a LightVirtualFile on the light
// file system can't be found again that way, so the tab would vanish on restart.
class MarginFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? = MarginFile.atPath(path)

    override fun refresh(asynchronous: Boolean) {}

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    companion object {
        const val PROTOCOL = "marginalis"
    }
}
