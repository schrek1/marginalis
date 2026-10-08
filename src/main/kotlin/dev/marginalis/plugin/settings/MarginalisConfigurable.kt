package dev.marginalis.plugin.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.Face
import dev.marginalis.core.Faces
import dev.marginalis.core.Intent
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.Mark
import dev.marginalis.core.MarkSubject
import dev.marginalis.core.People
import dev.marginalis.core.ReadWhen
import dev.marginalis.core.You
import dev.marginalis.plugin.avatars.AvatarsListener
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.AvatarGeometries
import dev.marginalis.plugin.ui.AvatarIcon
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.tab.ProjectTab
import java.awt.Component
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultCellEditor
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class MarginalisConfigurable : Configurable {

    private class EditableRow(
        var identity: String = "",
        var nickname: String = "",
        var kind: People.Kind = People.Kind.PERSON,
        var picture: String? = null,
    ) {
        fun toRow(): People.Row? = People.Row.normalizedOrNull(identity, kind, nickname, picture)

        fun face(): Face? = People.Row(identity, kind, nickname, picture).takeIf { it.key.isNotEmpty() }?.face()

        companion object {
            fun of(row: People.Row) = EditableRow(row.identity, row.nickname, row.kind, row.picture)
        }
    }

    private var panel: com.intellij.openapi.ui.DialogPanel? = null
    private var disposable: Disposable? = null

    private val avatarRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, "", isSelected, false, row, column)
            horizontalAlignment = SwingConstants.CENTER
            icon = (value as? Face)?.let { face -> AvatarIcon(face, { background }) }
            toolTipText = "Click to choose a picture; right-click to clear it"
            return this
        }
    }

    private val kindEditor = DefaultCellEditor(ComboBox(People.Kind.entries.map(::labelOf).toTypedArray()))

    private val people = ListTableModel<EditableRow>(
        object : ColumnInfo<EditableRow, Face>("") {
            override fun valueOf(item: EditableRow): Face? = item.face()

            override fun getRenderer(item: EditableRow): TableCellRenderer = avatarRenderer

            override fun getWidth(table: JTable): Int = AvatarGeometries.current().size + JBUI.scale(12)
        },
        object : ColumnInfo<EditableRow, String>("GitHub login or agent id") {
            override fun valueOf(item: EditableRow): String = item.identity

            override fun isCellEditable(item: EditableRow): Boolean = true

            override fun setValue(item: EditableRow, value: String?) {
                item.identity = value.orEmpty()
            }
        },
        object : ColumnInfo<EditableRow, String>("Nickname") {
            override fun valueOf(item: EditableRow): String = item.nickname

            override fun isCellEditable(item: EditableRow): Boolean = true

            override fun setValue(item: EditableRow, value: String?) {
                item.nickname = value.orEmpty()
            }
        },
        object : ColumnInfo<EditableRow, String>("Person or agent") {
            override fun valueOf(item: EditableRow): String = labelOf(item.kind)

            override fun isCellEditable(item: EditableRow): Boolean = true

            override fun setValue(item: EditableRow, value: String?) {
                item.kind = People.Kind.entries.firstOrNull { labelOf(it) == value } ?: People.Kind.PERSON
            }

            override fun getEditor(item: EditableRow): TableCellEditor = kindEditor
        },
    )

    private val peopleTable = TableView(people).apply {
        emptyText.text = "No one added yet: everyone shows their GitHub picture or initials"
        setShowGrid(false)
        rowHeight = maxOf(rowHeight, AvatarGeometries.current().size + JBUI.scale(8))
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private var userPicture: String = ""

    private val userPreview = JBLabel()

    private val displayNameField = JBTextField()

    private val githubLoginField = JBTextField()

    private var previewedLogin: String = ""

    init {
        peopleTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = clearOnRightClick(e)

            override fun mouseReleased(e: MouseEvent) = clearOnRightClick(e)

            override fun mouseClicked(e: MouseEvent) {
                val singleLeftClick = SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1
                if (!singleLeftClick || e.isControlDown || e.isPopupTrigger) return
                val row = avatarRowAt(e) ?: return
                e.consume()
                choosePicture(row)
            }
        })
        displayNameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = showUserPicture()
        })
        // Previewing per keystroke would fetch a GitHub avatar for every prefix of the login being typed.
        githubLoginField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = previewLogin()
        })
    }

    private fun clearOnRightClick(e: MouseEvent) {
        if (!SwingUtilities.isRightMouseButton(e) && !e.isPopupTrigger) return
        val row = avatarRowAt(e) ?: return
        e.consume()
        clearPicture(row)
    }

    private fun choosePicture(row: EditableRow) {
        Pictures.choose(peopleTable) { key ->
            row.picture = key
            refreshRow(row)
        }
    }

    private fun clearPicture(row: EditableRow) {
        row.picture = null
        refreshRow(row)
    }

    private fun selectedRowAction(text: String, icon: Icon, perform: (EditableRow) -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = peopleTable.selectedRowCount == 1
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (peopleTable.isEditing) peopleTable.cellEditor.stopCellEditing()
                peopleTable.selectedObject?.let(perform)
            }
        }

    private fun avatarRowAt(e: MouseEvent): EditableRow? {
        val row = peopleTable.rowAtPoint(e.point)
        val column = peopleTable.columnAtPoint(e.point)
        if (row < 0 || column < 0 || peopleTable.convertColumnIndexToModel(column) != AVATAR_COLUMN) return null
        if (peopleTable.isEditing) peopleTable.cellEditor.stopCellEditing()
        return people.getItem(peopleTable.convertRowIndexToModel(row))
    }

    private fun refreshRow(row: EditableRow) {
        val index = people.indexOf(row)
        if (index >= 0) people.fireTableRowsUpdated(index, index)
    }

    override fun getDisplayName(): String = "Marginalis"

    override fun createComponent(): JComponent {
        val settings = MarginalisSettings.getInstance()
        val state = settings.state
        val created = panel {
            row {
                checkBox("Allow agent navigation")
                    .comment(
                        "Lets the agent open a file and move the caret when you ask it to " +
                            "(\"show me where that is\"). When off, navigation requests are refused.",
                    )
                    .bindSelected(state::navigationEnabled)
            }
            row("Display name:") {
                cell(displayNameField)
                    .comment("Shown as the author of your comments. Leave blank to use your OS username.")
                    .columns(24)
                    .bindText(state::displayName)
            }
            row("GitHub login:") {
                cell(githubLoginField)
                    .comment(
                        "Your own comments relayed from GitHub show as yours, marked \"on GitHub\". " +
                            "Leave blank to show them like anyone else's.",
                    )
                    .columns(24)
                    .bindText(state::githubLogin)
            }
            row("Your picture:") {
                cell(userPreview)
                link("Choose…") {
                    Pictures.choose(userPreview) { key ->
                        userPicture = key
                        showUserPicture()
                    }
                }
                link("Clear") {
                    userPicture = ""
                    showUserPicture()
                }
            }
            group("People") {
                row {
                    cell(
                        ToolbarDecorator.createDecorator(peopleTable)
                            .setAddAction { addPerson() }
                            .setRemoveAction { removeSelectedPeople() }
                            .addExtraAction(selectedRowAction("Choose Picture…", AllIcons.FileTypes.Image, ::choosePicture))
                            .addExtraAction(selectedRowAction("Clear Picture", AllIcons.Actions.Rollback, ::clearPicture))
                            .createPanel(),
                    )
                        .align(Align.FILL)
                        .comment(
                            "Give a teammate's GitHub login or an agent's id a nickname and a picture. " +
                                "Click a picture to change it; right-click to clear it.",
                        )
                }
                row {
                    checkBox("Show GitHub avatars when no picture is set")
                        .comment("When off, Marginalis fetches nothing from GitHub and shows initials instead.")
                        .bindSelected(state::showGithubAvatars)
                }
            }
            row {
                checkBox("Jump to the next step after resolving")
                    .comment("While walking a guided walkthrough, resolving a step opens the next one.")
                    .bindSelected(state::walkthroughAutoAdvance)
            }
            row {
                checkBox("Notify when the agent posts elsewhere")
                    .comment(
                        "A balloon when an agent message lands in a file you don't have open in front " +
                            "of you. Turn-taking, not presence: one notification per message, nothing pulses.",
                    )
                    .bindSelected(state::notifyOnAgentReply)
            }
            row {
                checkBox("Live by default")
                    .comment(
                        "Every open thread is live, so each Submit wakes the listening agent. " +
                            "A project takes this when first opened; ⚡ in its Marginalis toolbar changes it there.",
                    )
                    .bindSelected(state::liveByDefault)
            }
            row("Time format:") {
                comboBox(TimeFormat.entries, textListCellRenderer { it?.label })
                    .comment("Message timestamps in thread panels.")
                    .bindItem(
                        { settings.timeFormat },
                        { settings.timeFormat = it ?: TimeFormat.AUTO },
                    )
            }
            group("Project Tab") {
                row("List while the tab is in front:") {
                    comboBox(ListWhileInFront.entries, textListCellRenderer { it?.let(::labelOf) })
                        .comment(
                            "Live keeps the latest activity on top. Hold still keeps everything in place " +
                                "while you read, and new threads wait behind a pill.",
                        )
                        .bindItem(
                            { settings.listWhileInFront },
                            { settings.listWhileInFront = it ?: ListWhileInFront.LIVE },
                        )
                }
                row {
                    checkBox("Expand a collapsed thread when it becomes your move")
                        .comment("When off, a thread stays as you left it.")
                        .bindSelected(state::expandOnYourMove)
                }
                row("What counts as read:") {
                    comboBox(ReadWhen.entries, textListCellRenderer { it?.let(::labelOf) })
                        .comment("Matters most for an FYI: its ✉ clears once you've read it.")
                        .bindItem(
                            { settings.readWhen },
                            { settings.readWhen = it ?: ReadWhen.EXPANDED_IN_FRONT },
                        )
                }
                row {
                    checkBox("Group a pull request's relayed conversation")
                        .comment("Folds a PR's conversation from GitHub into one group. When off, each comment is its own thread.")
                        .bindSelected(state::groupRelayedConversation)
                }
            }
            group("Intent Glyphs") {
                for ((intent, meaning) in INTENT_LEGEND) {
                    row {
                        cell(JBLabel(meaning, MarginalisIcons.mark(Mark(MarkSubject.LINE, intent)), SwingConstants.LEADING))
                    }
                }
            }
        }
        created.border = JBUI.Borders.empty(8)
        panel = created
        val connection = Disposer.newDisposable("Marginalis settings").also { disposable = it }
        ApplicationManager.getApplication().messageBus.connect(connection)
            .subscribe(AvatarsListener.TOPIC, AvatarsListener {
                peopleTable.repaint()
                userPreview.repaint()
            })
        return created
    }

    override fun isModified(): Boolean {
        val settings = MarginalisSettings.getInstance()
        return peopleTable.isEditing ||
            (panel?.isModified() ?: false) ||
            editedRows() != settings.rows ||
            userPicture != settings.state.userPicture
    }

    override fun apply() {
        if (peopleTable.isEditing) peopleTable.cellEditor.stopCellEditing()
        val edited = editedRows()
        People.duplicate(edited)?.let {
            throw ConfigurationException("'${it.identity}' is listed twice as ${roleOf(it.kind)}")
        }
        val settings = MarginalisSettings.getInstance()
        val bylinesBefore = bylineSettings()
        val tabBefore = settings.projectTabPrefs
        panel?.apply()
        settings.rows = edited
        settings.state.userPicture = userPicture
        Pictures.deleteUnreferenced(edited, userPicture)
        resetPeople()
        if (bylineSettings() != bylinesBefore) refreshThreads()
        if (settings.projectTabPrefs != tabBefore) ProjectTab.applyPrefs()
    }

    private fun bylineSettings(): List<Any> {
        val settings = MarginalisSettings.getInstance()
        return with(settings.state) { listOf(settings.rows, displayName, githubLogin, userPicture, showGithubAvatars) }
    }

    override fun reset() {
        panel?.reset()
        userPicture = MarginalisSettings.getInstance().state.userPicture
        previewLogin()
        resetPeople()
    }

    private fun resetPeople() {
        if (peopleTable.isEditing) peopleTable.cellEditor.cancelCellEditing()
        people.items = MarginalisSettings.getInstance().rows.mapTo(ArrayList(), EditableRow::of)
    }

    private fun previewLogin() {
        previewedLogin = githubLoginField.text
        showUserPicture()
    }

    private fun showUserPicture() {
        val name = Authors.userNamed(displayNameField.text).displayName
        val you = Faces(People.NONE, You(name, previewedLogin, userPicture)).yours
        userPreview.icon = AvatarIcon(you, UIUtil::getPanelBackground)
    }

    private fun editedRows(): List<People.Row> = people.items.mapNotNull(EditableRow::toRow)

    private fun addPerson() {
        people.addRow(EditableRow())
        val row = people.rowCount - 1
        peopleTable.selectionModel.setSelectionInterval(row, row)
        peopleTable.editCellAt(row, IDENTITY_COLUMN)
    }

    private fun removeSelectedPeople() {
        if (peopleTable.isEditing) peopleTable.cellEditor.cancelCellEditing()
        peopleTable.selectedRows.map(peopleTable::convertRowIndexToModel).sortedDescending().forEach(people::removeRow)
    }

    private fun refreshThreads() {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val threads = MarginalisStore.getInstance(project).threads
            threads.all().forEach(threads::notifyChanged)
        }
    }

    override fun disposeUIResources() {
        disposable?.let(Disposer::dispose)
        disposable = null
        panel = null
    }

    private companion object {
        const val AVATAR_COLUMN = 0
        const val IDENTITY_COLUMN = 1

        fun labelOf(kind: People.Kind): String = when (kind) {
            People.Kind.PERSON -> "Person"
            People.Kind.AGENT -> "Agent"
        }

        fun labelOf(list: ListWhileInFront): String = when (list) {
            ListWhileInFront.LIVE -> "Live"
            ListWhileInFront.HOLD_STILL -> "Hold still"
        }

        fun labelOf(readWhen: ReadWhen): String = when (readWhen) {
            ReadWhen.EXPANDED_IN_FRONT -> "Expanded while the tab is in front"
            ReadWhen.CLICKED_INTO -> "Only when you click into the thread"
        }

        fun roleOf(kind: People.Kind): String = when (kind) {
            People.Kind.PERSON -> "a person"
            People.Kind.AGENT -> "an agent"
        }

        val INTENT_LEGEND = listOf(
            Intent.FINDING to "Finding: something to fix",
            Intent.GUIDANCE to "Guidance: how to write the code around here",
            Intent.QUESTION to "Question: an answer is wanted",
            Intent.FYI to "FYI: praise, context, a heads-up; nothing is owed once you've read it",
            null to "Ordinary: a comment with no stated intent",
        )
    }
}
