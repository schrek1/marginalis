package dev.marginalis.plugin.testing

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.platform.util.coroutines.childScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.IconUtil
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.Turn
import dev.marginalis.core.TurnSignal
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.tab.ConversationCard
import dev.marginalis.plugin.ui.tab.MarginEditor
import dev.marginalis.plugin.ui.tab.MarginFile
import dev.marginalis.plugin.ui.tab.ProjectTab
import dev.marginalis.plugin.ui.tab.ThreadCard
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Color
import java.awt.Container
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.beans.PropertyChangeEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class MarginTab(private val project: Project, private val disposable: Disposable) {

    private class Screen : JFrame() {
        var active = false

        init {
            rootPane.glassPane = IdeGlassPaneImpl(rootPane)
        }

        override fun isActive(): Boolean = active

        override fun isShowing(): Boolean = true

        override fun getGraphics(): Graphics = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).createGraphics()
    }

    val editors: FileEditorManagerImpl

    private val screen: Screen? = if (GraphicsEnvironment.isHeadless()) null else Screen()

    init {
        Disposer.register(disposable) { screen?.dispose() }
        val light = FileEditorManager.getInstance(project)
        light.openFiles.forEach(light::closeFile)
        project.putUserData(FileEditorManagerKeys.ALLOW_IN_LIGHT_PROJECT, true)
        editors = FileEditorManagerImpl(project, (project as ComponentManagerEx).getCoroutineScope().childScope("Margin tab under test"))
        project.replaceService(FileEditorManager::class.java, editors, disposable)
        startTabTitleAndIconUpdates()
        Disposer.register(disposable) {
            editors.closeAllFiles()
            EditorHistoryManager.getInstance(project).removeAllFiles()
            Disposer.dispose(editors)
        }
        Registry.get("editor.text.field.init.on.shown").setValue(false, disposable)
        screen?.apply {
            contentPane.add(editors.mainSplitters)
            setSize(1200, 900)
            addNotify()
            rootPane.setBounds(0, 0, 1200, 900)
        }
        settle()
    }

    @AllowReflection("A headless IDE never starts the editor manager's tab title and icon updates, and no public API starts them")
    private fun startTabTitleAndIconUpdates() {
        FileEditorManagerImpl::class.java.getDeclaredMethod("processFileUpdateRequests").apply { isAccessible = true }.invoke(editors)
    }

    val file: VirtualFile get() = MarginFile.of(project)

    fun open(): MarginTab {
        editors.openFile(file, true, true)
        settle()
        return this
    }

    fun openOnStartup(): MarginTab {
        ProjectTab.openOnStartup(project)
        settle()
        return this
    }

    fun pin() {
        editors.windows.single().setFilePinned(file, true)
        settle()
    }

    fun restore(address: String, pinned: Boolean = false): VirtualFile {
        val restored = VirtualFileManager.getInstance().findFileByUrl(address)!!
        editors.openFile(restored, false, true)
        if (pinned) editors.windows.single().setFilePinned(restored, true)
        settle()
        return restored
    }

    fun restoreInASplit(address: String, pinned: Boolean = false): VirtualFile {
        val restored = VirtualFileManager.getInstance().findFileByUrl(address)!!
        val half = editors.windows.single().split(SwingConstants.VERTICAL, true, restored, false)!!
        if (pinned) half.setFilePinned(restored, true)
        settle()
        return restored
    }

    fun focusTheHalfShowing(shown: VirtualFile) {
        editors.windows.single { half -> half.fileList.any { it === shown } }.setAsCurrentWindow(true)
        settle()
    }

    fun marginTabsInEachHalf(): List<Int> = editors.windows.map { half -> half.fileList.count { it is MarginFile } }

    fun selectTheTabInEachHalf() {
        editors.windows.forEach { half -> half.setSelectedComposite(half.fileList.single { it is MarginFile }, true) }
        settle()
    }

    fun recentMarginAddresses(): List<String> =
        EditorHistoryManager.getInstance(project).fileList.map { it.url }.filter { it.startsWith("marginalis:") }

    fun marginAddresses(): List<String> = editors.openFiles.map { it.url }.filter { it.startsWith("marginalis:") }

    fun reloadPlugin(whileUnloaded: () -> Unit = {}) {
        ProjectTab.closeEverywhere()
        try {
            settle()
            whileUnloaded()
        } finally {
            MarginFile.reinstate()
        }
        openOnStartup()
    }

    fun unloadPluginForUpdate(whileUnloaded: () -> Unit = {}): Throwable? = try {
        PluginUnload.forUpdate(whileUnloaded = whileUnloaded)
    } finally {
        settle()
    }

    val isOpen: Boolean get() = editors.isFileOpen(file)

    val isInFront: Boolean get() = editors.selectedEditor?.file == file

    val isPinned: Boolean get() = editors.windows.any { it.getComposite(file)?.isPinned == true }

    fun openTabs(): List<String> = editors.windows.single().allComposites.map { it.file }.map { if (it == file) "Margin" else it.name }

    var windowActive: Boolean
        get() = screen?.active == true
        set(value) {
            check(screen != null || !value) { "an active IDE window needs a display" }
            screen?.active = value
        }

    fun switchToTheIde() = activeWindowChanged(true)

    fun switchToAnotherApp() = activeWindowChanged(false)

    private fun activeWindowChanged(active: Boolean) {
        val window = checkNotNull(screen) { "switching windows needs a display" }
        window.active = active
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val change = PropertyChangeEvent(focus, "activeWindow", if (active) null else window, if (active) window else null)
        focus.getPropertyChangeListeners("activeWindow").forEach { it.propertyChange(change) }
        settle()
    }

    fun resize(width: Int, height: Int) {
        val window = checkNotNull(screen) { "resizing needs a display" }
        window.setSize(width, height)
        window.rootPane.setBounds(0, 0, width, height)
        settle()
    }

    fun openFileInFront(path: String): VirtualFile {
        val file = project.guessProjectDir()!!.findFileByRelativePath(path)!!
        editors.openFile(file, true, true)
        settle()
        return file
    }

    fun bringToFront() {
        editors.openFile(file, true, true)
        settle()
    }

    fun close() {
        editors.closeFile(file)
        settle()
    }

    fun settle() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        screen?.rootPane?.validate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun <T> refreshedBy(change: () -> T): T {
        val before = outline()
        val result = change()
        PlatformTestUtil.waitWithEventsDispatching("the Margin tab never changed from\n$before", { outline() != before }, 5)
        return result
    }

    val editor: MarginEditor get() = editors.getAllEditors(file).filterIsInstance<MarginEditor>().single()

    private val root: JComponent get() = editor.component

    private val top: JComponent get() = (root.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JComponent

    private val scroll: JBScrollPane get() = UIUtil.findComponentOfType(root, JBScrollPane::class.java)!!

    private val stack: JPanel get() = scroll.viewport.view as JPanel

    val scrolls: Boolean
        get() {
            settle()
            return stack.height > scroll.viewport.extentSize.height
        }

    val scrolledTo: Int
        get() {
            settle()
            return scroll.viewport.viewPosition.y
        }

    val viewHeight: Int
        get() {
            settle()
            return scroll.viewport.extentSize.height
        }

    fun scrollTo(y: Int) {
        settle()
        scroll.verticalScrollBar.value = y
        settle()
    }

    fun scrollToBottom() = scrollTo(stack.height)

    fun whileDraggingTheScrollBar(meanwhile: () -> Unit) {
        val bar = scroll.verticalScrollBar
        bar.valueIsAdjusting = true
        try {
            meanwhile()
            settle()
        } finally {
            bar.valueIsAdjusting = false
        }
    }

    fun topOnScreen(component: Component): Int {
        settle()
        return SwingUtilities.convertPoint(component, 0, 0, root).y
    }

    fun topInView(component: Component): Int {
        settle()
        return SwingUtilities.convertPoint(component, 0, 0, scroll.viewport).y
    }

    val toolWindow: Component by lazy {
        JTextField().also { checkNotNull(screen) { "a tool window needs a display" }.layeredPane.add(it) }
    }

    fun title(): String {
        settle()
        return EditorTabPresentationUtil.getEditorTabTitle(project, file)
    }

    fun shownTitle(): String = editors.windows.single().findCompositeAndTab(file)!!.second.text

    fun waitForShownTitle(expected: String) {
        PlatformTestUtil.waitWithEventsDispatching("the tab still reads '${shownTitle()}', expected '$expected'", { shownTitle() == expected }, 5)
    }

    fun tabIcon(): Icon? = IconUtil.getIcon(file, 0, project)

    fun counts(): String {
        settle()
        val tally = UIUtil.findComponentsOfType(top, JLabel::class.java).single { it.text.orEmpty().contains(" open · ") }
        return tally.parent.components.filterIsInstance<JLabel>().filter { it.isVisible }.joinToString("") { it.text }
    }

    fun alsoYourMove(): String? = shownLink("Also your move")?.text

    fun pill(): String? = shownLink("↑")?.text

    fun clickAlsoYourMove() = click(shownLink("Also your move")!!)

    fun clickPill() = click(shownLink("↑")!!)

    private fun shownLink(startingWith: String): ActionLink? {
        settle()
        return UIUtil.findComponentsOfType(top, ActionLink::class.java)
            .singleOrNull { it.text.startsWith(startingWith) }
            ?.takeIf { link -> generateSequence<Component>(link) { it.parent }.takeWhile { it !== top }.all { it.isVisible } }
    }

    fun newProjectThread() {
        click(UIUtil.findComponentsOfType(top, ActionLink::class.java).single { it.text == "+ New project thread" })
    }

    fun clickEmptyStateLink() {
        settle()
        click(UIUtil.findComponentsOfType(stack, ActionLink::class.java).single { it.text == "+ New project thread" })
    }

    fun stop(agentName: String) {
        val group = action("Stop agents") as ActionGroup
        check(presentationOf(group).isEnabled) { "Stop agents is greyed out" }
        val stop = group.getChildren(event(group)).single { it.templatePresentation.text == "Stop $agentName" }
        check(presentationOf(stop).isEnabled) { "Stop $agentName is greyed out" }
        stop.actionPerformed(event(stop))
        settle()
    }

    fun toolbar(): List<String> = toolbarActions().flatMap { action ->
        val children = (action as? ActionGroup)
            ?.takeIf { it.templatePresentation.text == null }
            ?.getChildren(event(action))?.toList()
        (children ?: listOf(action)).mapNotNull { child -> presentationOf(child).text?.takeIf { presentationOf(child).isVisible } }
    }

    fun presence(): List<String> = presenceActions().mapNotNull { presentationOf(it).text }

    fun presenceIcons(): List<Icon?> = presenceActions().map { presentationOf(it).icon }

    private fun presenceActions(): List<AnAction> = toolbarActions()
        .filterIsInstance<ActionGroup>()
        .filter { it.templatePresentation.text == null }
        .flatMap { it.getChildren(event(it)).toList() }

    fun isEnabled(text: String): Boolean = presentationOf(action(text)).isEnabled

    fun press(text: String) {
        val action = action(text)
        val event = event(action)
        action.update(event)
        action.actionPerformed(event)
        settle()
    }

    private fun action(text: String): AnAction = toolbarActions().single { presentationOf(it).text == text || it.templatePresentation.text == text }

    private fun toolbarActions(): List<AnAction> {
        val toolbar = UIUtil.uiTraverser(top).filter(ActionToolbar::class.java).first()!!
        return toolbar.actionGroup.getChildren(null).filter { it !is Separator }
    }

    private fun presentationOf(action: AnAction): Presentation {
        val event = event(action)
        action.update(event)
        return event.presentation
    }

    private fun event(action: AnAction) = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

    fun find(text: String) {
        val shortcuts = ActionManager.getInstance().getAction(IdeActions.ACTION_FIND).shortcutSet.shortcuts
        val find = ActionUtil.getActions(root).single { it.shortcutSet.shortcuts.contentEquals(shortcuts) }
        find.actionPerformed(event(find))
        filterField().text = text
        settle()
    }

    fun filterText(): String? = filterField().takeIf { it.parent.isVisible }?.text

    fun escapeFilter() {
        val field = filterField().textEditor
        field.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))!!
            .actionPerformed(ActionEvent(field, ActionEvent.ACTION_PERFORMED, "escape"))
        settle()
    }

    private fun filterField(): SearchTextField = UIUtil.findComponentOfType(top, SearchTextField::class.java)!!

    fun outline(): String {
        settle()
        return stack.components.flatMap(::describe).joinToString("\n")
    }

    fun rows(): List<String> = outline().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun describe(component: Component): List<String> = when (component) {
        is ThreadPanel -> listOf("[draft] " + component.statusText())
        is ThreadCard -> listOf(describeCard(component))
        is ConversationCard -> {
            val heading = (component.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as Container
            val labels = UIUtil.findComponentsOfType(heading as JComponent, JLabel::class.java).filter { it.isVisible }
            val members = (component.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as Container
            listOf(labels.joinToString("  ") { it.text }) + members.components.flatMap(::describe).map { "  $it" }
        }
        is JLabel -> listOf(component.text)
        else -> describeContainer(component as JComponent)
    }

    private fun describeContainer(component: JComponent): List<String> {
        UIUtil.findComponentOfType(component, JBTextArea::class.java)?.let { text ->
            return listOf(text.text) + UIUtil.findComponentsOfType(component, ActionLink::class.java).map { it.text }
        }
        val toggle = UIUtil.findComponentsOfType(component, ActionLink::class.java).first()
        val cards = (component.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as Container
        return listOf(toggle.text) + cards.components.flatMap(::describe).map { "  $it" }
    }

    private fun describeCard(card: ThreadCard): String {
        val heading = heading(card)
        val layout = heading.layout as BorderLayout
        val mark = UIUtil.findComponentsOfType(heading, JLabel::class.java).first { it.icon != null }
        val turn = turnOf(card, mark.icon)?.let { TurnSignal.glyph(it) + " " }.orEmpty()
        val title = (layout.getLayoutComponent(BorderLayout.CENTER) as JLabel).text
        val trailing = UIUtil.findComponentsOfType(heading, JLabel::class.java)
            .filter { it.isVisible && it.text in TRAILING_MARKS }.joinToString("") { " " + it.text }
        val preview = (layout.getLayoutComponent(BorderLayout.SOUTH) as JLabel).takeIf { it.isVisible }?.let { " — " + it.text }.orEmpty()
        val expanded = if (card.panel != null) " [expanded]" else ""
        return "$turn$title$trailing$preview$expanded"
    }

    private fun turnOf(card: ThreadCard, icon: Icon?): Turn? {
        val mark = MarginalisIcons.markOf(listOf(card.thread))
        return Turn.entries.firstOrNull { icon === MarginalisIcons.withLeadingTurnSignal(mark, it) }
    }

    private fun heading(card: ThreadCard): JComponent = (card.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JComponent

    private fun cards(): List<ThreadCard> {
        settle()
        return UIUtil.findComponentsOfType(stack, ThreadCard::class.java)
    }

    internal fun card(text: String): ThreadCard = cards().firstOrNull { describeCard(it).contains(text) }
        ?: error("no card shows '$text' in\n${outline()}")

    fun click(text: String) {
        val card = card(text)
        clickHeading(heading(card))
    }

    fun reopen(text: String) {
        check(card(text).panel != null) { "'$text' is not open in\n${outline()}" }
        click(text)
        click(text)
    }

    fun clickConversation(discussion: String) {
        settle()
        val conversation = UIUtil.findComponentsOfType(stack, ConversationCard::class.java).single { conversation ->
            UIUtil.findComponentsOfType(conversation, JLabel::class.java).any { it.text == "From GitHub · $discussion" }
        }
        clickHeading((conversation.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JComponent)
    }

    fun clickResolvedFold() {
        settle()
        click(UIUtil.findComponentsOfType(stack, ActionLink::class.java).single { it.text.endsWith(" resolved") })
    }

    private fun clickHeading(heading: JComponent) {
        val event = MouseEvent(heading, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, 4, 4, 4, 1, false, MouseEvent.BUTTON1)
        heading.mouseListeners.forEach { it.mouseClicked(event) }
        settle()
    }

    private fun click(link: ActionLink) {
        link.doClick()
        settle()
    }

    fun panel(text: String): ThreadPanel = card(text).panel ?: error("'$text' is folded in\n${outline()}")

    fun draft(): ThreadPanel? {
        settle()
        return stack.components.filterIsInstance<ThreadPanel>().singleOrNull()
    }

    fun outlineColor(text: String): Color {
        val image = BufferedImage(100, 60, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        card(text).border.paintBorder(card(text), graphics, 0, 0, 100, 60)
        graphics.dispose()
        return Color(image.getRGB(0, 20), true)
    }

    private fun ThreadPanel.statusText(): String =
        UIUtil.findComponentsOfType(this, JLabel::class.java).firstOrNull { it.isVisible && it.text.orEmpty().contains("unsent") }?.text.orEmpty()

    private companion object {
        val TRAILING_MARKS = setOf("resolved just now", "●")
    }
}
