package dev.marginalis.plugin

import com.google.gson.JsonParser
import dev.marginalis.plugin.testing.PluginUnload
import dev.marginalis.plugin.testing.SavedProjectTestCase
import java.nio.file.Files

class UnloadingSavesTest : SavedProjectTestCase() {

    fun testUpdatingThePluginSavesWhereEachThreadSitsNow() {
        openShop()
        val id = agent.addOnLine(12, "body" to "check the bounds")
        waitForSave(id)
        editInIde({ it.insertString(0, "// header\n".repeat(25)) })

        assertNull(PluginUnload.forUpdate())

        val saved = JsonParser.parseString(Files.readString(shop.savedThreads)).asJsonObject
            .getAsJsonArray("threads").map { it.asJsonObject }.single { it["id"].asString == id }
        assertEquals(36, saved["line"].asInt)

        reopen()

        assertEquals("open", agent.thread(id).string("status"))
        assertEquals(37, agent.thread(id)["line"].asInt)
    }
}
