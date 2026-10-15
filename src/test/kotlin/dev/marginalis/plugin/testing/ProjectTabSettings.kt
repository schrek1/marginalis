package dev.marginalis.plugin.testing

import com.intellij.util.ui.UIUtil
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.ReadWhen
import dev.marginalis.plugin.settings.MarginalisConfigurable
import dev.marginalis.plugin.settings.MarginalisSettings
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList

class ProjectTabSettings(private val page: JComponent) {

    fun listWhileInFront(label: String) = choose(ListWhileInFront.LIVE, label)

    fun whatCountsAsRead(label: String) = choose(ReadWhen.EXPANDED_IN_FRONT, label)

    fun expandWhenItBecomesYourMove(on: Boolean) = tick("Expand a collapsed thread when it becomes your move", on)

    fun groupRelayedConversation(on: Boolean) = tick("Group a pull request's relayed conversation", on)

    private fun tick(text: String, on: Boolean) {
        UIUtil.findComponentsOfType(page, JCheckBox::class.java).single { it.text == text }.isSelected = on
    }

    private fun choose(anyItem: Any, label: String) {
        @Suppress("UNCHECKED_CAST")
        val combo = UIUtil.findComponentsOfType(page, JComboBox::class.java)
            .single { combo -> (0 until combo.itemCount).any { combo.getItemAt(it) == anyItem } } as JComboBox<Any>
        combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) }.single { labelOf(combo, it) == label }
    }

    private fun labelOf(combo: JComboBox<Any>, item: Any): String =
        combo.renderer.getListCellRendererComponent(JList(), item, 0, false, false).accessibleContext.accessibleName

    companion object {
        fun change(edit: ProjectTabSettings.() -> Unit) {
            val configurable = MarginalisConfigurable()
            try {
                val page = configurable.createComponent()
                configurable.reset()
                ProjectTabSettings(page).edit()
                configurable.apply()
            } finally {
                configurable.disposeUIResources()
            }
        }

        fun restoreDefaults() {
            val settings = MarginalisSettings.getInstance()
            settings.listWhileInFront = ListWhileInFront.LIVE
            settings.readWhen = ReadWhen.EXPANDED_IN_FRONT
            settings.state.expandOnYourMove = true
            settings.state.groupRelayedConversation = true
            settings.state.displayName = ""
        }
    }
}
