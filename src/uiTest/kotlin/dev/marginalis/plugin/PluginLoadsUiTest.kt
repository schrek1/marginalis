package dev.marginalis.plugin

import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

class PluginLoadsUiTest {

    private val builtInServerPort = 11111

    @Test
    fun pluginAnswersPingInARealIde() {
        val ide = IdeInfo.IdeaUltimate.copy(getInstaller = { ExistingIdeInstaller(Path.of(System.getProperty("marginalis.uiTestIde"))) })
        Starter.newContext("pluginAnswersPing", TestCase(ide, NoProject))
            .apply { PluginConfigurator(this).installPluginFromPath(Path.of(System.getProperty("marginalis.plugin"))) }
            .runIdeWithDriver()
            .useDriverAndCloseIde {
                val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                val ping = HttpRequest.newBuilder(URI("http://127.0.0.1:$builtInServerPort/api/marginalis/ping")).build()
                val answer = generateSequence { runCatching { http.send(ping, HttpResponse.BodyHandlers.ofString()) }.getOrNull().also { if (it == null) Thread.sleep(500) } }
                    .take(60).firstNotNullOf { it }
                println("UITEST ping ${answer.statusCode()} ${answer.body()}")
                assertTrue(answer.body().contains("\"status\":\"ok\""))
            }
    }
}
