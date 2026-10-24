package dev.marginalis.plugin.ui.tab

import org.junit.Assert.assertFalse
import org.junit.Test

class MarginEditorProviderTest {

    @Test
    fun `the Margin tab's provider is checked without the read lock, so a stalled IDE never blames Marginalis`() {
        assertFalse(MarginEditorProvider().acceptRequiresReadAction())
    }
}
