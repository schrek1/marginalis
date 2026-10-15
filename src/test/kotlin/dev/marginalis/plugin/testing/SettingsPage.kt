package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.impl.FileChooserFactoryImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.table.TableView
import com.intellij.util.ui.UIUtil
import dev.marginalis.plugin.settings.MarginalisConfigurable
import java.awt.Component
import java.awt.Container
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTextField

class SettingsPage private constructor(private val disposable: Disposable) {

    private val configurable = MarginalisConfigurable()

    val page: JComponent = configurable.createComponent()

    init {
        Disposer.register(disposable) { configurable.disposeUIResources() }
        configurable.reset()
        page.setSize(900, 1400)
        layOut(page)
    }

    val title: String get() = configurable.displayName

    val isModified: Boolean get() = configurable.isModified

    fun apply() {
        configurable.apply()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun reset() = configurable.reset()

    fun tick(text: String, on: Boolean) {
        val box = checkBox(text)
        if (box.isSelected != on) box.doClick()
    }

    fun isTicked(text: String): Boolean = checkBox(text).isSelected

    private fun checkBox(text: String): JCheckBox = UIUtil.findComponentsOfType(page, JCheckBox::class.java).single { it.text == text }

    fun type(label: String, text: String) {
        field(label).text = text
    }

    fun leave(label: String) {
        val field = field(label)
        field.focusListeners.forEach { it.focusLost(FocusEvent(field, FocusEvent.FOCUS_LOST)) }
    }

    fun text(label: String): String = field(label).text

    private fun field(label: String): JTextField =
        UIUtil.findComponentsOfType(page, JLabel::class.java).single { it.text == label }.labelFor as JTextField

    fun choose(anyItem: Any, label: String) {
        val combo = comboHolding(anyItem)
        combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) }.single { labelOf(combo, it) == label }
    }

    fun chosen(anyItem: Any): String = comboHolding(anyItem).let { labelOf(it, it.selectedItem!!) }

    fun options(anyItem: Any): List<String> = comboHolding(anyItem).let { combo -> (0 until combo.itemCount).map { labelOf(combo, combo.getItemAt(it)) } }

    private fun comboHolding(anyItem: Any): JComboBox<Any> {
        @Suppress("UNCHECKED_CAST")
        return UIUtil.findComponentsOfType(page, JComboBox::class.java)
            .single { combo -> (0 until combo.itemCount).any { combo.getItemAt(it) == anyItem } } as JComboBox<Any>
    }

    private fun labelOf(combo: JComboBox<Any>, item: Any): String =
        combo.renderer.getListCellRendererComponent(JList(), item, 0, false, false).accessibleContext.accessibleName

    fun labels(): List<String> = UIUtil.findComponentsOfType(page, JLabel::class.java).mapNotNull { it.text }

    fun legend(): List<Pair<String, Icon?>> =
        UIUtil.findComponentsOfType(page, JLabel::class.java).filter { it.icon != null && it.text?.contains(": ") == true }.map { it.text to it.icon }

    fun clickLink(text: String) {
        UIUtil.findComponentsOfType(page, ActionLink::class.java).single { it.text == text }.doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    val yourPicture: Icon
        get() = UIUtil.findComponentsOfType(page, JLabel::class.java)
            .single { it.text.isNullOrEmpty() && it.icon != null }.icon

    val people: TableView<*> get() = UIUtil.findComponentOfType(page, TableView::class.java)!!

    val peopleEmptyText: String get() = people.emptyText.text

    fun rows(): List<List<String>> = (0 until people.rowCount).map { row ->
        (1 until people.columnCount).map { column -> people.getValueAt(row, column)?.toString().orEmpty() }
    }

    fun addPerson(identity: String, nickname: String = "", kind: String? = null) {
        perform(ToolbarDecorator.findAddButton(page)!!)
        val row = people.rowCount - 1
        typeInCell(row, IDENTITY, identity, editing = true)
        if (nickname.isNotEmpty()) typeInCell(row, NICKNAME, nickname)
        if (kind != null) pickInCell(row, KIND, kind)
    }

    fun startAdding(): JTextField {
        perform(ToolbarDecorator.findAddButton(page)!!)
        return people.editorComponent as JTextField
    }

    fun startTyping(row: Int, column: Int): JTextField {
        check(people.editCellAt(row, column)) { "cell $row,$column is not editable" }
        return people.editorComponent as JTextField
    }

    fun typeInCell(row: Int, column: Int, text: String, editing: Boolean = false) {
        if (!editing) check(people.editCellAt(row, column)) { "cell $row,$column is not editable" }
        (people.editorComponent as JTextField).text = text
        people.cellEditor.stopCellEditing()
    }

    fun pickInCell(row: Int, column: Int, choice: String) {
        check(people.editCellAt(row, column))
        @Suppress("UNCHECKED_CAST")
        (people.editorComponent as JComboBox<Any>).selectedItem = choice
        people.cellEditor?.stopCellEditing()
    }

    fun select(row: Int) {
        people.selectionModel.setSelectionInterval(row, row)
    }

    fun selectNone() = people.clearSelection()

    fun removeSelected() = perform(ToolbarDecorator.findRemoveButton(page)!!)

    fun isOffered(text: String): Boolean {
        val action = peopleAction(text)
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.isEnabled
    }

    fun press(text: String) = perform(peopleAction(text))

    private fun peopleAction(text: String): AnAction = UIUtil.uiTraverser(page).filter(ActionToolbar::class.java)
        .flatMap { it.actionGroup.getChildren(null).toList() }
        .single { it.templatePresentation.text == text }

    private fun perform(action: AnAction) {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun avatarOf(row: Int): Icon? = (people.prepareRenderer(people.getCellRenderer(row, AVATAR), row, AVATAR) as JLabel).icon

    fun avatarTooltip(row: Int): String? = (people.prepareRenderer(people.getCellRenderer(row, AVATAR), row, AVATAR) as JLabel).toolTipText

    fun clickAvatar(row: Int, button: Int = MouseEvent.BUTTON1, clicks: Int = 1, modifiers: Int = 0) =
        clickCell(row, AVATAR, button, clicks, modifiers)

    fun clickCell(row: Int, column: Int, button: Int = MouseEvent.BUTTON1, clicks: Int = 1, modifiers: Int = 0) {
        layOut(page)
        val cell = people.getCellRect(row, column, true)
        val x = cell.centerX.toInt()
        val y = cell.centerY.toInt()
        val mask = modifiers or when (button) {
            MouseEvent.BUTTON3 -> InputEvent.BUTTON3_DOWN_MASK
            else -> InputEvent.BUTTON1_DOWN_MASK
        }
        val popup = button == MouseEvent.BUTTON3
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            val event = MouseEvent(people, id, System.currentTimeMillis(), mask, x, y, x, y, clicks, popup && id == MouseEvent.MOUSE_PRESSED, button)
            people.mouseListeners.filterNot { it.javaClass.name.startsWith("javax.swing.plaf.") }.forEach { listener ->
                when (id) {
                    MouseEvent.MOUSE_PRESSED -> listener.mousePressed(event)
                    MouseEvent.MOUSE_RELEASED -> listener.mouseReleased(event)
                    else -> listener.mouseClicked(event)
                }
            }
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun errorsWhile(act: (List<String>) -> Unit): List<String> {
        val shown = mutableListOf<String>()
        TestDialogManager.setTestDialog(TestDialog { message ->
            shown += message
            Messages.OK
        })
        try {
            act(shown)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        return shown
    }

    var choosersOpened = 0
        private set

    fun pictureChooserOffers(file: Path?) {
        val chosen: VirtualFile? = file?.let { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it) }
        val chooser = object : FileChooserFactoryImpl() {
            override fun createFileChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?): FileChooserDialog =
                object : FileChooserDialog {
                    override fun choose(project: Project?, vararg toSelect: VirtualFile?): Array<VirtualFile> {
                        choosersOpened++
                        return listOfNotNull(chosen).toTypedArray()
                    }
                }
        }
        ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, chooser, disposable)
    }

    private fun layOut(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layOut)
        }
    }

    companion object {
        const val AVATAR = 0
        const val IDENTITY = 1
        const val NICKNAME = 2
        const val KIND = 3

        fun open(disposable: Disposable): SettingsPage = SettingsPage(disposable)
    }
}
