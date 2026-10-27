package dev.marginalis.plugin.testing

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.popup.BalloonBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorTextField
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Identity
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.AvatarIcon
import dev.marginalis.plugin.ui.FoldedMessageRow
import dev.marginalis.plugin.ui.StopAgentsGroup
import dev.marginalis.plugin.ui.SubmitRoundAction
import dev.marginalis.plugin.ui.ThreadGutterIconRenderer
import dev.marginalis.plugin.ui.ThreadInlayManager
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.WalkthroughNavigator
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractButton
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener
import javax.swing.plaf.basic.BasicHTML

class MarginUser(private val project: Project, private val disposable: Disposable) {

    private val store get() = MarginalisStore.getInstance(project)

    fun openToolWindow(): MarginToolWindow = MarginToolWindow(project, disposable)

    fun toolWindowStripe(): MarginToolWindow.Window = MarginToolWindow.stripe(project, disposable)

    fun openMarginTab(): MarginTab = MarginTab(project, disposable).open()

    fun changeLongThreadSettings(edit: LongThreadSettings.() -> Unit) = LongThreadSettings.change(edit)

    fun changeProjectTabSettings(edit: ProjectTabSettings.() -> Unit) {
        ProjectTabSettings.change(edit)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun open(threadId: String): ThreadPanel {
        ThreadInlayManager.disposeAll()
        WalkthroughNavigator.navigateTo(project, thread(threadId))
        return panels().single()
    }

    fun commentOnSelection(file: String, line: Int, selected: String, body: String): String {
        comment(file, line, body, selecting = selected)
        return store.threads.all().last().id
    }

    fun read(threadId: String) {
        open(threadId).markRead()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun reply(threadId: String, body: String, to: String? = null) {
        val panel = open(threadId)
        if (to != null) mention(panel, to)
        type(panel, body)
        submit(panel)
    }

    fun addresseeShownWhileMentioning(threadId: String, agentId: String): String {
        val panel = open(threadId)
        mention(panel, agentId)
        return UIUtil.findComponentsOfType(panel, ActionLink::class.java).single { it.isVisible && it.text.startsWith("@") }.text
    }

    fun mention(panel: ThreadPanel, agentId: String) {
        val composer = composer(panel)
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun doIntercept(popup: JBPopup) {
                val agents = UIUtil.findComponentOfType(popup.content, JList::class.java)!!
                agents.selectedIndex = (0 until agents.model.size).single { (agents.model.getElementAt(it) as Identity.Agent).id == agentId }
                pressEnter(agents)
            }
        })
        WriteCommandAction.runWriteCommandAction(project) { composer.document.insertString(0, "@") }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun replyAndSendRound(threadId: String, body: String) {
        val panel = open(threadId)
        type(panel, body)
        sendInstead(panel, "Submit & send round")
    }

    fun canEdit(threadId: String): Boolean = visibleLinks(open(threadId)).contains("Edit")

    fun edit(threadId: String, body: String, beforeSaving: () -> Unit = {}): EditorTextField {
        val panel = open(threadId)
        clickLink(panel, "Edit")
        val composer = composer(panel)
        composer.text = body
        beforeSaving()
        submit(panel)
        return composer
    }

    fun isLive(threadId: String): Boolean {
        val live = toolbarActions(open(threadId)).filterIsInstance<ToggleAction>().single()
        return live.isSelected(event(live))
    }

    fun agree(threadId: String) = clickLink(open(threadId), "Agree")

    fun canAgree(threadId: String): Boolean = visibleLinks(open(threadId)).contains("Agree")

    fun delete(threadId: String) {
        pressDelete(open(threadId), confirm = true)
    }

    fun canGoLive(threadId: String): Boolean {
        val live = toolbarActions(open(threadId)).filterIsInstance<ToggleAction>().single()
        val event = event(live)
        live.update(event)
        return event.presentation.isEnabled
    }

    fun goLive(threadId: String) = setLive(threadId, true)

    fun stopLive(threadId: String) = setLive(threadId, false)

    private fun setLive(threadId: String, live: Boolean) {
        val panel = open(threadId)
        val toggle = toolbarActions(panel).filterIsInstance<ToggleAction>().single()
        if (toggle.isSelected(event(toggle)) != live) perform(toggle)
    }

    fun resolve(threadId: String) = press(open(threadId), "Resolve")

    fun reopen(threadId: String) = press(open(threadId), "Reopen")

    fun goProjectLive() = openToolWindow().turnProjectLive(true)

    fun stopProjectLive() = openToolWindow().turnProjectLive(false)

    fun isProjectLive(): Boolean = openToolWindow().isProjectLive()

    fun submitRound() {
        val action = SubmitRoundAction()
        action.actionPerformed(event(action))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun canSubmitRound(): Boolean {
        val action = SubmitRoundAction()
        val event = event(action)
        action.update(event)
        return event.presentation.isEnabled
    }

    fun stop(agentName: String) = openToolWindow().stop(agentName)

    fun stoppableAgents(): List<String> {
        val group = StopAgentsGroup()
        return group.getChildren(event(group)).mapNotNull { it.templatePresentation.text }.filter { it.startsWith("Stop ") && it != "Stop all agents" }
            .map { it.removePrefix("Stop ") }
    }

    fun thread(threadId: String): CommentThread = store.threads.byId(threadId)!!

    fun editor(): Editor = FileEditorManager.getInstance(project).selectedTextEditor!!

    fun openFile(file: String, line: Int = 1): Editor {
        val vFile = project.guessProjectDir()!!.findFileByRelativePath(file)!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vFile, line - 1, 0), true)!!
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return editor
    }

    fun panels(editor: Editor = editor()): List<ThreadPanel> {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return UIUtil.findComponentsOfType(editor.contentComponent, ThreadPanel::class.java).onEach(::onScreen)
    }

    fun panel(editor: Editor = editor()): ThreadPanel = panels(editor).single()

    fun whileInABackgroundTab(panel: ThreadPanel, editor: Editor = editor(), meanwhile: () -> Unit) {
        panel.removeNotify()
        meanwhile()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        if (SwingUtilities.isDescendingFrom(panel, editor.contentComponent)) panel.addNotify()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun gutterIcon(file: String, line: Int): ThreadGutterIconRenderer? = gutterIconsOnLine(file, line).firstOrNull()

    fun gutterIconsOnLine(file: String, line: Int): List<ThreadGutterIconRenderer> {
        val document = openFile(file, line).document
        val markup = DocumentMarkupModel.forDocument(document, project, false) ?: return emptyList()
        return markup.allHighlighters
            .filter { it.isValid && document.getLineNumber(it.startOffset) == line - 1 }
            .mapNotNull { it.gutterIconRenderer as? ThreadGutterIconRenderer }
    }

    fun closePanels() {
        ThreadInlayManager.disposeAll()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun clickGutter(file: String, line: Int) {
        val click = gutterIcon(file, line)!!.clickAction
        click.actionPerformed(TestActionEvent.createTestEvent(click, editorContext(editor())))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun choose(offered: MutableList<String>, pick: String) {
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun doIntercept(popup: JBPopup) {
                val list = UIUtil.findComponentOfType(popup.content, JList::class.java)!!
                val texts = rowTexts(list)
                offered += texts
                list.selectedIndex = texts.indexOfFirst { it.contains(pick) }
                pressEnter(list)
            }
        })
    }

    private fun <T> rowTexts(list: JList<T>): List<String> = (0 until list.model.size).map { index ->
        (list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(index), index, false, false) as JLabel).text
    }

    private fun pressEnter(list: JList<*>) {
        list.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
            .actionPerformed(ActionEvent(list, ActionEvent.ACTION_PERFORMED, "enter"))
    }

    fun comment(file: String, line: Int, body: String? = null, action: String = "marginalis.addComment", selecting: String? = null): Editor {
        val editor = openFile(file, line)
        selecting?.let { selected ->
            val lineStart = editor.document.getLineStartOffset(line - 1)
            val start = lineStart + editor.document.charsSequence.subSequence(lineStart, editor.document.textLength).indexOf(selected)
            editor.selectionModel.setSelection(start, start + selected.length)
        }
        invoke(action, editor)
        if (body != null) {
            val panel = panels(editor).last()
            type(panel, body)
            submit(panel)
        }
        return editor
    }

    fun isOffered(action: String, editor: Editor?): Boolean {
        val anAction = ActionManager.getInstance().getAction(action)
        val context = editor?.let(::editorContext) ?: SimpleDataContext.getProjectContext(project)
        val event = TestActionEvent.createTestEvent(anAction, context)
        anAction.update(event)
        return event.presentation.isEnabledAndVisible
    }

    fun invoke(action: String, editor: Editor) {
        val anAction = ActionManager.getInstance().getAction(action)
        anAction.actionPerformed(TestActionEvent.createTestEvent(anAction, editorContext(editor)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun editorContext(editor: Editor): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, editor)
        .build()

    fun type(panel: ThreadPanel, text: String) {
        composer(panel).text = text
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun writeIn(panel: ThreadPanel, text: String, caretAt: Int = text.length): EditorTextField {
        type(panel, text)
        val composer = composer(panel)
        composer.editor!!.caretModel.moveToOffset(caretAt)
        focusOn(composer.editor!!.contentComponent)
        return composer
    }

    fun lastFocusedInWindowOf(component: Component): Component? = SwingUtilities.getWindowAncestor(component)?.mostRecentFocusOwner

    private var focusMoved = false

    fun focusOn(component: Component) {
        check(component.isFocusable) { "$component can't take focus" }
        if (!focusMoved) {
            focusMoved = true
            Disposer.register(disposable) { movePermanentFocusTo(null) }
        }
        movePermanentFocusTo(component)
    }

    @AllowReflection("A test frame is never shown, so AWT never moves focus into it, and no public API moves it there")
    private fun movePermanentFocusTo(component: Component?) {
        KeyboardFocusManager::class.java.getDeclaredMethod("setGlobalPermanentFocusOwner", Component::class.java)
            .apply { isAccessible = true }
            .invoke(KeyboardFocusManager.getCurrentKeyboardFocusManager(), component)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun fireComposerFocusGained(panel: ThreadPanel) {
        val composer = composer(panel)
        composer.focusGained(FocusEvent(composer, FocusEvent.FOCUS_GAINED))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private var productionData = false

    fun dataContextOf(component: Component): DataContext {
        if (!productionData) {
            HeadlessDataManager.fallbackToProductionDataManager(disposable)
            productionData = true
        }
        return DataManager.getInstance().getDataContext(component)
    }

    fun replyBoxContext(panel: ThreadPanel): DataContext = dataContextOf(composer(panel).editor!!.contentComponent)

    fun composer(panel: ThreadPanel): EditorTextField {
        panel.focusReply()
        return UIUtil.findComponentsOfType(panel, EditorTextField::class.java).last()
    }

    fun submit(panel: ThreadPanel) {
        sendButton(panel).action.actionPerformed(null)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun submitLabel(panel: ThreadPanel): String = sendButton(panel).action.getValue(Action.NAME) as String

    fun sendOptions(panel: ThreadPanel): List<String> = sendButton(panel).options.orEmpty().map(::shownText)

    fun sendInstead(panel: ThreadPanel, option: String) {
        sendButton(panel).options!!.single { shownText(it) == option }.actionPerformed(null)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /**
     * An option as the send button's popup shows it: through a [Presentation], which reads a `&` in the name as a
     * mnemonic marker. A headless button has no UI to open the popup itself.
     */
    private fun shownText(option: Action): String = Presentation().apply { text = option.getValue(Action.NAME) as String }.text

    private fun sendButton(panel: ThreadPanel): JBOptionButton {
        panel.focusReply()
        return UIUtil.findComponentOfType(panel, JBOptionButton::class.java)!!
    }

    fun visibleLinks(panel: ThreadPanel): List<String> =
        UIUtil.findComponentsOfType(panel, ActionLink::class.java).filter { it.isVisible && it.text.isNotEmpty() }.map { it.text }

    fun clickLink(panel: ThreadPanel, text: String) {
        UIUtil.findComponentsOfType(panel, ActionLink::class.java).single { it.isVisible && it.text == text }.doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun clickLinkWithTooltip(panel: ThreadPanel, tooltip: String) {
        UIUtil.findComponentsOfType(panel, ActionLink::class.java).first { it.isVisible && it.toolTipText?.startsWith(tooltip) == true }.doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun followLink(panel: ThreadPanel, href: String) {
        val pane = UIUtil.findComponentsOfType(panel, JEditorPane::class.java).first { it.text.contains("href=\"$href\"") }
        val url = runCatching { java.net.URI(href).toURL() }.getOrNull()
        pane.fireHyperlinkUpdate(HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, url, href))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun fold(panel: ThreadPanel, text: String) = clickFoldToggle(panel, "Fold Message", text)

    fun expand(panel: ThreadPanel, text: String) = clickFoldToggle(panel, "Expand Message", text)

    fun foldToggles(panel: ThreadPanel): List<String> =
        foldTogglesIn(panel).map { it.accessibleContext.accessibleName }

    fun foldedRows(panel: ThreadPanel): List<String> = foldedRowsIn(panel).map { it.openingLine }

    fun foldedRow(panel: ThreadPanel, text: String): JComponent = foldedRowsIn(panel).single { text in it.openingLine }

    fun foldedRowsShownAsMarkup(panel: ThreadPanel): List<String> = foldedRowsIn(panel)
        .flatMap { UIUtil.findComponentsOfType(it, JLabel::class.java) }
        .filter { it.getClientProperty(BasicHTML.propertyKey) != null }
        .map { it.text }

    fun messagesWithAFoldToggle(panel: ThreadPanel): List<String> = foldTogglesIn(panel).map(::messageAround)

    fun clickFoldedRow(panel: ThreadPanel, text: String, on: String = text) {
        val row = foldedRowsIn(panel).single { text in it.openingLine }
        val label = UIUtil.findComponentsOfType(row, JLabel::class.java).first { it.text != null && on in it.text }
        label.dispatchEvent(MouseEvent(label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun foldedRowsIn(panel: ThreadPanel): List<FoldedMessageRow> =
        UIUtil.findComponentsOfType(panel, FoldedMessageRow::class.java).filter { it.isVisible }

    private fun foldTogglesIn(panel: ThreadPanel): List<ActionLink> = UIUtil.findComponentsOfType(panel, ActionLink::class.java)
        .filter { it.isVisible && it.toolTipText in setOf("Fold Message", "Expand Message") }

    fun foldToggle(panel: ThreadPanel, text: String): ActionLink = foldTogglesIn(panel).single { text in messageAround(it) }

    private fun clickFoldToggle(panel: ThreadPanel, name: String, text: String) {
        foldToggle(panel, text).also { check(it.toolTipText == name) { "'$text' offers ${it.toolTipText}" } }.doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun messageAround(toggle: Component): String {
        val holder = generateSequence(toggle.parent) { it.parent }
            .filterIsInstance<JComponent>()
            .first { it is FoldedMessageRow || UIUtil.findComponentsOfType(it, JEditorPane::class.java).isNotEmpty() }
        return if (holder is FoldedMessageRow) holder.openingLine
        else UIUtil.findComponentsOfType(holder, JEditorPane::class.java).joinToString("\n") { it.document.getText(0, it.document.length) }
    }

    fun messageBody(panel: ThreadPanel, text: String): JEditorPane =
        UIUtil.findComponentsOfType(panel, JEditorPane::class.java).single { it.document.getText(0, it.document.length).contains(text) }

    fun hasLink(panel: ThreadPanel, href: String): Boolean =
        UIUtil.findComponentsOfType(panel, JEditorPane::class.java).any { it.text.contains("href=\"$href\"") }

    fun shownText(panel: ThreadPanel): String = UIUtil.uiTraverser(panel)
        .filter { component -> generateSequence(component) { it.parent }.takeWhile { it !== panel.parent }.all { it.isVisible } }
        .mapNotNull { component ->
            when (component) {
                is JEditorPane -> component.document.getText(0, component.document.length).trim()
                is EditorTextField -> component.text
                is JLabel -> component.text
                is AbstractButton -> component.text
                else -> null
            }
        }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    fun avatars(panel: ThreadPanel): List<AvatarIcon> =
        UIUtil.findComponentsOfType(panel, JLabel::class.java).mapNotNull { it.icon as? AvatarIcon }

    fun tooltips(panel: ThreadPanel): List<String> =
        UIUtil.uiTraverser(panel).filter(JComponent::class.java).mapNotNull { it.toolTipText }.toList()

    fun toolbar(panel: ThreadPanel): Map<String, Presentation> = toolbarActions(panel)
        .filter { it !is Separator }
        .associate { action ->
            val event = event(action)
            action.update(event)
            (event.presentation.text ?: action.templatePresentation.text ?: "") to event.presentation
        }

    fun press(panel: ThreadPanel, text: String) {
        perform(toolbarActions(panel).single { action ->
            val event = event(action)
            action.update(event)
            event.presentation.text == text || action.templatePresentation.text == text
        })
    }

    private fun perform(action: AnAction) {
        val event = event(action)
        action.update(event)
        check(event.presentation.isEnabled) { "${event.presentation.text ?: action.templatePresentation.text} is greyed out" }
        if (action is ToggleAction) action.setSelected(event, !action.isSelected(event)) else action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun pressDelete(panel: ThreadPanel, confirm: Boolean): String {
        var asked = ""
        TestDialogManager.setTestDialog(TestDialog { message ->
            asked = message
            if (confirm) Messages.YES else Messages.NO
        })
        try {
            press(panel, "Delete Thread")
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        return asked
    }

    fun warningsWhile(act: () -> Unit): List<String> {
        val shown = mutableListOf<String>()
        val recording = object : PopupFactoryImpl() {
            override fun createHtmlTextBalloonBuilder(htmlContent: String, messageType: MessageType, listener: HyperlinkListener?): BalloonBuilder {
                shown += htmlContent
                return super.createHtmlTextBalloonBuilder(htmlContent, messageType, listener)
            }
        }
        val scope = Disposer.newDisposable("recording warnings")
        ApplicationManager.getApplication().replaceService(JBPopupFactory::class.java, recording, scope)
        try {
            act()
        } finally {
            Disposer.dispose(scope)
        }
        return shown
    }

    private fun onScreen(panel: ThreadPanel) {
        if (panel.isDisplayable) return
        Registry.get("editor.text.field.init.on.shown").setValue(false, disposable)
        panel.addNotify()
        panel.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L && panel.parent == null && panel.isDisplayable) {
                panel.removeNotify()
            }
        }
        Disposer.register(disposable) { if (panel.isDisplayable) panel.removeNotify() }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun pressKeyInComposer(panel: ThreadPanel, keyCode: Int, modifiers: Int = 0) {
        val content = composer(panel).editor!!.contentComponent
        val press = KeyEvent(content, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(content, press)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun pressShortcut(panel: ThreadPanel, ideAction: String) {
        val shortcuts = ActionManager.getInstance().getAction(ideAction).shortcutSet.shortcuts
        performIfEnabled(ActionUtil.getActions(panel).single { it.shortcutSet.shortcuts.contentEquals(shortcuts) })
    }

    fun pressEscape(panel: ThreadPanel) {
        panel.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))!!
            .actionPerformed(ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "escape"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun pressSubmitShortcut(panel: ThreadPanel, keyStroke: String = "meta ENTER") {
        val pressed = KeyboardShortcut(KeyStroke.getKeyStroke(keyStroke), null)
        performIfEnabled(ActionUtil.getActions(composer(panel)).single { action -> action.shortcutSet.shortcuts.any { it == pressed } })
    }

    private fun performIfEnabled(action: AnAction) {
        val event = event(action)
        action.update(event)
        if (event.presentation.isEnabled) action.actionPerformed(event)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    /** A header action updating on the EDT waits for it, so a busy EDT stalls the whole toolbar. */
    fun headerActionsUpdatingOnTheUiThread(panel: ThreadPanel): List<String> = toolbarActions(panel)
        .filter { it !is Separator && it.actionUpdateThread == ActionUpdateThread.EDT }
        .map { it.templatePresentation.text ?: it.javaClass.name }

    private fun toolbarActions(panel: ThreadPanel): List<AnAction> {
        val toolbar = UIUtil.uiTraverser(panel).filter(ActionToolbar::class.java).first()!!
        return toolbar.actionGroup.getChildren(null).toList()
    }

    private fun event(action: AnAction) =
        TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
}
