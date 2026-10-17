package dev.marginalis.plugin.ui.tab

import io.kotest.matchers.shouldBe
import org.junit.Test

class MarginEditorProviderTest {

    @Test
    fun `the Margin tab's provider is checked without the read lock, so a stalled IDE never blames Marginalis`() {
        MarginEditorProvider().acceptRequiresReadAction() shouldBe false
    }
}
