package dev.marginalis.plugin.ui.tab

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Elsewhere
import dev.marginalis.core.HoldStill
import dev.marginalis.core.ListWhileInFront
import dev.marginalis.core.Message
import dev.marginalis.core.NewTags
import dev.marginalis.core.People
import dev.marginalis.core.ProjectTabExpansion
import dev.marginalis.core.ProjectTabLayout
import dev.marginalis.core.ProjectTabLayout.Entry
import dev.marginalis.core.ProjectTabTally
import dev.marginalis.core.ScrollAnchor
import dev.marginalis.core.ThreadText
import dev.marginalis.plugin.settings.MarginalisSettings
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.AgentPresenceGroup
import dev.marginalis.plugin.ui.CoalescedEdtRunner
import dev.marginalis.plugin.ui.MarginalisPalette
import dev.marginalis.plugin.ui.StopAgentsGroup
import dev.marginalis.plugin.ui.SubmitRoundAction
import dev.marginalis.plugin.ui.ThreadPanel
import dev.marginalis.plugin.ui.WalkthroughNavigator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent

class ProjectTabPanel(private val project: Project) : Disposable {

    private val store = MarginalisStore.getInstance(project)
    private val state = ProjectTabState.getInstance(project)
    private val settings = MarginalisSettings.getInstance()

    private val expansion = ProjectTabExpansion(::holdsDraft)
    private val newTags = NewTags()
    private val holdStill = HoldStill()
    private var shownEntries: List<Entry> = emptyList()
    private var resolvedThreads: List<CommentThread> = emptyList()
    private var selected = false
    private var disposed = false
    private var resolvedOpen = false
    private val resolvedExpanded = HashSet<String>()

    private val cards = HashMap<String, ThreadCard>()
    private val conversations = HashMap<String, ConversationCard>()
    private var draftShown: DraftShown? = null
    private var focusOnceShown: String? = null

    private val counts = JBLabel()
    private val newCount = JBLabel().apply { foreground = MarginalisPalette.ACCENT }
    private val elsewhereLink = ActionLink("") { elsewhere?.let { WalkthroughNavigator.navigateTo(project, it.first) } }
    private var elsewhere: Elsewhere? = null
    private val pill = ActionLink("") {
        holdStill.release()
        refresh()
    }
    private val filterField = SearchTextField(false)
    private val elsewhereRow = row(elsewhereLink)
    private val filterRow = row(filterField, BorderLayout.CENTER).apply { isVisible = false }
    private val pillRow = row(pill)

    private val stack = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width

        override fun getScrollableTracksViewportWidth(): Boolean = true

        override fun getScrollableTracksViewportHeight(): Boolean = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 12, 12, 12)
    }
    private val scroll = JBScrollPane(stack).apply { border = JBUI.Borders.empty() }

    private val emptyState = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(8, 0, 16, 0)
        add(
            JBTextArea(
                "Project threads are for what isn't about one file: plans, release notes, a pull request's " +
                    "conversation. Agents start them here, and so can you.",
            ).apply {
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                font = UIUtil.getLabelFont()
                foreground = UIUtil.getContextHelpForeground()
                border = JBUI.Borders.emptyBottom(8)
            },
            BorderLayout.CENTER,
        )
        add(newThreadLink(), BorderLayout.SOUTH)
    }
    private val noMatch = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(8, 0)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
    private val resolvedToggle = ActionLink("") {
        resolvedOpen = !resolvedOpen
        refresh()
    }
    private val resolvedCards = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.emptyTop(6)
    }
    private val resolvedFold = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(8)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(resolvedToggle)
        }, BorderLayout.NORTH)
        add(resolvedCards, BorderLayout.CENTER)
    }

    private val root = JPanel(BorderLayout()).apply { isFocusable = true }
    private val toolbar = ActionManager.getInstance()
        .createActionToolbar("MarginalisProjectTab", DefaultActionGroup(AgentPresenceGroup(), SubmitRoundAction(), StopAgentsGroup()), true)
        .also {
            it.targetComponent = root
            it.component.isOpaque = false
        }

    private val refreshing = CoalescedEdtRunner(project) { if (!disposed) refresh() }
    private val observing = store.threads.observe { refreshing.request() }
    private val onWaitersChanged: () -> Unit = { ActivityTracker.getInstance().inc() }
    private val reading = CoalescedEdtRunner(project) { if (!disposed) expandedPanels().forEach(ThreadPanel::markReadIfSeen) }
    private val resizing = Timer(RESIZE_SETTLE_MILLIS) { expandedPanels().forEach(ThreadPanel::refresh) }.apply { isRepeats = false }
    private val onActiveWindowChanged = PropertyChangeListener { event -> if (involvesOwnWindow(event)) lookChanged() }

    init {
        root.add(buildTop(), BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        store.handBack.addListener(onWaitersChanged)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = resizing.restart()
        })
        scroll.viewport.addChangeListener { reading.request() }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener(ACTIVE_WINDOW, onActiveWindowChanged)
        installFind()
        refresh()
    }

    val component: JComponent get() = root

    val preferredFocus: JComponent? get() = draftShown?.panel ?: root

    fun onShown(inFront: Boolean) {
        selected = inFront
        lookChanged()
    }

    fun reveal(threadId: String, message: Message?) {
        val thread = store.threads.byId(threadId) ?: return
        if (!ThreadText.matches(thread, filterField.text, settings.people)) closeFilter()
        holdStill.admit(threadId)
        refresh()
        if (shownEntries.any { entry -> entry.threads.any { it.id == threadId } }) {
            expansion.reveal(threadId)
        } else {
            resolvedOpen = true
            resolvedExpanded += threadId
        }
        refresh()
        val card = cards[threadId] ?: return
        card.panel?.markRead()
        ApplicationManager.getApplication().invokeLater {
            if (disposed || cards[threadId] !== card) return@invokeLater
            card.scrollRectToVisible(Rectangle(0, 0, card.width, card.height))
            card.flash()
            message?.let { card.panel?.reveal(it) }
        }
    }

    fun draftNew() {
        refresh()
        val panel = draftShown?.panel ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || draftShown?.panel !== panel) return@invokeLater
            stack.scrollRectToVisible(Rectangle(0, 0, 1, 1))
            panel.focusReply()
        }
    }

    fun applyPrefs() {
        refresh()
        reading.request()
    }

    override fun dispose() {
        disposed = true
        observing.close()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener(ACTIVE_WINDOW, onActiveWindowChanged)
        store.handBack.removeListener(onWaitersChanged)
        resizing.stop()
        cards.values.forEach(ThreadCard::stopFlashing)
    }

    private val inFront: Boolean
        get() = selected && ownWindow()?.isActive == true

    private fun ownWindow(): Window? = SwingUtilities.getWindowAncestor(root)

    private fun involvesOwnWindow(event: PropertyChangeEvent): Boolean {
        val own = ownWindow() ?: return false
        return event.oldValue === own || event.newValue === own
    }

    private fun holdsDraft(threadId: String): Boolean = !store.drafts[threadId]?.text.isNullOrBlank()

    private fun lookChanged() {
        if (disposed) return
        if (!inFront) newTags.endLook()
        refresh()
        if (!inFront) return
        val panels = expandedPanels()
        panels.filter { it.showsNewTags }.forEach(ThreadPanel::refresh)
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) panels.forEach(ThreadPanel::markReadIfSeen)
        }
    }

    private fun refresh(anchorOn: String? = null) {
        if (disposed) return
        val anchor = anchorBeforeRefresh(anchorOn)
        val prefs = settings.projectTabPrefs
        val people = settings.people
        val threads = store.threads.all()
        val live = ProjectTabLayout.arrange(threads, shownEntries, ::holdsDraft, prefs.groupRelayed)
        val view = if (prefs.list == ListWhileInFront.HOLD_STILL) {
            holdStill.update(live, inFront)
        } else {
            holdStill.release()
            HoldStill.View(shown = live, dimmed = emptySet(), pending = 0)
        }
        shownEntries = view.shown.active
        resolvedThreads = view.shown.resolved
        expansion.observe(shownEntries, prefs.expandOnYourMove)
        resolvedExpanded.retainAll(resolvedThreads.mapTo(HashSet()) { it.id })
        refreshTop(threads, view.pending)

        val filter = filterField.text
        val visible = shownEntries.filter { ThreadText.matches(it, filter, people) }
        val children = mutableListOf<JComponent>()
        val draft = state.draft
        if (draft == null) draftShown = null else children += draftPanel(draft)
        when {
            shownEntries.isEmpty() && draft == null && view.pending == 0 -> children += emptyState
            visible.isEmpty() && filter.isNotBlank() -> children += noMatch.apply { text = "No open project thread mentions “${filter.trim()}”." }
        }
        visible.mapTo(children) { entryCard(it, view.dimmed, people) }
        if (resolvedThreads.isNotEmpty()) children += resolvedFold(resolvedThreads, filter, people)
        stack.arrange(children)
        forgetCardsNotIn(shownEntries, resolvedThreads)
        focusFirstSentThread()
        keepInPlace(anchor)
    }

    private fun anchorBeforeRefresh(anchorOn: String?): ScrollAnchor.Anchor? {
        val view = scroll.viewport.viewRect
        if (view.height <= 0 || scroll.verticalScrollBar.valueIsAdjusting) return null
        val boxes = anchorTargets().map { (key, component) ->
            val top = topInStack(component)
            ScrollAnchor.Box(key, top, top + component.height)
        }
        return ScrollAnchor.pick(boxes, view.y, view.height, preferred = listOf(anchorOn, writingIn()))
    }

    private fun keepInPlace(anchor: ScrollAnchor.Anchor?) {
        anchor ?: return
        root.validate()
        val component = anchorTargets()[anchor.key] ?: return
        val viewport = scroll.viewport
        val viewTop = ScrollAnchor.viewTopKeeping(anchor, topInStack(component), stack.height, viewport.extentSize.height)
        if (viewTop != viewport.viewPosition.y) viewport.viewPosition = Point(viewport.viewPosition.x, viewTop)
    }

    private fun writingIn(): String? {
        val focused = ownWindow()?.mostRecentFocusOwner ?: return null
        return anchorTargets().entries.firstOrNull { (_, component) -> SwingUtilities.isDescendingFrom(focused, component) }?.key
    }

    // Thread cards precede conversations, so the innermost card holding the focus is found first.
    private fun anchorTargets(): Map<String, JComponent> {
        val targets = LinkedHashMap<String, JComponent>()
        draftShown?.let { targets[it.threadId] = it.panel }
        targets.putAll(cards)
        targets.putAll(conversations)
        return targets.filterValues { it.height > 0 && SwingUtilities.isDescendingFrom(it, stack) }
    }

    private fun topInStack(component: JComponent): Int = SwingUtilities.convertPoint(component, 0, 0, stack).y

    private fun focusFirstSentThread() {
        val sent = focusOnceShown ?: return
        focusOnceShown = null
        val panel = cards[sent]?.panel ?: return
        ApplicationManager.getApplication().invokeLater { if (!disposed) panel.focusDefault() }
    }

    private fun closeKeepingFocus(host: JComponent, close: () -> Unit) {
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val hadFocus = focused != null && SwingUtilities.isDescendingFrom(focused, host)
        close()
        if (hadFocus) root.requestFocusInWindow()
    }

    private fun refreshTop(threads: List<CommentThread>, pending: Int) {
        val tally = ProjectTabTally.of(threads)
        counts.text = "${tally.open} open · ${tally.turns.user} your move · ${tally.turns.agent} agent's move"
        newCount.text = " · ${tally.newCount} new"
        newCount.isVisible = tally.newCount > 0
        elsewhere = Elsewhere.of(threads)
        elsewhereRow.isVisible = elsewhere != null
        elsewhereLink.text = elsewhere?.let { "Also your move: ${it.count} ${if (it.count == 1) "thread" else "threads"} in files ›" }.orEmpty()
        pillRow.isVisible = pending > 0
        pill.text = "↑ $pending new ${if (pending == 1) "thread" else "threads"}"
    }

    private fun entryCard(entry: Entry, dimmed: Set<String>, people: People): JComponent = when (entry) {
        is Entry.Single -> threadCard(entry.thread, expansion.isExpanded(entry.key), entry.thread.id in dimmed, people)
        is Entry.Conversation -> {
            val card = conversations.getOrPut(entry.key) { ConversationCard(entry.discussion) { toggleConversation(entry.key) } }
            val expanded = expansion.isExpanded(entry.key)
            val members = entry.threads.map { threadCard(it, expanded && expansion.isExpanded(it.id), it.id in dimmed, people) }
            card.bind(entry, expanded, dimmed = entry.key in dimmed, memberCards = members)
            card
        }
    }

    private fun resolvedFold(resolved: List<CommentThread>, filter: String, people: People): JComponent {
        resolvedToggle.text = "${if (resolvedOpen) "▾" else "▸"} ${resolved.size} resolved"
        val listed = if (resolvedOpen) resolved.filter { ThreadText.matches(it, filter, people) } else emptyList()
        resolvedCards.arrange(listed.map { threadCard(it, it.id in resolvedExpanded, dimmed = false, people) })
        return resolvedFold
    }

    private fun threadCard(thread: CommentThread, expanded: Boolean, dimmed: Boolean, people: People): ThreadCard {
        val card = cards[thread.id]?.takeIf { it.thread === thread }
            ?: ThreadCard(thread, ::toggleThread, ::threadPanel).also { cards[thread.id] = it }
        card.bind(expanded, dimmed, people)
        return card
    }

    private fun threadPanel(card: ThreadCard): ThreadPanel = ThreadPanel(
        project,
        editor = null,
        thread = card.thread,
        ensureStored = {},
        onClose = {
            if (heldOpenByDraft(card.thread.id)) card.panel?.focusReply() else closeKeepingFocus(card) { collapse(card) }
        },
        hostWidth = ::panelWidth,
        mayMarkRead = { clickedInto -> settings.projectTabPrefs.readWhen.reads(inFront, card.panel != null, clickedInto) },
        newTags = newTags::tagsOnSight,
        onDraftPresenceChanged = refreshing::request,
    )

    private fun draftPanel(draft: CommentThread): JComponent {
        draftShown?.takeIf { it.threadId == draft.id }?.let { return it.panel }
        val panel = ThreadPanel(
            project,
            editor = null,
            thread = draft,
            ensureStored = {
                if (store.threads.byId(draft.id) == null) store.threads.add(draft)
                state.clearDraft(draft)
                holdStill.admit(draft.id)
                expansion.reveal(draft.id)
                focusOnceShown = draft.id
            },
            onClose = {
                val panel = draftShown?.panel
                if (holdsDraft(draft.id)) {
                    panel?.focusReply()
                } else {
                    closeKeepingFocus(panel ?: root) {
                        state.clearDraft(draft)
                        refresh()
                    }
                }
            },
            hostWidth = ::panelWidth,
        ).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.compound(JBUI.Borders.emptyBottom(8), border)
        }
        draftShown = DraftShown(draft.id, panel)
        return panel
    }

    private fun toggleThread(card: ThreadCard) {
        val id = card.thread.id
        if (resolvedThreads.any { it.id == id }) {
            if (!resolvedExpanded.remove(id)) resolvedExpanded += id
        } else {
            expansion.toggle(id)
        }
        refresh(anchorOn = id)
        val opened = card.panel
        if (opened != null) opened.markRead() else newTags.endLook(id)
    }

    private fun heldOpenByDraft(threadId: String): Boolean =
        holdsDraft(threadId) && resolvedThreads.none { it.id == threadId }

    private fun collapse(card: ThreadCard) {
        if (card.panel != null) toggleThread(card)
    }

    private fun toggleConversation(key: String) {
        expansion.toggle(key)
        refresh(anchorOn = key)
    }

    private fun forgetCardsNotIn(entries: List<Entry>, resolved: List<CommentThread>) {
        val present = entries.flatMapTo(HashSet()) { entry -> entry.threads.map { it.id } } + resolved.map { it.id }
        cards.keys.filter { it !in present }.forEach { cards.remove(it)?.stopFlashing() }
        conversations.keys.retainAll(entries.mapTo(HashSet()) { it.key })
    }

    private fun expandedPanels(): List<ThreadPanel> = cards.values.mapNotNull { it.panel } + listOfNotNull(draftShown?.panel)

    private fun panelWidth(): Int = (scroll.viewport.width - JBUI.scale(PANEL_INSET)).coerceAtLeast(JBUI.scale(MIN_PANEL_WIDTH))

    private fun buildTop(): JComponent {
        val headerLine = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(counts)
                add(newCount)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(toolbar.component)
                add(newThreadLink())
            }, BorderLayout.EAST)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12, 0, 12)
            listOf(headerLine, elsewhereRow, filterRow, pillRow).forEach(::add)
        }
    }

    private fun row(component: JComponent, constraint: String = BorderLayout.WEST): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyTop(4)
        add(component, constraint)
    }

    private fun newThreadLink(): ActionLink = ActionLink("+ New project thread") { ProjectTab.draftNew(project) }

    private fun installFind() {
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                filterRow.isVisible = true
                filterRow.revalidate()
                filterField.requestFocusInWindow()
                filterField.selectText()
            }
        }.registerCustomShortcutSet(ActionManager.getInstance().getAction(IdeActions.ACTION_FIND).shortcutSet, root)
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        filterField.textEditor.registerKeyboardAction(
            {
                closeFilter()
                root.requestFocusInWindow()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED,
        )
    }

    private fun closeFilter() {
        filterField.text = ""
        filterRow.isVisible = false
    }

    private data class DraftShown(val threadId: String, val panel: ThreadPanel)

    private companion object {
        const val RESIZE_SETTLE_MILLIS = 200
        const val ACTIVE_WINDOW = "activeWindow"
        const val PANEL_INSET = 64
        const val MIN_PANEL_WIDTH = 280
    }
}
