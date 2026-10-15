package dev.marginalis.plugin.testing

import com.intellij.ide.browsers.BrowserLauncher
import com.intellij.ide.browsers.WebBrowser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import java.io.File
import java.nio.file.Path

class Browser(disposable: Disposable) {

    val opened: MutableList<String> = mutableListOf()

    init {
        ApplicationManager.getApplication().replaceService(BrowserLauncher::class.java, Launcher(), disposable)
    }

    private inner class Launcher : BrowserLauncher() {
        override fun open(url: String) {
            opened += url
        }

        override fun browse(file: File) {
            opened += file.toURI().toString()
        }

        override fun browse(file: Path) {
            opened += file.toUri().toString()
        }

        override fun browse(url: String, browser: WebBrowser?, project: Project?) {
            opened += url
        }
    }
}
