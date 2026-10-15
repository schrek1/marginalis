package dev.marginalis.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.Addressee
import dev.marginalis.core.AtMention
import dev.marginalis.core.Author
import dev.marginalis.core.CommentThread
import dev.marginalis.core.FaceKey
import dev.marginalis.core.Faces
import dev.marginalis.core.Identities
import dev.marginalis.core.Identity
import dev.marginalis.core.Intent
import dev.marginalis.core.LiveThread
import dev.marginalis.core.Mark
import dev.marginalis.core.Message
import dev.marginalis.core.People
import dev.marginalis.core.Reference
import dev.marginalis.core.Relayed
import dev.marginalis.core.SendOption
import dev.marginalis.core.Severity
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.WebLink
import dev.marginalis.plugin.avatars.AvatarsListener
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.settings.TimeFormat
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.Timer
import kotlin.math.roundToInt

internal const val RELAYED_STAYS_DELETED = "This removes it from the margin only (GitHub is untouched), and relaying the PR again won't bring it back."

class ThreadPanel(
    private val project: Project,
    private val editor: Editor?,
    private val thread: CommentThread,
    private val ensureStored: () -> Unit,
    private val onClose: () -> Unit,
    private val hostWidth: () -> Int,
    private val mayMarkRead: (clickedInto: Boolean) -> Boolean = { true },
    private val newTags: (CommentThread) -> Set<String> = { emptySet() },
    private val onDraftPresenceChanged: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val messagesBox = Box.createVerticalBox()
    private val messageComponents = mutableMapOf<String, JComponent>()
    private val statusLabel = JBLabel()
    private val liveLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.emptyRight(6)
    }
    private var livePulseDim = false
    private val livePulse = Timer(LIVE_PULSE_MILLIS) {
        livePulseDim = !livePulseDim
        liveLabel.foreground = if (livePulseDim) UIUtil.getContextHelpForeground() else LIVE_COLOR
    }

    private val submitAction = object : AbstractAction("Submit") {
        override fun actionPerformed(e: ActionEvent?) = submit()
    }
    private val commentOnFileAction = object : AbstractAction("Comment on file instead") {
        override fun actionPerformed(e: ActionEvent?) = submitWiderThan(file = thread.file)
    }
    private val commentOnProjectAction = object : AbstractAction("Comment on project instead") {
        override fun actionPerformed(e: ActionEvent?) = submitWiderThan(file = null)
    }
    private val handBack = MarginalisStore.getInstance(project).handBack
    private val submitAndSendRoundAction = object : AbstractAction("Submit & send round") {
        override fun actionPerformed(e: ActionEvent?) {
            if (replyArea.text.isBlank()) return
            sendReply()
            MarginalisStore.getInstance(project).recordHandBack()
        }
    }
    private val sendButton = JBOptionButton(submitAction, arrayOf(submitAndSendRoundAction))
    private val replyRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(4)
    }
    private lateinit var composerHolder: JComponent
    private lateinit var composerActions: JComponent
    private lateinit var collapsedReply: JComponent
    private val agreeLink = ActionLink("Agree") { agree() }.apply {
        icon = MarginalisIcons.Agree
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.emptyLeft(12)
    }
    private var agreeableId: String? = null
    private var renderedMessages: List<Message> = emptyList()
    var showsNewTags = false
        private set
    private var whileAttached: Disposable? = null
    private var composerExpanded = false
    private var addressee: Addressee? = null
    private var agentNames: Map<String, String> = emptyMap()
    private val addresseeLink = ActionLink("") {
        addressTo(null)
        saveDraft()
    }.apply {
        toolTipText = "Click to address everyone instead"
    }
    private val cancelEditLink = ActionLink("Cancel") {
        editingMessageId = null
        replyArea.text = ""
        setComposerExpanded(false)
        refresh()
    }

    private val replyArea = EditorTextField("", project, CodeFenceFileTypes.of("markdown")).apply {
        setOneLineMode(false)
        addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                editor?.let { ComposerFenceHighlighter.repaint(it, project) }
                if (event.newFragment.toString() == "@" && editingMessageId == null &&
                    !UndoManager.getInstance(project).isUndoOrRedoInProgress &&
                    AtMention.startsAt(event.document.text, event.offset)
                ) {
                    ApplicationManager.getApplication().invokeLater { pickAddressee(event.offset) }
                }
            }
        })
        addSettingsProvider { composerEditor ->
            composerEditor.settings.isUseSoftWraps = true
            ComposerFenceHighlighter.repaint(composerEditor, project)
            composerEditor.installPopupHandler(
                ContextMenuPopupHandler.Simple(
                    DefaultActionGroup(
                        ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_CUT),
                        ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_COPY),
                        ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_PASTE),
                        ActionManager.getInstance().getAction(IdeActions.ACTION_SELECT_ALL),
                    ),
                ),
            )
            composerEditor.contentComponent.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER && (e.isMetaDown || e.isControlDown)) {
                        e.consume()
                        submit()
                    }
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        e.consume()
                        closeAndRefocus()
                    }
                }
            })
        }
    }
    private var editingMessageId: String? = null

    init {
        val accent = when (thread.severity) {
            Severity.BLOCKER -> JBColor(Color(0xDB, 0x58, 0x60), Color(0xC7, 0x54, 0x50))
            Severity.NIT -> JBColor(Color(0xB8, 0xB8, 0xB8), Color(0x5E, 0x61, 0x64))
            null -> JBColor(Color(0x9C, 0x27, 0xB0), Color(0xCE, 0x93, 0xD8))
        }
        border = JBUI.Borders.compound(
            JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 1),
                JBUI.Borders.customLine(accent, 0, 3, 0, 0),
            ),
            JBUI.Borders.empty(8),
        )
        background = UIUtil.getPanelBackground()

        add(buildHeader(), BorderLayout.NORTH)
        add(messagesBox, BorderLayout.CENTER)
        add(buildReplyRow(), BorderLayout.SOUTH)
        // Submit must be a REGISTERED shortcut, not a KeyListener: the IDE's
        // key dispatcher routes ⌘⏎ to editor actions (Split Line on several
        // keymaps) before the component sees the event.
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = submit()
        }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke("meta ENTER"), null),
                KeyboardShortcut(KeyStroke.getKeyStroke("control ENTER"), null),
            ),
            replyArea,
        )
        // Reading mode needs a focus home for Esc and the walk shortcuts.
        isFocusable = true
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) markReadIfSeenLater()
        }
        MarginalisStore.getInstance(project).drafts[thread.id]?.let {
            replyArea.text = it.text
            addressee = it.to
            setComposerExpanded(true)
        }
        replyArea.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = saveDraft()
        })
        replyArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                reloadDraft()
                markRead()
            }
        })
        // The composer handles its own Esc: its editor consumes key events.
        registerKeyboardAction(
            { closeAndRefocus() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
        refresh()
    }

    private fun closeAndRefocus() {
        onClose()
        editor?.contentComponent?.requestFocusInWindow()
    }

    /**
     * Never set preferredSize directly — an explicit value freezes the height
     * at construction time and the inlay squashes to a single line.
     */
    override fun getPreferredSize(): Dimension {
        val computed = super.getPreferredSize()
        return Dimension(panelWidth(), computed.height)
    }

    private fun panelWidth(): Int = hostWidth()

    private fun buildHeader(): JComponent {
        val header = JPanel(BorderLayout()).apply { isOpaque = false }
        statusLabel.font = JBUI.Fonts.smallFont()
        statusLabel.foreground = UIUtil.getContextHelpForeground()

        val left = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(
                JBLabel(MarginalisIcons.mark(Mark.of(listOf(thread)))).apply {
                    toolTipText = thread.intent?.name?.lowercase() ?: "ordinary comment"
                },
            )
            add(Box.createHorizontalStrut(JBUI.scale(6)))
            if (thread.intent == Intent.FYI) {
                add(Chip(thread.label ?: "fyi", FYI_PILL, FYI_TEXT))
                add(Box.createHorizontalStrut(JBUI.scale(6)))
            }
            thread.severity?.let { severity ->
                add(Chip(severity.name.lowercase(), severityPill(severity), severityText(severity)))
                add(Box.createHorizontalStrut(JBUI.scale(8)))
            }
            add(statusLabel)
        }

        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(liveLabel)
            add(buildHeaderToolbar())
        }

        header.add(left, BorderLayout.WEST)
        header.add(right, BorderLayout.EAST)
        header.border = JBUI.Borders.emptyBottom(6)
        return header
    }

    private fun toggleResolved() {
        if (thread.status is ThreadStatus.Open) {
            // Capture the next step BEFORE resolving: the walk holds only open threads.
            val next = nextStepIfAutoAdvancing()
            thread.resolve(Authors.user)
            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
            if (next != null) {
                onClose()
                WalkthroughNavigator.navigateTo(project, next, from = editor)
            }
        } else {
            thread.reopen()
            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
        }
    }

    private fun buildHeaderToolbar(): JComponent {
        val firstStep = navAction("First Step", AllIcons.Actions.Play_first) { walk, i ->
            walk.firstOrNull().takeIf { i != 0 }
        }
        val previousStep = navAction("Previous Step", AllIcons.Actions.PreviousOccurence) { walk, i ->
            if (i != null && i > 0) walk[i - 1] else null
        }
        val nextStep = navAction("Next Step", AllIcons.Actions.NextOccurence) { walk, i ->
            if (i == null) walk.firstOrNull() else walk.getOrNull(i + 1)
        }
        val lastStep = navAction("Last Step", AllIcons.Actions.Play_last) { walk, i ->
            walk.lastOrNull().takeIf { i != walk.size - 1 }
        }
        // The served skill promises the occurrence shortcuts walk steps
        // without the tool window.
        val actionManager = ActionManager.getInstance()
        previousStep.registerCustomShortcutSet(
            actionManager.getAction(IdeActions.ACTION_PREVIOUS_OCCURENCE).shortcutSet, this,
        )
        nextStep.registerCustomShortcutSet(
            actionManager.getAction(IdeActions.ACTION_NEXT_OCCURENCE).shortcutSet, this,
        )
        val group = DefaultActionGroup(
            firstStep,
            previousStep,
            nextStep,
            lastStep,
            Separator.getInstance(),
            liveAction(),
            copyReferenceAction(),
            resolveAction(),
            deleteAction(),
            closeAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("MarginalisThreadPanel", group, true)
        toolbar.targetComponent = this
        toolbar.component.isOpaque = false
        return toolbar.component
    }

    private fun isDraft(): Boolean = MarginalisStore.getInstance(project).threads.byId(thread.id) == null

    private fun resolveAction(): AnAction = object : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = !isDraft()
            if (thread.status is ThreadStatus.Open) {
                e.presentation.text = "Resolve"
                e.presentation.icon = AllIcons.General.GreenCheckmark
            } else {
                e.presentation.text = "Reopen"
                e.presentation.icon = MarginalisIcons.LineMark
            }
        }

        override fun actionPerformed(e: AnActionEvent) = toggleResolved()
    }

    private fun liveAction(): AnAction = object : ToggleAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = MarginalisStore.getInstance(project).isLive(thread)

        override fun setSelected(e: AnActionEvent, state: Boolean) =
            MarginalisStore.getInstance(project).setLive(thread, state, addressee)

        override fun update(e: AnActionEvent) {
            super.update(e)
            refreshLive()
            e.presentation.icon = AllIcons.Actions.Lightning
            e.presentation.isVisible = !isDraft() && thread.status is ThreadStatus.Open
            e.presentation.isEnabled = isSelected(e) || liveAgentWaiting()
            val agent = liveAgentKey()?.let(::agentName) ?: "an agent"
            e.presentation.text = when {
                isSelected(e) -> "Live: each Submit wakes $agent with this thread"
                e.presentation.isEnabled -> "Go Live: each Submit wakes $agent with this thread"
                liveAgentKey() == null && handBack.waitingAgents.size > 1 -> "Several agents are listening — @ one to go live"
                else -> "No agent is listening — live needs $agent waiting"
            }
        }
    }

    private fun liveAgentKey(): String? = MarginalisStore.getInstance(project).liveAgentKey(thread, addressee)

    private fun liveAgentWaiting(): Boolean {
        val key = liveAgentKey() ?: return false
        return handBack.waitingAgents.any { it.receiptKey == key }
    }

    private fun agentName(key: String): String = agentNames[key] ?: key

    private fun refreshLive() {
        val key = liveAgentKey()
        val listening = liveAgentWaiting()
        val working = key != null && !listening &&
            LiveThread.isWorking(thread.messages, key, handBack.liveDeliveredAt(key, thread.id))
        liveLabel.text = when {
            !MarginalisStore.getInstance(project).isLive(thread) || key == null -> ""
            listening -> "${agentName(key)} is listening"
            working -> "${agentName(key)} is working…"
            else -> ""
        }
        liveLabel.isVisible = liveLabel.text.isNotEmpty()
        val pulsing = liveLabel.isVisible && working && watchingThread != null
        if (pulsing) {
            livePulse.start()
        } else {
            livePulse.stop()
            livePulseDim = false
            liveLabel.foreground = LIVE_COLOR
        }
    }

    private fun deleteAction(): AnAction = object : AnAction("Delete Thread", null, AllIcons.Actions.GC) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = !isDraft()
        }

        override fun actionPerformed(e: AnActionEvent) {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete this thread (${thread.messages.size} message(s))? " +
                    "Unlike resolving, deletion keeps no record. This cannot be undone." +
                    if (thread.isRelayedRoot) " $RELAYED_STAYS_DELETED" else "",
                "Delete Margin Thread",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return
            MarginalisStore.getInstance(project).threads.delete(thread.id)
            closeAndRefocus()
        }
    }

    private fun copyReferenceAction(): AnAction = object : AnAction("Copy Reference", null, AllIcons.Actions.Copy) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = !isDraft()
            e.presentation.description = "Copy ${Reference.of(thread.id)} to cite this thread"
        }

        override fun actionPerformed(e: AnActionEvent) = copyReference(thread.id)
    }

    private fun copyReference(id: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(Reference.of(id).toString()))
    }

    fun reveal(message: Message) {
        messageComponents[message.id]?.let { it.scrollRectToVisible(Rectangle(it.size)) }
        markReadIfSeen()
    }

    private fun closeAction(): AnAction = object : AnAction("Close", null, AllIcons.Actions.Close) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = closeAndRefocus()
    }

    private fun navAction(
        name: String,
        icon: Icon,
        target: (walk: List<CommentThread>, index: Int?) -> CommentThread?,
    ): AnAction = object : AnAction(name, null, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val (walk, i) = WalkthroughNavigator.walkFrom(project, thread)
            e.presentation.isEnabled = target(walk, i) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val (walk, i) = WalkthroughNavigator.walkFrom(project, thread)
            val destination = target(walk, i) ?: return
            onClose()
            WalkthroughNavigator.navigateTo(project, destination, from = editor)
        }
    }

    private fun buildReplyRow(): JComponent {
        sendButton.font = JBUI.Fonts.smallFont()
        cancelEditLink.font = JBUI.Fonts.smallFont()
        addresseeLink.font = JBUI.Fonts.smallFont()
        val quoteLink = ActionLink("") { quoteIntoReply() }.apply {
            icon = AllIcons.Actions.MenuPaste
            toolTipText = "Quote code: insert the editor selection (or this thread's anchor) as a code block"
        }
        composerHolder = object : JPanel(BorderLayout()) {
            override fun getPreferredSize(): Dimension {
                val computed = super.getPreferredSize()
                return Dimension(computed.width, computed.height.coerceAtLeast(JBUI.scale(52)))
            }
        }.apply {
            isOpaque = false
            add(replyArea, BorderLayout.CENTER)
        }
        composerActions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(10), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            add(addresseeLink)
            add(quoteLink)
            add(cancelEditLink)
            add(sendButton)
        }
        collapsedReply = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(ActionLink("Reply…") { focusReply() }.apply { font = JBUI.Fonts.smallFont() })
                    add(agreeLink)
                    add(
                        JBLabel("⌘⏎ submits").apply {
                            font = JBUI.Fonts.smallFont()
                            foreground = UIUtil.getContextHelpForeground()
                            border = JBUI.Borders.emptyLeft(8)
                        },
                    )
                },
                BorderLayout.WEST,
            )
        }
        setComposerExpanded(false)
        return replyRow
    }

    /** The inlay tracks the panel's preferred size, so revalidating is enough. */
    private fun setComposerExpanded(expanded: Boolean) {
        composerExpanded = expanded
        replyRow.removeAll()
        if (expanded) {
            replyRow.add(composerHolder, BorderLayout.CENTER)
            replyRow.add(composerActions, BorderLayout.SOUTH)
        } else {
            replyRow.add(collapsedReply, BorderLayout.CENTER)
        }
        replyRow.revalidate()
        replyRow.repaint()
    }

    private fun quoteIntoReply() {
        val quoted = editor?.selectionModel?.selectedText
            ?: thread.segment?.exact
            ?: thread.anchorText?.trim()
        if (quoted.isNullOrBlank()) return
        val lang = thread.file?.substringAfterLast('.', "") ?: ""
        val fence = "```$lang\n$quoted\n```\n"
        replyArea.text = when {
            replyArea.text.isBlank() -> fence
            replyArea.text.endsWith("\n") -> replyArea.text + fence
            else -> replyArea.text + "\n" + fence
        }
        focusReply()
    }

    private fun submit() {
        sendReply()?.let { MarginalisStore.getInstance(project).wakeLive(thread, it.to) }
    }

    private fun sendReply(): Message? {
        val body = replyArea.text.trim()
        if (body.isEmpty()) return null

        val editing = editingMessageId?.let { id -> thread.messages.find { it.id == id } }
        if (editing != null) {
            editingMessageId = null
            // An agent may have read the original mid-edit; once read, it is record.
            val applied = !editing.seenByAnyAgent
            if (applied) {
                editing.body = body
                // A Message can't bump its thread; without touch() a cursor sweep misses the revision.
                thread.touch()
            }
            replyArea.text = ""
            setComposerExpanded(false)
            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
            return editing.takeIf { applied }
        }

        ensureStored()
        val sent = Message(Authors.user, body, to = addressee)
        thread.addMessage(sent)
        addressTo(null)
        replyArea.text = ""
        setComposerExpanded(false)
        MarginalisStore.getInstance(project).drafts.remove(thread.id)
        markRead()
        MarginalisStore.getInstance(project).threads.notifyChanged(thread)
        return sent
    }

    private fun agree() {
        val agreed = thread.agreeable()?.takeIf { it.id == agreeableId }
        val agent = agreed?.author as? Author.Agent
        if (agent == null) {
            refresh()
            return
        }
        val agreement = Message.agreement(by = Authors.user, with = agent)
        markRead()
        thread.addMessage(agreement)
        MarginalisStore.getInstance(project).threads.notifyChanged(thread)
        MarginalisStore.getInstance(project).wakeLive(thread, agreement.to)
    }

    fun markReadIfSeen() {
        if (!mayMarkRead(false) || !isShowing || visibleRect.isEmpty || !ApplicationManager.getApplication().isActive) return
        if (thread.markReadByUser(renderedMessages)) MarginalisStore.getInstance(project).threads.notifyChanged(thread)
    }

    fun markRead() {
        if (!mayMarkRead(true) || isDraft() || !ApplicationManager.getApplication().isActive) return
        if (thread.markReadByUser(renderedMessages)) MarginalisStore.getInstance(project).threads.notifyChanged(thread)
    }

    private fun markReadIfSeenLater() {
        ApplicationManager.getApplication().invokeLater { markReadIfSeen() }
    }

    private fun submitWiderThan(file: String?) {
        val body = replyArea.text.trim()
        if (body.isEmpty()) return
        val store = MarginalisStore.getInstance(project)
        val wider = CommentThread(file, line = null, anchorText = null, segment = thread.segment)
        wider.addMessage(Message(Authors.user, body, to = addressee))
        replyArea.text = ""
        store.drafts.remove(thread.id)
        onClose()
        store.threads.add(wider)
        WalkthroughNavigator.navigateTo(project, wider)
    }

    private fun pickAddressee(atOffset: Int) {
        val composerEditor = replyArea.editor ?: return
        val agents = knownAgents()
        if (agents.isEmpty()) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(agents)
            .setTitle("Address To")
            .setNamerForFiltering { agent -> "${agent.name.orEmpty()} ${agent.id}" }
            .setRenderer(
                object : SimpleListCellRenderer<Identity.Agent>() {
                    override fun customize(
                        list: JList<out Identity.Agent>,
                        agent: Identity.Agent,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        text = agent.name?.let { "$it  ·  ${agent.id}" } ?: agent.id
                    }
                },
            )
            .setItemChosenCallback { agent ->
                val document = composerEditor.document
                val mentionEnd = maxOf(composerEditor.caretModel.offset, atOffset + 1)
                if (document.charsSequence.getOrNull(atOffset) == '@' && mentionEnd <= document.textLength) {
                    WriteCommandAction.runWriteCommandAction(project) { document.deleteString(atOffset, mentionEnd) }
                }
                addressTo(Addressee.Agent(agent.id))
                saveDraft()
            }
            .createPopup()
            .showInBestPositionFor(composerEditor)
    }

    private fun knownAgents(): List<Identity.Agent> =
        Identities.of(MarginalisStore.getInstance(project).threads.all(), Authors.user, handBack.waitingAgents)
            .filterIsInstance<Identity.Agent>()

    private fun refreshAgentNames() {
        agentNames = handBack.waitingAgents.associate { it.receiptKey to it.displayName } +
            Identities.namesByKey(MarginalisStore.getInstance(project).threads.all())
    }

    private fun addressTo(to: Addressee?) {
        addressee = to
        addresseeLink.isVisible = to != null && editingMessageId == null
        addresseeLink.text = to?.let { "@${addresseeName(it)} ✕" } ?: ""
        refreshLive()
    }

    private fun reloadDraft() {
        if (editingMessageId != null) return
        val saved = MarginalisStore.getInstance(project).drafts[thread.id]
        addressTo(saved?.to)
        if (replyArea.text != (saved?.text ?: "")) replyArea.text = saved?.text ?: ""
    }

    private fun saveDraft() {
        if (editingMessageId != null) return // edits restore the original on cancel, not a draft
        val drafts = MarginalisStore.getInstance(project).drafts
        val heldDraft = !drafts[thread.id]?.text.isNullOrBlank()
        val text = replyArea.text
        if (text.isBlank() && addressee == null) {
            drafts.remove(thread.id)
        } else {
            drafts[thread.id] = MarginalisStore.Draft(text, addressee)
        }
        if (heldDraft == text.isBlank()) onDraftPresenceChanged()
    }

    private fun addresseeName(to: Addressee): String = when (to) {
        Addressee.User -> Authors.user.displayName
        is Addressee.Agent -> agentNames[to.key] ?: to.key
    }

    fun focusReply() {
        if (!composerExpanded) setComposerExpanded(true)
        replyArea.requestFocusInWindow()
    }

    fun focusDefault() {
        if (isDraft() || replyArea.text.isNotBlank()) focusReply() else requestFocusInWindow()
        markReadIfSeen()
    }

    private fun nextStepIfAutoAdvancing(): CommentThread? {
        if (!MarginalisSettings.getInstance().state.walkthroughAutoAdvance) return null
        val (walk, i) = WalkthroughNavigator.walkFrom(project, thread)
        return i?.let { walk.getOrNull(it + 1) }
    }

    private fun messageTimeFormatter(): DateTimeFormatter =
        when (MarginalisSettings.getInstance().timeFormat) {
            TimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a")
            TimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm")
            TimeFormat.AUTO -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        }.withZone(ZoneId.systemDefault())

    /** Ordered steps use the stable total so they agree with the tool window's (n/total) as steps resolve. */
    private fun walkPosition(): String {
        val order = thread.order
        if (order != null) {
            val total = WalkthroughNavigator.stableTotal(project, thread) ?: return ""
            return " · step $order/$total"
        }
        val (walk, i) = WalkthroughNavigator.walkFrom(project, thread)
        return if (i != null && walk.size > 1) " · step ${i + 1}/${walk.size}" else ""
    }

    private fun refreshSendOptions() {
        sendButton.options = SendOption.offered(
            thread,
            isDraft = isDraft(),
            isEditing = editingMessageId != null,
            anyoneListening = handBack.canSubmitRound,
        ).map {
            when (it) {
                SendOption.SEND_ROUND -> submitAndSendRoundAction
                SendOption.COMMENT_ON_FILE -> commentOnFileAction
                SendOption.COMMENT_ON_PROJECT -> commentOnProjectAction
            }
        }.toTypedArray()
    }

    private val onWaitersChanged: () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                refreshSendOptions()
                refreshLive()
            }
        }
    }

    private var watchingThread: AutoCloseable? = null
    private val threadUpdate = CoalescedEdtRunner(project) {
        if (watchingThread == null) return@CoalescedEdtRunner
        if (MarginalisStore.getInstance(project).threads.byId(thread.id) == null) {
            onClose()
        } else {
            refresh()
            markReadIfSeen()
        }
    }

    override fun addNotify() {
        super.addNotify()
        handBack.addListener(onWaitersChanged)
        watchingThread = MarginalisStore.getInstance(project).threads.watch(thread.id) { threadUpdate.request() }
        refreshSendOptions()
        refreshLive()
        val attached = Disposer.newDisposable(MarginalisStore.getInstance(project), "Marginalis thread panel")
        whileAttached = attached
        ApplicationManager.getApplication().messageBus.connect(attached).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = markReadIfSeenLater()
            },
        )
        ApplicationManager.getApplication().messageBus.connect(attached)
            .subscribe(AvatarsListener.TOPIC, AvatarsListener { repaint() })
        editor?.scrollingModel?.addVisibleAreaListener({ markReadIfSeen() }, attached)
        markReadIfSeenLater()
    }

    override fun removeNotify() {
        whileAttached?.let(Disposer::dispose)
        whileAttached = null
        livePulse.stop()
        handBack.removeListener(onWaitersChanged)
        watchingThread?.close()
        watchingThread = null
        super.removeNotify()
    }

    fun refresh() {
        statusLabel.text = when {
            isDraft() -> "new comment — unsent"
            thread.status is ThreadStatus.Open -> "open${walkPosition()}"
            thread.status is ThreadStatus.Resolved -> "resolved by ${thread.resolvedBy?.displayName ?: "?"}"
            else -> "orphaned (anchor deleted)"
        }
        // "Submit", not "Send": nothing is transmitted — the message waits in
        // the local store for the agent's next read.
        submitAction.putValue(
            Action.NAME,
            when {
                editingMessageId != null -> "Save"
                thread.messages.isEmpty() -> "Submit"
                else -> "Reply"
            },
        )
        refreshSendOptions()
        cancelEditLink.isVisible = editingMessageId != null
        val agreeable = thread.agreeable()
        agreeableId = agreeable?.id
        agreeLink.isVisible = agreeable != null && !isDraft()
        agreeLink.toolTipText = agreeable?.let { "Agree with ${it.author.displayName}: replies \"Agreed.\" and passes the turn" }
        refreshAgentNames()
        addressTo(addressee)
        replyArea.setPlaceholder(
            when {
                thread.messages.isNotEmpty() -> "Reply… (⌘⏎ to submit)"
                thread.isProjectLevel -> "Comment on this project… (⌘⏎ to submit)"
                thread.isFileLevel -> "Comment on this file… (⌘⏎ to submit)"
                else -> "Comment on this line… (⌘⏎ to submit)"
            },
        )

        messagesBox.removeAll()
        messageComponents.clear()
        val timeFormat = messageTimeFormatter()
        val people = MarginalisSettings.getInstance().people
        val faces = Authors.facesOf(people)
        var previous: Message? = null
        var githubBlock: JPanel? = null
        renderedMessages = thread.messages
        val tagged = newTags(thread)
        showsNewTags = renderedMessages.any { it.id in tagged }
        for (message in renderedMessages) {
            val grouped = message.continues(previous)
            val relayed = message.relayed
            if (relayed == null || relayed.discussion != previous?.relayed?.discussion) {
                githubBlock = null
            } else if (githubBlock != null) {
                githubBlock.add(Box.createVerticalStrut(JBUI.scale(8)))
            }
            if (githubBlock == null && messagesBox.componentCount > 0) {
                messagesBox.add(Box.createVerticalStrut(JBUI.scale(if (grouped) 2 else 8)))
            }
            if (relayed != null && githubBlock == null) {
                githubBlock = githubBlock(relayed).also { messagesBox.add(it) }
            }
            val component = messageComponent(
                message, timeFormat, people, faces, showMeta = message.showsAvatar(previous), isNew = message.id in tagged,
            )
            messageComponents[message.id] = component
            (githubBlock ?: messagesBox).add(component)
            previous = message
        }
        revalidate()
        repaint()
    }

    private fun githubBlock(first: Relayed): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(
            MarginalisPalette.githubDashedBorder(),
            JBUI.Borders.empty(4, 6, 6, 6),
        )
        val title = first.discussion?.let { "From GitHub · $it" } ?: "From GitHub"
        val heading = JBLabel(title, AllIcons.Vcs.Vendors.Github, SwingConstants.LEADING).apply {
            font = JBUI.Fonts.miniFont()
            foreground = UIUtil.getContextHelpForeground()
        }
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(4)
                add(heading, BorderLayout.WEST)
            },
        )
    }

    private fun agreementLine(message: Message, timeFormat: DateTimeFormatter): JComponent {
        val line = JBLabel(
            "${message.author.displayName} agreed · ${timeFormat.format(message.createdAt)}",
            MarginalisIcons.Agree,
            SwingConstants.LEADING,
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = AuthorColors.USER
            if (message.seenByAnyAgent) toolTipText = seenByNames(message)
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(9)
            add(line, BorderLayout.WEST)
        }
    }

    private fun gapAfterAvatar(): Int =
        (JBUI.scale(6) - AvatarGeometries.current().trailingPadding).roundToInt().coerceAtLeast(0)

    private fun messageComponent(
        message: Message,
        timeFormat: DateTimeFormatter,
        people: People,
        faces: Faces,
        showMeta: Boolean,
        isNew: Boolean,
    ): JComponent {
        if (message.agrees) return agreementLine(message, timeFormat)
        val relayed = message.relayed
        val face = faces.of(message)
        val relayedByUser = relayed != null && face.key == FaceKey.User
        val authorColor = AuthorColors.of(face.key)
        val author = people.displayNameOf(message.author)
        val byline = if (relayed == null) author else "${face.name} · via $author"
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = isNew
            background = NEW_TINT
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(authorColor, 0, 2, 0, 0),
                JBUI.Borders.emptyLeft(7),
            )
        }
        val metaRow = JPanel(BorderLayout()).apply { isOpaque = false }
        if (showMeta) {
            val meta = JBLabel("$byline · ${timeFormat.format(message.createdAt)}").apply {
                font = JBUI.Fonts.smallFont().asBold()
                foreground = authorColor
                if (relayed != null) toolTipText = "@${relayed.login} on GitHub"
            }
            val who = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(
                    JBLabel(AvatarIcon(face, UIUtil::getPanelBackground)).apply {
                        alignmentY = AvatarGeometries.current().tileCentreShare.toFloat()
                    },
                )
                add(Box.createHorizontalStrut(gapAfterAvatar()))
                add(meta)
                val chip = when {
                    relayedByUser -> "on GitHub"
                    relayed?.bot == true -> "bot"
                    else -> null
                }
                chip?.let {
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(Chip(it, QUIET_PILL, authorColor))
                }
                message.to?.let { to ->
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(Chip("@${addresseeName(to)}", QUIET_PILL, AuthorColors.of(to)))
                }
            }
            metaRow.add(who, BorderLayout.WEST)
        } else {
            panel.toolTipText = "$byline · ${timeFormat.format(message.createdAt)}"
        }

        val trailing = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply { isOpaque = false }
        if (isNew) trailing.add(Chip("new", MarginalisPalette.ACCENT, NEW_TEXT))
        if (message.author is Author.User && !message.seenByAnyAgent && editingMessageId == null) {
            val editLink = ActionLink("Edit") {
                editingMessageId = message.id
                replyArea.text = message.body
                refresh()
                focusReply()
            }
            editLink.font = JBUI.Fonts.smallFont()
            trailing.add(editLink)
        } else if (editingMessageId == message.id) {
            metaRow.add(
                JBLabel("editing below ↓").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = UIUtil.getContextHelpForeground()
                },
                BorderLayout.EAST,
            )
            panel.add(metaRow, BorderLayout.NORTH)
            return panel
        } else if (message.author is Author.User && message.seenByAnyAgent) {
            trailing.add(
                JBLabel("✓ seen").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor(Color(0x2E, 0x7D, 0x32), Color(0xA5, 0xD6, 0xA7))
                    toolTipText = seenByNames(message)
                },
            )
        }
        relayed?.takeIf { WebLink.isBrowsable(it.url) }?.let { source ->
            trailing.add(
                ActionLink("↗") { if (WebLink.isBrowsable(source.url)) BrowserUtil.browse(source.url) }.apply {
                    font = JBUI.Fonts.smallFont()
                    toolTipText = "Open on GitHub: ${source.url}"
                },
            )
        }
        trailing.add(
            ActionLink("") { copyReference(message.id) }.apply {
                icon = AllIcons.Actions.Copy
                toolTipText = "Copy reference: ${Reference.of(message.id)}"
            },
        )
        metaRow.add(trailing, BorderLayout.EAST)
        // A conservative width, so heights only overestimate, never clip.
        val body = MarkdownRenderer.render(project, message.body, panelWidth() - JBUI.scale(if (relayed == null) 64 else 80))
        panel.add(metaRow, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
        return panel
    }

    private fun seenByNames(message: Message): String {
        return "Seen by ${message.seenBy.sorted().joinToString(", ") { agentNames[it] ?: it }}"
    }

    private class Chip(text: String, private val pill: JBColor, textColor: JBColor) : JBLabel(text) {

        init {
            font = JBUI.Fonts.miniFont().asBold()
            foreground = textColor
            border = JBUI.Borders.empty(1, 7)
            isOpaque = false
            maximumSize = preferredSize
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = pill
            g2.fillRoundRect(0, 0, width, height, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }

    private companion object {
        val QUIET_PILL = JBColor(Color(0xE1, 0xE9, 0xF4), Color(0x36, 0x3E, 0x4B))
        val FYI_PILL = JBColor(Color(0x8A, 0x94, 0xA6, 0x4D), Color(0x8A, 0x94, 0xA6, 0x59))
        val FYI_TEXT = JBColor(Color(0x46, 0x53, 0x6A), Color(0xB9, 0xC4, 0xD8))

        fun severityPill(severity: Severity): JBColor = when (severity) {
            Severity.BLOCKER -> JBColor(Color(0xDB, 0x58, 0x60), Color(0xC7, 0x54, 0x50))
            Severity.NIT -> JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x4E, 0x51, 0x57))
        }

        fun severityText(severity: Severity): JBColor = when (severity) {
            Severity.BLOCKER -> JBColor(Color.WHITE, Color(0xF5, 0xE3, 0xE3))
            Severity.NIT -> JBColor(Color(0x59, 0x59, 0x59), Color(0xBD, 0xBD, 0xBD))
        }

        val NEW_TINT = JBColor(Color(0xEE, 0xF3, 0xFE), Color(0x2B, 0x32, 0x40))
        val NEW_TEXT = JBColor(Color.WHITE, Color.WHITE)

        val LIVE_COLOR = JBColor(0x2E7D32, 0xA5D6A7)
        const val LIVE_PULSE_MILLIS = 600
    }

}
