package dev.marginalis.plugin.testing

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowBalloonShowOptions
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.PopupHandler
import com.intellij.ui.UiInterceptors
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.AgreementSignal
import dev.marginalis.core.Turn
import dev.marginalis.core.TurnSignal
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.ParticipantStackIcon
import dev.marginalis.plugin.ui.ProjectLiveAction
import dev.marginalis.plugin.ui.toolwindow.MarginalisToolWindowFactory
import java.awt.Component
import java.awt.DefaultKeyboardFocusManager
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.Popup
import javax.swing.PopupFactory
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class MarginToolWindow(private val project: Project, private val disposable: Disposable) {

    class Window(project: Project, parent: Disposable) : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
        private val lifetime = Disposer.newDisposable(parent, "Marginalis test tool window")
        var actions: List<AnAction> = emptyList()
        var shownIcon: Icon? = null
        val titles = mutableListOf<String>()
        val balloons = mutableListOf<String>()
        var visible = true
        var activations = 0

        override fun getId(): String = ID
        override fun getDisposable(): Disposable = lifetime
        override fun setTitleActions(actions: List<AnAction>) {
            this.actions = actions.toList()
        }
        override fun setIcon(icon: Icon) {
            shownIcon = icon
        }
        override fun getIcon(): Icon? = shownIcon
        override fun setTitle(title: String) {
            titles += title
        }
        override fun setStripeTitle(title: String) {
            titles += title
        }
        override fun isVisible(): Boolean = visible
        override fun show(runnable: Runnable?) {
            visible = true
            runnable?.run()
        }
        override fun hide(runnable: Runnable?) {
            visible = false
            runnable?.run()
        }
        override fun activate(runnable: Runnable?, autoFocusContents: Boolean, forced: Boolean) {
            visible = true
            activations++
            runnable?.run()
        }
    }

    val window = Window(project, disposable)

    val panel: JComponent

    val tree: Tree

    init {
        val manager = object : ToolWindowHeadlessManagerImpl(project) {
            override fun getToolWindow(id: String?): ToolWindow? = if (id == ID) window else super.getToolWindow(id)
            override fun notifyByBalloon(options: ToolWindowBalloonShowOptions) {
                if (options.toolWindowId == ID) window.balloons += options.htmlBody
            }
        }
        project.replaceService(ToolWindowManager::class.java, manager, disposable)
        MarginalisToolWindowFactory().also { it.init(window) }.createToolWindowContent(project, window)
        val content = window.contentManager.contents.single()
        Disposer.register(disposable) { Disposer.dispose(content) }
        panel = content.component
        tree = UIUtil.findComponentOfType(panel, Tree::class.java)!!
        settle()
    }

    fun settle() = PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    fun <T> rebuiltBy(change: () -> T): T {
        val before = tree.model
        val result = change()
        PlatformTestUtil.waitWithEventsDispatching("the tree was never rebuilt", { tree.model !== before }, 5)
        settle()
        return result
    }

    val displayName: String? get() = window.contentManager.contents.single().displayName

    fun outline(): String {
        settle()
        return (0 until tree.rowCount).joinToString("\n") { row ->
            val path = tree.getPathForRow(row)
            "  ".repeat(path.pathCount - 2) + rowText(row)
        }
    }

    fun rows(): List<String> = outline().lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun rowText(row: Int): String {
        val cell = render(row)
        val words = UIUtil.findComponentOfType(cell, SimpleColoredComponent::class.java)!!
        val leading = (words.icon as? RowIcon)?.let { icon -> glyphOf(icon.getIcon(2))?.let { "$it " } } ?: ""
        val counts = UIUtil.findComponentsOfType(cell, JLabel::class.java)
            .filter { it.isVisible && turnOf(it.icon) != null }
            .joinToString("") { " " + TurnSignal.glyph(turnOf(it.icon)!!) + it.text }
        return leading + words.getCharSequence(false).toString() + counts
    }

    fun participantsShown(text: String): String? {
        val cell = render(rowOf(text))
        return UIUtil.findComponentsOfType(cell, JLabel::class.java)
            .singleOrNull { it.isVisible && it.icon is ParticipantStackIcon }
            ?.let { it.text ?: "" }
    }

    fun spoken(text: String): String = render(rowOf(text)).accessibleContext.accessibleName

    fun isGreyed(text: String, fragment: String): Boolean {
        val words = UIUtil.findComponentOfType(render(rowOf(text)), SimpleColoredComponent::class.java)!!
        val iterator = words.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().contains(fragment)) return iterator.textAttributes.fgColor == SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor
        }
        error("no fragment '$fragment' in '$text'")
    }

    private fun render(row: Int): JComponent {
        val path = tree.getPathForRow(row)
        return tree.cellRenderer.getTreeCellRendererComponent(
            tree, path.lastPathComponent, tree.isRowSelected(row), tree.isExpanded(row),
            tree.model.isLeaf(path.lastPathComponent), row, false,
        ) as JComponent
    }

    private fun turnOf(icon: Icon?): Turn? = Turn.entries.firstOrNull { icon != null && icon === MarginalisIcons.turnSignal(it) }

    private fun glyphOf(icon: Icon?): String? = turnOf(icon)?.let(TurnSignal::glyph)
        ?: AgreementSignal.entries.firstOrNull { icon != null && icon === MarginalisIcons.agreementSignal(it) }?.glyph

    fun rowOf(text: String, under: String? = null): Int {
        settle()
        val from = under?.let { section -> (0 until tree.rowCount).first { rowText(it).startsWith(section) } } ?: 0
        val matches = (from until tree.rowCount).filter { rowText(it).contains(text) }
        check(matches.isNotEmpty()) { "no row shows '$text' in\n${outline()}" }
        return matches.first()
    }

    fun selected(): String? {
        settle()
        return tree.selectionRows?.singleOrNull()?.let(::rowText)
    }

    fun click(text: String, clicks: Int = 1, under: String? = null) {
        val row = rowOf(text, under)
        val at = center(row)
        for (count in 1..clicks) {
            mouse(MouseEvent.MOUSE_PRESSED, at, count)
            mouse(MouseEvent.MOUSE_RELEASED, at, count)
            mouse(MouseEvent.MOUSE_CLICKED, at, count)
        }
        settle()
    }

    fun doubleClick(text: String, under: String? = null) = click(text, clicks = 2, under = under)

    fun pressKey(keyCode: Int) {
        val press = KeyEvent(tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED)
        tree.keyListeners.forEach { it.keyPressed(press) }
        val binding = tree.getActionForKeyStroke(KeyStroke.getKeyStroke(keyCode, 0))
        when {
            press.isConsumed -> Unit
            binding != null -> binding.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, null))
            else -> withTreeFocused { IdeEventQueue.getInstance().keyEventDispatcher.dispatchKeyEvent(press) }
        }
        settle()
    }

    private fun <T> withTreeFocused(act: () -> T): T {
        val treeData = dataContext()
        val focused = Disposer.newDisposable("tree focused")
        (DataManager.getInstance() as HeadlessDataManager).setTestDataProvider({ key -> treeData.getData(DataKey.create<Any>(key)) }, focused)
        val original = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        KeyboardFocusManager.setCurrentKeyboardFocusManager(object : DefaultKeyboardFocusManager() {
            override fun getFocusOwner(): Component = tree
            override fun getPermanentFocusOwner(): Component = tree
        })
        return try {
            act()
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(original)
            Disposer.dispose(focused)
        }
    }

    fun toggleFolding(text: String, under: String? = null) {
        val path = tree.getPathForRow(rowOf(text, under))
        if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        settle()
    }

    fun isExpanded(text: String, under: String? = null): Boolean = tree.isExpanded(rowOf(text, under))

    fun select(text: String, under: String? = null) {
        tree.selectionPath = tree.getPathForRow(rowOf(text, under))
        settle()
    }

    fun selectAlso(text: String, under: String? = null) {
        tree.addSelectionPath(tree.getPathForRow(rowOf(text, under)))
        settle()
    }

    fun selectNothing() {
        tree.clearSelection()
        settle()
    }

    fun canJumpToSource(): Boolean {
        val action = ActionManager.getInstance().getAction("EditSource")
        val event = TestActionEvent.createTestEvent(action, dataContext())
        action.update(event)
        return event.presentation.isEnabled
    }

    fun jumpToSource() {
        val action = ActionManager.getInstance().getAction("EditSource")
        val event = TestActionEvent.createTestEvent(action, dataContext())
        action.update(event)
        check(event.presentation.isEnabled) { "Jump to Source is unavailable" }
        action.actionPerformed(event)
        settle()
    }

    private var productionData = false

    fun dataContext(): DataContext {
        if (!productionData) {
            HeadlessDataManager.fallbackToProductionDataManager(disposable)
            productionData = true
        }
        return DataManager.getInstance().getDataContext(tree)
    }

    fun toolbar(): List<String> = window.actions.flatMap { action ->
        action.templatePresentation.text?.let(::listOf) ?: shownChildren(action as ActionGroup)
    }

    fun presence(): List<String> = window.actions.filterIsInstance<ActionGroup>()
        .filter { it.templatePresentation.text == null }
        .flatMap(::shownChildren)

    fun presenceIcon(name: String): Icon? = presenceActions().map(::presented).single { it.text.startsWith("$name · ") }.icon

    fun presenceShowing(action: AnAction): Presentation = presented(action)

    fun presenceActions(): List<AnAction> = window.actions.filterIsInstance<ActionGroup>()
        .filter { it.templatePresentation.text == null }
        .flatMap { it.getChildren(event(it)).toList() }

    fun clickAvatar(name: String, choose: String) {
        val avatar = presenceActions().single { presented(it).text.startsWith("$name · ") }
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<JBPopup>(JBPopup::class.java) {
            override fun doIntercept(popup: JBPopup) {
                val list = UIUtil.findComponentOfType(popup.content, JList::class.java)!!
                val index = (0 until list.model.size).single { (list.model.getElementAt(it) as? PopupFactoryImpl.ActionItem)?.text == choose }
                list.selectedIndex = index
                (popup as ListPopupImpl).handleSelect(true)
            }
        })
        avatar.actionPerformed(TestActionEvent.createTestEvent(avatar, dataContext()))
        settle()
    }

    private fun presented(action: AnAction): Presentation {
        val event = event(action)
        action.update(event)
        return event.presentation
    }

    fun participantsIcon(text: String): ParticipantStackIcon? = UIUtil.findComponentsOfType(render(rowOf(text)), JLabel::class.java)
        .singleOrNull { it.isVisible && it.icon is ParticipantStackIcon }?.icon as ParticipantStackIcon?

    private fun shownChildren(group: ActionGroup): List<String> = group.getChildren(event(group)).mapNotNull { child ->
        val event = event(child)
        child.update(event)
        event.presentation.text?.takeIf { event.presentation.isVisible }
    }

    fun presentation(text: String): Presentation {
        val action = action(text)
        val event = event(action)
        action.update(event)
        return event.presentation
    }

    fun isEnabled(text: String): Boolean = presentation(text).isEnabled

    fun press(text: String, answer: Int? = null): String {
        var asked = ""
        if (answer != null) {
            TestDialogManager.setTestDialog(TestDialog { message ->
                asked = message
                answer
            })
        }
        try {
            val action = action(text)
            val event = event(action)
            action.update(event)
            check(event.presentation.isEnabled) { "$text is greyed out" }
            action.actionPerformed(event)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        settle()
        return asked
    }

    fun filters(): Map<String, Boolean> = filterChoices().associate { it.templatePresentation.text to it.isSelected(event(it)) }

    fun filterBy(title: String) {
        val choice = filterChoices().single { it.templatePresentation.text == title }
        choice.setSelected(event(choice), true)
        settle()
    }

    fun unselectFilter(title: String) {
        val choice = filterChoices().single { it.templatePresentation.text == title }
        choice.setSelected(event(choice), false)
        settle()
    }

    fun layOutGuided(label: String) {
        val choice = (action("Guided Layout") as ActionGroup).getChildren(null).filterIsInstance<ToggleAction>()
            .single { it.templatePresentation.text == label }
        choice.setSelected(event(choice), true)
        settle()
    }

    fun emptyText(): String = tree.emptyText.text

    fun isProjectLive(): Boolean = projectLive().let { it.isSelected(event(it)) }

    fun turnProjectLive(on: Boolean) {
        val toggle = projectLive()
        if (toggle.isSelected(event(toggle)) != on) toggle.setSelected(event(toggle), on)
        settle()
    }

    fun projectLiveText(): String = presented(projectLive()).text

    fun canTurnProjectLive(): Boolean = presented(projectLive()).isEnabled

    private fun projectLive(): ProjectLiveAction = window.actions.filterIsInstance<ProjectLiveAction>().single()

    private fun filterChoices(): List<ToggleAction> =
        (action("Filter") as ActionGroup).getChildren(null).filterIsInstance<ToggleAction>()

    fun stop(agentName: String) {
        val group = window.actions.filterIsInstance<ActionGroup>().single { it.templatePresentation.text == "Stop agents" }
        check(presented(group).isEnabled) { "Stop agents is greyed out" }
        val stop = group.getChildren(event(group)).single { it.templatePresentation.text == "Stop $agentName" }
        check(presented(stop).isEnabled) { "Stop $agentName is greyed out" }
        stop.actionPerformed(event(stop))
        settle()
    }

    fun group(text: String): List<AnAction> {
        val group = window.actions.filterIsInstance<ActionGroup>().single { it.templatePresentation.text == text }
        return group.getChildren(event(group)).toList()
    }

    fun action(text: String): AnAction = window.actions.single { action ->
        action.templatePresentation.text == text || run {
            val event = event(action)
            action.update(event)
            event.presentation.text == text
        }
    }

    fun contextMenu(text: String, under: String? = null): List<String> = showContextMenu(text, under)?.let(::items)?.map { it.text } ?: emptyList()

    fun chooseFromContextMenu(text: String, item: String, answer: Int? = null, under: String? = null): String {
        val menu = showContextMenu(text, under) ?: error("no context menu on '$text'")
        var asked = ""
        if (answer != null) {
            TestDialogManager.setTestDialog(TestDialog { message ->
                asked = message
                answer
            })
        }
        try {
            items(menu).single { it.text == item }.doClick(0)
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        settle()
        return asked
    }

    private fun items(menu: JPopupMenu): List<JMenuItem> = menu.components.filterIsInstance<JMenuItem>()

    private fun showContextMenu(text: String, under: String?): JPopupMenu? {
        val at = center(rowOf(text, under))
        val screen = object : JComponent() {
            override fun isShowing(): Boolean = true
            override fun getLocationOnScreen(): Point = Point(0, 0)
        }
        val shown = mutableListOf<Component>()
        val original = PopupFactory.getSharedInstance()
        PopupFactory.setSharedInstance(object : PopupFactory() {
            override fun getPopup(owner: Component?, contents: Component, x: Int, y: Int): Popup {
                shown += contents
                return object : Popup() {
                    override fun show() = Unit
                    override fun hide() = Unit
                }
            }
        })
        try {
            val trigger = MouseEvent(screen, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK, at.x, at.y, at.x, at.y, 1, true, MouseEvent.BUTTON3)
            tree.mouseListeners.filterIsInstance<PopupHandler>().single().mousePressed(trigger)
        } finally {
            PopupFactory.setSharedInstance(original)
        }
        settle()
        val menu = shown.filterIsInstance<JPopupMenu>().singleOrNull()
        menu?.isVisible = false
        return menu
    }

    private fun center(row: Int): Point {
        tree.size = tree.preferredSize
        val bounds = tree.getRowBounds(row)!!
        return Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
    }

    private fun mouse(id: Int, at: Point, clickCount: Int) {
        val modifiers = if (id == MouseEvent.MOUSE_CLICKED) 0 else InputEvent.BUTTON1_DOWN_MASK
        tree.dispatchEvent(MouseEvent(tree, id, System.currentTimeMillis(), modifiers, at.x, at.y, at.x, at.y, clickCount, false, MouseEvent.BUTTON1))
    }

    fun selectOpenedThread(context: DataContext = SimpleDataContext.getProjectContext(project)) {
        val action = action("Select Opened Thread")
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        check(event.presentation.isEnabled) { "Select Opened Thread is greyed out" }
        action.actionPerformed(event)
        settle()
    }

    fun showOnScreen(width: Int, height: Int) {
        val screen = object : JFrame() {
            override fun isShowing(): Boolean = true
        }
        Disposer.register(disposable) { screen.dispose() }
        screen.contentPane.add(panel)
        screen.setSize(width, height)
        screen.addNotify()
        screen.rootPane.setBounds(0, 0, width, height)
        screen.validate()
        settle()
    }

    fun isSelectionInView(): Boolean {
        settle()
        panel.rootPane?.validate()
        val bounds = tree.getPathBounds(tree.selectionPath ?: return false) ?: return false
        return tree.visibleRect.contains(bounds)
    }

    fun selectedPath(): List<String> {
        settle()
        val path: TreePath = tree.selectionPath ?: return emptyList()
        return path.path.drop(1).map { node -> rowText(tree.getRowForPath(TreePath((node as DefaultMutableTreeNode).path))) }
    }

    private fun event(action: AnAction) = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

    companion object {
        const val ID = "Marginalis"
        const val YES = Messages.YES
        const val NO = Messages.NO

        fun stripe(project: Project, disposable: Disposable): Window =
            Window(project, disposable).also {
                MarginalisToolWindowFactory().init(it)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            }
    }
}
