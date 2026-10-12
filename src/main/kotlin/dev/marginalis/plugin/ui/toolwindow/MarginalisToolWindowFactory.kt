package dev.marginalis.plugin.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.OccurenceNavigator
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.pom.Navigatable
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.components.BorderLayoutPanel
import dev.marginalis.core.CommentThread
import dev.marginalis.core.Faces
import dev.marginalis.core.Intent
import dev.marginalis.core.PathTrie
import dev.marginalis.core.Severity
import dev.marginalis.core.StripeBadge
import dev.marginalis.core.ThreadOrder
import dev.marginalis.core.ThreadStatus
import dev.marginalis.core.Turn
import dev.marginalis.core.TurnSignal
import dev.marginalis.core.TurnTally
import dev.marginalis.core.Walkthrough
import dev.marginalis.plugin.avatars.AvatarsListener
import dev.marginalis.plugin.store.Authors
import dev.marginalis.plugin.store.MarginalisStore
import dev.marginalis.plugin.ui.AgentPresenceGroup
import dev.marginalis.plugin.ui.FileLevelThreads
import dev.marginalis.plugin.ui.LiveByDefaultAction
import dev.marginalis.plugin.ui.MarginalisIcons
import dev.marginalis.plugin.ui.MarkdownRenderer
import dev.marginalis.plugin.ui.ParticipantStackIcon
import dev.marginalis.plugin.ui.RELAYED_STAYS_DELETED
import dev.marginalis.plugin.ui.StopAgentsGroup
import dev.marginalis.plugin.ui.SubmitRoundAction
import dev.marginalis.plugin.ui.WalkthroughNavigator
import dev.marginalis.plugin.ui.tab.ProjectTab
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

class MarginalisToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MarginalisToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
        val common = CommonActionsManager.getInstance()
        toolWindow.setTitleActions(
            listOf(
                CommentOnProjectAction(),
                FirstStepAction(panel),
                common.createPrevOccurenceAction(panel),
                common.createNextOccurenceAction(panel),
                LastStepAction(panel),
                ExpandSectionAction(panel),
                CollapseSectionAction(panel),
                FilterMenuAction(panel),
                AgentPresenceGroup(),
                SubmitRoundAction(),
                LiveByDefaultAction(),
                StopAgentsGroup(),
                ResolveAllAction(),
                ClearAllAction(),
            ),
        )

        val refreshBadge = {
            val threads = MarginalisStore.getInstance(project).threads.all()
            toolWindow.setIcon(MarginalisIcons.toolWindow(StripeBadge.of(threads)))
        }
        MarginalisStore.getInstance(project).threads.addListener {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && !toolWindow.isDisposed) refreshBadge()
            }
        }
        refreshBadge()

        val refreshToolbar: () -> Unit = { ActivityTracker.getInstance().inc() }
        val handBack = MarginalisStore.getInstance(project).handBack
        handBack.addListener(refreshToolbar)
        Disposer.register(content) { handBack.removeListener(refreshToolbar) }
    }
}

/**
 * Deliberately a toolbar action, not a node action: creating a project thread
 * must not depend on the tree already having a Project node.
 */
private class CommentOnProjectAction :
    AnAction("Comment on Project", "Start a margin thread about this project as a whole", MarginalisIcons.ProjectMark) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ProjectTab.draftNew(project)
    }
}

private class FirstStepAction(private val panel: MarginalisToolWindowPanel) :
    AnAction("First Step", "Go to the first step in this section", AllIcons.Actions.Play_first) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.canGoFirst()
    }

    override fun actionPerformed(e: AnActionEvent) = panel.goFirst()
}

private class LastStepAction(private val panel: MarginalisToolWindowPanel) :
    AnAction("Last Step", "Go to the last step in this section", AllIcons.Actions.Play_last) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.canGoLast()
    }

    override fun actionPerformed(e: AnActionEvent) = panel.goLast()
}

private class ExpandSectionAction(private val panel: MarginalisToolWindowPanel) :
    AnAction("Expand", "Expand the selected folder; each press one level further out, then every section", AllIcons.Actions.Expandall) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.hasSections()
        e.presentation.text = panel.expandText()
    }

    override fun actionPerformed(e: AnActionEvent) = panel.expandStep()
}

private class CollapseSectionAction(private val panel: MarginalisToolWindowPanel) :
    AnAction("Collapse", "Collapse the selected folder; each press one level further out, then every section", AllIcons.Actions.Collapseall) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.hasSections()
        e.presentation.text = panel.collapseText()
    }

    override fun actionPerformed(e: AnActionEvent) = panel.collapseStep()
}

internal enum class TreeFilter(
    val title: String,
    val empty: String,
    val matches: (CommentThread) -> Boolean,
) {
    ALL("All", "No margin threads yet", { true }),
    BLOCKERS("Blockers Only", "No blockers", { it.severity == Severity.BLOCKER }),
    AWAITING_USER("Awaiting You", "Nothing awaiting you", { it.turn() == Turn.USER_OWES }),
    AWAITING_AGENT("Awaiting Agent", "Nothing awaiting the agent", { it.turn() == Turn.AGENT_OWES }),
    FINDINGS("Findings", "No findings", { it.intent == Intent.FINDING }),
    GUIDANCE("Guidance", "No guidance", { it.intent == Intent.GUIDANCE }),
    QUESTIONS("Questions", "No questions", { it.intent == Intent.QUESTION }),
    FYI("FYI", "No FYIs", { it.intent == Intent.FYI }),
}

private class FilterMenuAction(private val panel: MarginalisToolWindowPanel) :
    DefaultActionGroup("Filter", "Filter the tree", AllIcons.General.Filter), DumbAware {
    init {
        isPopup = true
        templatePresentation.isPerformGroup = false
        TreeFilter.entries.forEach { lens ->
            add(object : ToggleAction(lens.title), DumbAware {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

                override fun isSelected(e: AnActionEvent): Boolean = panel.filter == lens

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) panel.filter = lens
                }
            })
        }
    }
}

private class ResolveAllAction : AnAction("Resolve All", "Mark every open thread resolved", AllIcons.Actions.Selectall) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            MarginalisStore.getInstance(project).threads.all().any { it.status !is ThreadStatus.Resolved }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val store = MarginalisStore.getInstance(project)
        val pending = store.threads.all().filter { it.status !is ThreadStatus.Resolved }
        if (pending.isEmpty()) return
        val blockers = pending.count { it.severity == Severity.BLOCKER }
        val blockerWarning =
            if (blockers > 0) " $blockers of them are blockers — resolve only if their outcomes genuinely landed." else ""
        val answer = Messages.showYesNoDialog(
            project,
            "Resolve all ${pending.size} open thread(s)?$blockerWarning Their gutter markers will be removed; " +
                "the threads remain in the Resolved log.",
            "Resolve All Margin Threads",
            if (blockers > 0) Messages.getWarningIcon() else Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        for (thread in pending) {
            thread.resolve(Authors.user)
            store.threads.notifyChanged(thread)
        }
    }
}

private class ClearAllAction : AnAction("Delete All", "Delete all threads, including resolved ones", AllIcons.Actions.GC) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && MarginalisStore.getInstance(project).threads.all().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val store = MarginalisStore.getInstance(project)
        val count = store.threads.all().size
        if (count == 0) return
        val blockers = store.threads.all().count { it.status !is ThreadStatus.Resolved && it.severity == Severity.BLOCKER }
        val blockerWarning = if (blockers > 0) " $blockers open blocker(s) are among them." else ""
        val answer = Messages.showYesNoDialog(
            project,
            "Delete all $count margin thread(s), including the resolved log?$blockerWarning This cannot be undone." +
                if (store.threads.deletedRelays.isNotEmpty()) {
                    " Relayed threads you deleted from the margin can then come back the next time an agent relays their PR."
                } else {
                    ""
                },
            "Delete All Margin Threads",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        store.clearAll() // marker cleanup happens in the store listener (deleted-thread branch)
    }
}

private sealed class NodeData {
    /** Names the node across rebuilds, which replace every node object. */
    abstract val key: String

    class Section(val title: String, val count: Int, val blockers: Int = 0, val guided: Boolean = false) : NodeData() {
        override val key get() = "section:$title"
    }

    class ProjectNode(val count: Int) : NodeData() {
        override val key get() = "project"
    }

    class DirNode(val name: String, val count: Int) : NodeData() {
        override val key get() = "dir:$name"
    }

    class FileNode(val name: String, val threads: List<CommentThread>) : NodeData() {
        override val key get() = "file:$name"
    }

    class ThreadNode(val thread: CommentThread, val walkthroughPrefix: String? = null) : NodeData() {
        override val key get() = "thread:${thread.id}"
    }
}

internal class MarginalisToolWindowPanel(private val project: Project) :
    JPanel(BorderLayout()), UiDataProvider, OccurenceNavigator, Disposable {

    private val tree = Tree()
    private val renderer = MarginalisTreeRenderer()

    /** Must be declared before init: init calls rebuild(), which reads it. */
    var filter: TreeFilter = TreeFilter.ALL
        set(value) {
            field = value
            rebuild()
        }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = renderer
        tree.emptyText.text = "No margin threads yet"
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) selectedThread()?.let { navigateTo(it) }
            }
        })
        tree.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                val path = tree.getPathForLocation(x, y) ?: return
                tree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                buildContextMenu(node)?.show(comp, x, y)
            }
        })
        add(JBScrollPane(tree), BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AvatarsListener.TOPIC, AvatarsListener { tree.repaint() })

        MarginalisStore.getInstance(project).threads.addListener {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) rebuild()
            }
        }
        rebuild()
    }

    override fun dispose() = Unit

    /** Powers F4 / Jump to Source. */
    override fun uiDataSnapshot(sink: DataSink) {
        val thread = selectedThread() ?: return
        sink[CommonDataKeys.NAVIGATABLE] = object : Navigatable {
            override fun navigate(requestFocus: Boolean) = navigateTo(thread)
            override fun canNavigate(): Boolean = true
            override fun canNavigateToSource(): Boolean = true
        }
    }

    fun selectFile(file: String) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        val node = root.preorderEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { candidate ->
                (candidate.userObject as? NodeData.FileNode)?.threads?.any { it.file == file } == true
            } ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun selectedThread(): CommentThread? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? NodeData.ThreadNode)?.thread
    }

    private fun threadsUnder(node: DefaultMutableTreeNode): List<CommentThread> =
        node.preorderEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .mapNotNull { (it.userObject as? NodeData.ThreadNode)?.thread }
            .distinctBy { it.id }
            .toList()

    private fun buildContextMenu(node: DefaultMutableTreeNode): JPopupMenu? {
        val data = node.userObject as? NodeData ?: return null
        val menu = JPopupMenu()
        when (data) {
            is NodeData.ThreadNode -> {
                val thread = data.thread
                menu.add(JMenuItem("Navigate").apply { addActionListener { navigateTo(thread) } })
                if (thread.status is ThreadStatus.Resolved) {
                    menu.add(JMenuItem("Reopen").apply {
                        addActionListener {
                            thread.reopen()
                            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
                        }
                    })
                } else {
                    menu.add(JMenuItem("Resolve").apply {
                        addActionListener {
                            thread.resolve(Authors.user)
                            MarginalisStore.getInstance(project).threads.notifyChanged(thread)
                        }
                    })
                }
                menu.add(JMenuItem("Delete…").apply { addActionListener { deleteThreads(listOf(thread)) } })
            }
            is NodeData.FileNode, is NodeData.DirNode -> {
                val threads = threadsUnder(node)
                if (data is NodeData.FileNode) {
                    threads.firstOrNull()?.file?.let { path ->
                        menu.add(JMenuItem("Comment on File").apply {
                            addActionListener { FileLevelThreads.startDraft(project, path) }
                        })
                    }
                }
                val open = threads.filter { it.status !is ThreadStatus.Resolved }
                if (open.isNotEmpty()) {
                    menu.add(JMenuItem("Resolve ${open.size} Open Thread(s)…").apply {
                        addActionListener { resolveThreads(open) }
                    })
                }
                menu.add(JMenuItem("Delete ${threads.size} Thread(s)…").apply {
                    addActionListener { deleteThreads(threads) }
                })
            }
            else -> return null
        }
        return menu
    }

    private fun resolveThreads(threads: List<CommentThread>) {
        val blockers = threads.count { it.severity == Severity.BLOCKER }
        val blockerWarning =
            if (blockers > 0) " $blockers of them are blockers — resolve only if their outcomes genuinely landed." else ""
        val answer = Messages.showYesNoDialog(
            project,
            "Resolve ${threads.size} open thread(s)?$blockerWarning",
            "Resolve Threads",
            if (blockers > 0) Messages.getWarningIcon() else Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        val store = MarginalisStore.getInstance(project)
        for (thread in threads) {
            thread.resolve(Authors.user)
            store.threads.notifyChanged(thread)
        }
    }

    private fun deleteThreads(threads: List<CommentThread>) {
        val blockers = threads.count { it.status !is ThreadStatus.Resolved && it.severity == Severity.BLOCKER }
        val blockerWarning = if (blockers > 0) " $blockers open blocker(s) are among them." else ""
        val answer = Messages.showYesNoDialog(
            project,
            "Delete ${threads.size} thread(s)?$blockerWarning Unlike resolving, deletion keeps no record. " +
                "This cannot be undone." +
                if (threads.any { it.isRelayedRoot }) " $RELAYED_STAYS_DELETED" else "",
            "Delete Threads",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        val store = MarginalisStore.getInstance(project)
        for (thread in threads) store.threads.delete(thread.id)
    }

    /** Cursor index is -1 when no thread node is selected. */
    private fun steps(): Pair<List<DefaultMutableTreeNode>, Int> {
        val none = emptyList<DefaultMutableTreeNode>() to -1
        val root = tree.model.root as? DefaultMutableTreeNode ?: return none
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val section = selected?.path?.getOrNull(1) as? DefaultMutableTreeNode
            ?: root.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().firstOrNull()
            ?: return none
        val nodes = section.preorderEnumeration().asSequence()
            .filterIsInstance<DefaultMutableTreeNode>()
            .filter { it.userObject is NodeData.ThreadNode }
            .toList()
        // The Guided tree groups steps by folder and file, so its top-to-bottom order is not the step order.
        val walk = if ((section.userObject as NodeData.Section).guided) {
            nodes.sortedWith(compareBy(Walkthrough.byStepNumber) { (it.userObject as NodeData.ThreadNode).thread })
        } else {
            nodes
        }
        val index = if (selected?.userObject is NodeData.ThreadNode) walk.indexOf(selected) else -1
        return walk to index
    }

    private fun goTo(node: DefaultMutableTreeNode) {
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        (node.userObject as? NodeData.ThreadNode)?.thread?.let { navigateTo(it) }
    }

    override fun hasNextOccurence(): Boolean = steps().let { (walk, i) -> i < walk.size - 1 && walk.isNotEmpty() }

    override fun hasPreviousOccurence(): Boolean = steps().second > 0

    override fun goNextOccurence(): OccurenceNavigator.OccurenceInfo? {
        val (walk, i) = steps()
        val target = walk.getOrNull(i + 1) ?: return null
        goTo(target)
        return OccurenceNavigator.OccurenceInfo.position(i + 2, walk.size)
    }

    override fun goPreviousOccurence(): OccurenceNavigator.OccurenceInfo? {
        val (walk, i) = steps()
        val target = walk.getOrNull(i - 1) ?: return null
        goTo(target)
        return OccurenceNavigator.OccurenceInfo.position(i, walk.size)
    }

    override fun getNextOccurenceActionName(): String = "Next Step"

    override fun getPreviousOccurenceActionName(): String = "Previous Step"

    fun canGoFirst(): Boolean = steps().let { (walk, i) -> walk.isNotEmpty() && i != 0 }

    fun canGoLast(): Boolean = steps().let { (walk, i) -> walk.isNotEmpty() && i != walk.size - 1 }

    fun goFirst() {
        steps().first.firstOrNull()?.let { goTo(it) }
    }

    fun goLast() {
        steps().first.lastOrNull()?.let { goTo(it) }
    }

    // Any thread change rebuilds the tree, and opening a step is one (it marks the step read); step navigation
    // walks from the selection, and folders the user folded must stay folded, so both are carried over by key.
    fun rebuild() {
        val selected = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.let(::keyPath)
        val wasExpanded = branchStates()
        val store = MarginalisStore.getInstance(project)
        store.syncLines()
        val threads = store.threads.all().filter(filter.matches)
        tree.emptyText.text = filter.empty
        renderer.faces = Authors.faces

        val root = DefaultMutableTreeNode()
        addGuidedSection(root, threads)
        addTreeSection(root, "Open", threads.filter { it.status is ThreadStatus.Open })
        addTreeSection(root, "Orphaned", threads.filter { it.status is ThreadStatus.Orphaned })
        addTreeSection(root, "Resolved", threads.filter { it.status is ThreadStatus.Resolved })

        tree.model = DefaultTreeModel(root)
        restoreExpansion(root, wasExpanded)
        selected?.let(::reselect)
    }

    private fun keyPath(node: DefaultMutableTreeNode): List<String> =
        node.path.drop(1).map { ((it as DefaultMutableTreeNode).userObject as NodeData).key }

    /** Expanded or not, per branch node of the current tree; empty before the first build (the stock model). */
    private fun branchStates(): Map<List<String>, Boolean> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptyMap()
        return root.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().drop(1)
            .filter { !it.isLeaf && it.userObject is NodeData }
            .associate { keyPath(it) to tree.isExpanded(TreePath(it.path)) }
    }

    /** A branch seen before keeps its state; a new one opens unless it is under Resolved. */
    private fun restoreExpansion(root: DefaultMutableTreeNode, wasExpanded: Map<List<String>, Boolean>) {
        val branches = root.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().drop(1)
            .filter { !it.isLeaf }
            .toList()
        val (open, folded) = branches.partition { node ->
            val section = (node.path[1] as DefaultMutableTreeNode).userObject as NodeData.Section
            wasExpanded[keyPath(node)] ?: (section.title != "Resolved")
        }
        open.forEach { tree.expandPath(TreePath(it.path)) }
        // Deepest first: expanding a child opened its parents, and a folded parent keeps its children's state.
        folded.sortedByDescending { it.level }.forEach { tree.collapsePath(TreePath(it.path)) }
    }

    private fun sections(): List<DefaultMutableTreeNode> =
        (tree.model.root as? DefaultMutableTreeNode)?.children()?.asSequence()
            ?.filterIsInstance<DefaultMutableTreeNode>()?.toList().orEmpty()

    /** The folders and the section around the selection, innermost first; files are not a level of their own. */
    private fun levelsAroundSelection(): List<DefaultMutableTreeNode> {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return emptyList()
        return selected.path.reversed().filterIsInstance<DefaultMutableTreeNode>().filter {
            it.userObject is NodeData.DirNode || it.userObject is NodeData.ProjectNode || it.userObject is NodeData.Section
        }
    }

    private fun isFullyExpanded(node: DefaultMutableTreeNode): Boolean =
        node.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>()
            .all { it.isLeaf || tree.isExpanded(TreePath(it.path)) }

    // Each press works one level further out from the selection, then on every section (a null target).
    private fun collapseTarget(): DefaultMutableTreeNode? =
        levelsAroundSelection().firstOrNull { tree.isExpanded(TreePath(it.path)) }

    private fun expandTarget(): DefaultMutableTreeNode? = levelsAroundSelection().firstOrNull { !isFullyExpanded(it) }

    fun collapseText(): String = "Collapse ${scopeOf(collapseTarget())}"

    fun expandText(): String = "Expand ${scopeOf(expandTarget())}"

    private fun scopeOf(target: DefaultMutableTreeNode?): String = when (target?.userObject) {
        null -> "All Sections"
        is NodeData.Section -> "Section"
        else -> "Folder"
    }

    fun hasSections(): Boolean = sections().isNotEmpty()

    fun collapseStep() {
        val target = collapseTarget()
        if (target == null) {
            sections().forEach(::collapseRecursively)
            return
        }
        collapseRecursively(target)
        // The next press starts from here, one level further out.
        tree.selectionPath = TreePath(target.path)
    }

    fun expandStep() {
        val target = expandTarget()
        if (target == null) {
            sections().forEach(::expandRecursively)
            return
        }
        expandRecursively(target)
    }

    // Like IntelliJ's Collapse All: reopening the node shows one level, not the folders as they were left.
    private fun collapseRecursively(node: DefaultMutableTreeNode) {
        node.preorderEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>()
            .filter { !it.isLeaf }
            .sortedByDescending { it.level }
            .forEach { tree.collapsePath(TreePath(it.path)) }
    }

    /** The same node by its key path, else the same thread in the same section (folders may group differently). */
    private fun reselect(keys: List<String>) {
        val root = tree.model.root as DefaultMutableTreeNode
        val exact = keys.fold<String, DefaultMutableTreeNode?>(root) { parent, key ->
            parent?.children()?.asSequence()?.filterIsInstance<DefaultMutableTreeNode>()
                ?.firstOrNull { (it.userObject as NodeData).key == key }
        }
        val node = exact ?: keys.last().takeIf { it.startsWith("thread:") }?.let { threadKey ->
            root.children().asSequence().filterIsInstance<DefaultMutableTreeNode>()
                .firstOrNull { (it.userObject as NodeData).key == keys.first() }
                ?.preorderEnumeration()?.asSequence()?.filterIsInstance<DefaultMutableTreeNode>()
                ?.firstOrNull { (it.userObject as NodeData).key == threadKey }
        } ?: return
        tree.selectionPath = TreePath(node.path)
    }

    private fun addGuidedSection(root: DefaultMutableTreeNode, allThreads: List<CommentThread>) {
        val openStops = allThreads.filter { it.status is ThreadStatus.Open && it.order != null }
        if (openStops.isEmpty()) return
        val walkthroughs = openStops.groupBy { it.walkthrough ?: "" }.toSortedMap()
        val labelNeeded = walkthroughs.size > 1
        for ((label, walkthroughThreads) in walkthroughs) {
            val title = if (label.isEmpty()) "Guided" else "Guided $label"
            val section = DefaultMutableTreeNode(
                NodeData.Section(
                    title,
                    walkthroughThreads.size,
                    walkthroughThreads.count { it.severity == Severity.BLOCKER },
                    guided = true,
                ),
            )
            val total = WalkthroughNavigator.stableTotal(project, walkthroughThreads.first())
                ?: walkthroughThreads.size
            val shownLabel = if (labelNeeded && label.isNotEmpty()) label else ""
            val prefixFor = { thread: CommentThread -> "($shownLabel${thread.order}/$total)" }
            addProjectNode(section, walkthroughThreads.filter { it.isProjectLevel }, prefixFor)
            val trie = PathTrie().apply { walkthroughThreads.forEach(::insert) }
            emitTrie(trie, section, prefixFor)
            root.add(section)
        }
    }

    private fun addTreeSection(root: DefaultMutableTreeNode, title: String, threads: List<CommentThread>) {
        if (threads.isEmpty()) return
        val blockers = threads.count { it.status !is ThreadStatus.Resolved && it.severity == Severity.BLOCKER }
        val section = DefaultMutableTreeNode(NodeData.Section(title, threads.size, blockers))
        addProjectNode(section, threads.filter { it.isProjectLevel })
        val trie = PathTrie().apply { threads.forEach(::insert) }
        emitTrie(trie, section)
        root.add(section)
    }

    private fun addProjectNode(
        section: DefaultMutableTreeNode,
        threads: List<CommentThread>,
        prefixFor: ((CommentThread) -> String)? = null,
    ) {
        if (threads.isEmpty()) return
        val node = DefaultMutableTreeNode(NodeData.ProjectNode(threads.size))
        val ordered =
            if (prefixFor != null) threads.sortedWith(compareBy({ it.order }, { it.createdAt }))
            else threads.sortedWith(ThreadOrder.byAnchor)
        for (thread in ordered) {
            node.add(DefaultMutableTreeNode(NodeData.ThreadNode(thread, prefixFor?.invoke(thread))))
        }
        section.add(node)
    }

    private fun emitTrie(
        trie: PathTrie,
        parent: DefaultMutableTreeNode,
        prefixFor: ((CommentThread) -> String)? = null,
    ) {
        val dirEntries =
            if (prefixFor != null) trie.dirs.entries.sortedBy { it.value.minOrder() }
            else trie.dirs.entries.toList()
        for ((name, child) in dirEntries) {
            var display = name
            var node = child
            while (node.files.isEmpty() && node.dirs.size == 1) {
                val (nextName, next) = node.dirs.entries.first()
                display = "$display/$nextName"
                node = next
            }
            val dirTreeNode = DefaultMutableTreeNode(NodeData.DirNode(display, node.threadCount()))
            parent.add(dirTreeNode)
            emitTrie(node, dirTreeNode, prefixFor)
        }
        val fileEntries =
            if (prefixFor != null) {
                trie.files.entries.sortedBy { e -> e.value.mapNotNull { it.order }.minOrNull() ?: Int.MAX_VALUE }
            } else {
                trie.files.entries.toList()
            }
        for ((fileName, fileThreads) in fileEntries) {
            val fileNode = DefaultMutableTreeNode(NodeData.FileNode(fileName, fileThreads))
            val ordered =
                if (prefixFor != null) fileThreads.sortedWith(compareBy({ it.order }, { it.createdAt }))
                else fileThreads.sortedWith(ThreadOrder.byAnchor)
            for (thread in ordered) {
                fileNode.add(DefaultMutableTreeNode(NodeData.ThreadNode(thread, prefixFor?.invoke(thread))))
            }
            parent.add(fileNode)
        }
    }

    private fun expandRecursively(node: DefaultMutableTreeNode) {
        tree.expandPath(TreePath(node.path))
        for (i in 0 until node.childCount) {
            expandRecursively(node.getChildAt(i) as DefaultMutableTreeNode)
        }
    }

    private fun navigateTo(thread: CommentThread) = WalkthroughNavigator.navigateTo(project, thread)
}

private class MarginalisTreeRenderer : TreeCellRenderer {
    var faces: Faces = Authors.faces
    private val words = RowWords()
    private val participants = JBLabel().apply {
        iconTextGap = JBUI.scale(2)
        border = JBUI.Borders.emptyLeft(6)
    }
    private val yourMove = turnLabel(Turn.USER_OWES)
    private val agentsMove = turnLabel(Turn.AGENT_OWES)
    private val cell = BorderLayoutPanel().addToCenter(words).addToRight(
        BorderLayoutPanel().addToLeft(participants).addToCenter(yourMove).addToRight(agentsMove).andTransparent(),
    )

    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        words.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
        val data = (value as? DefaultMutableTreeNode)?.userObject
        val spokenTurn = showTurnSignals(data)
        val spokenParticipants = showParticipants(data, RenderingUtil.getBackground(tree, selected))
        cell.accessibleContext.accessibleName =
            listOfNotNull(words.accessibleContext.accessibleName, spokenTurn, spokenParticipants)
                .filter { it.isNotBlank() }
                .joinToString(", ")
        val foreground = RenderingUtil.getForeground(tree, selected)
        yourMove.foreground = foreground
        agentsMove.foreground = foreground
        participants.foreground = if (selected) foreground else UIUtil.getContextHelpForeground()
        cell.isOpaque = words.isOpaque
        cell.background = words.background
        return cell
    }

    private fun showTurnSignals(data: Any?): String? = when (data) {
        is NodeData.FileNode -> {
            val tally = TurnTally.of(data.threads)
            yourMove.showCount(tally.user)
            agentsMove.showCount(tally.agent)
            TurnSignal.spoken(tally)
        }
        is NodeData.ThreadNode -> {
            yourMove.isVisible = false
            agentsMove.isVisible = false
            data.thread.turn()?.let(TurnSignal::spoken)
        }
        else -> {
            yourMove.isVisible = false
            agentsMove.isVisible = false
            null
        }
    }

    private fun showParticipants(data: Any?, rowBackground: Color): String? {
        val stack = (data as? NodeData.ThreadNode)?.thread?.let(faces::participants)
        participants.isVisible = !stack?.faces.isNullOrEmpty()
        if (stack == null || stack.faces.isEmpty()) return null
        participants.icon = ParticipantStackIcon(stack.faces) { rowBackground }
        participants.text = if (stack.more > 0) "+${stack.more}" else null
        val names = stack.faces.map { it.name } + listOfNotNull(stack.more.takeIf { it > 0 }?.let { "$it more" })
        return "with ${names.joinToString(", ")}"
    }

    private fun turnLabel(turn: Turn) = JBLabel(MarginalisIcons.turnSignal(turn)).apply {
        iconTextGap = JBUI.scale(2)
        border = JBUI.Borders.emptyLeft(6)
    }

    private fun JBLabel.showCount(count: Int) {
        isVisible = count > 0
        text = count.toString()
    }
}

private class RowWords : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is NodeData.Section -> {
                append(data.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${data.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.blockers > 0) {
                    append("  ·  ${data.blockers} blocker${if (data.blockers > 1) "s" else ""}", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }
            is NodeData.ProjectNode -> {
                icon = MarginalisIcons.ProjectMark
                append("Project", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${data.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is NodeData.DirNode -> {
                icon = AllIcons.Nodes.Folder
                append(data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${data.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is NodeData.FileNode -> {
                icon = FileTypeManager.getInstance().getFileTypeByFileName(data.name).icon
                    ?: AllIcons.FileTypes.Any_type
                append(data.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            is NodeData.ThreadNode -> {
                val thread = data.thread
                icon = MarginalisIcons.withLeadingTurnSignal(MarginalisIcons.markOf(listOf(thread)), thread.turn())
                if (data.walkthroughPrefix != null) {
                    append("${data.walkthroughPrefix}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                val where = thread.line?.let { "L${it + 1}" } ?: if (thread.isProjectLevel) "project" else "file"
                append("$where  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val preview = MarkdownRenderer.previewText(thread.messages.firstOrNull()?.body ?: "")
                append(
                    StringUtil.shortenTextWithEllipsis(preview, 70, 0),
                    if (thread.severity == Severity.NIT) SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES,
                )
            }
            else -> {}
        }
    }
}
