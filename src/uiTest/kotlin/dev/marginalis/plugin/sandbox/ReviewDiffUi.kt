package dev.marginalis.plugin.sandbox

import dev.marginalis.plugin.sandbox.SandboxUi.EDITORS
import dev.marginalis.plugin.sandbox.SandboxUi.js
import dev.marginalis.plugin.sandbox.SandboxUi.lit

/**
 * Review-diff steps (a port of `sandbox/review_diff_ui.py`, same JavaScript): a diff driven by the platform's
 * `MutableDiffRequestProcessor`, the processor behind the GitHub and GitLab review diffs.
 *
 * The sandbox has no GitHub/GitLab review to open, so [openReviewDiffWithFiles] builds the same kind of diff from the
 * platform alone: the processor's `Navigator` lists several project files, each shown as a revision copy (the right
 * side only names the project file), opened in an editor tab like a review. Switching files is asynchronous as in a
 * real review: a loading request first, the file's diff on a later EDT turn.
 */
object ReviewDiffUi {

    const val PROCESSOR = "com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor"

    data class ReviewFile(val path: String, val before: String, val after: String)

    private const val REVIEW_DIFF = """
function collaborationLoader() {
    var modules = com.intellij.ide.plugins.PluginManagerCore.getPluginSet().getUnsortedEnabledModules().iterator();
    while (modules.hasNext()) {
        var module = modules.next();
        if (String(module.moduleId.getName()) == 'intellij.platform.collaborationTools') return module.getPluginClassLoader();
    }
    throw 'the intellij.platform.collaborationTools module is not loaded';
}
function selectedReviewViewer() {
    var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
    if (selected == null || !(selected instanceof com.intellij.diff.editor.DiffEditorViewerFileEditor)) return null;
    return selected.getEditorViewer();
}
"""

    private val OPEN = """
var files = __FILES__;
var current = __SHOWN__;
var loader = collaborationLoader();
var processorType = java.lang.Class.forName('__PROCESSOR__', false, loader);
var navigatorType = java.lang.Class.forName('__PROCESSOR__${'$'}Navigator', false, loader);
var changeType = java.lang.Class.forName('com.intellij.openapi.vcs.changes.ui.PresentableChange', false, loader);
var projectType = java.lang.Class.forName('com.intellij.openapi.project.Project', false, loader);
var processor = processorType.getConstructor(projectType).newInstance(project);
var factory = com.intellij.diff.DiffContentFactory.getInstance();
var changes = new java.util.ArrayList();
for (var i = 0; i < files.length; i++) changes.add(new java.lang.String(files[i].path));

function requestFor(index) {
    var file = files[index];
    var left = factory.create(project, file.before, projectFile(file.path));
    var right = factory.create(project, file.after, projectFile(file.path));
    return new com.intellij.diff.requests.SimpleDiffRequest(file.path, left, right, 'base', 'PR');
}
function show(index) {
    if (index < 0 || index >= files.length) return;
    current = index;
    processor.applyRequest(new com.intellij.diff.requests.LoadingDiffRequest(files[index].path));
    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(function () {
        if (current == index && !processor.isDisposed()) processor.applyRequest(requestFor(index));
    });
}
function objectMethod(self, name, args) {
    if (name == 'hashCode') return java.lang.Integer.valueOf(java.lang.System.identityHashCode(self));
    if (name == 'equals') return java.lang.Boolean.valueOf(args[0] != null
        && java.lang.System.identityHashCode(self) == java.lang.System.identityHashCode(args[0]));
    if (name == 'toString') return 'sandbox review diff';
    return null;
}
function proxy(type, handle) {
    var types = java.lang.reflect.Array.newInstance(java.lang.Class, 1);
    types[0] = type;
    return java.lang.reflect.Proxy.newProxyInstance(loader, types, new java.lang.reflect.InvocationHandler({ invoke: handle }));
}
function presentation(path) {
    return proxy(changeType, function (self, method, args) {
        var name = String(method.getName());
        if (name == 'getFilePath') return new com.intellij.openapi.vcs.LocalFilePath(String(projectFile(path).getPath()), false);
        if (name == 'getFileStatus') return com.intellij.openapi.vcs.FileStatus.MODIFIED;
        return objectMethod(self, name, args);
    });
}
var navigator = proxy(navigatorType, function (self, method, args) {
    var name = String(method.getName());
    if (name == 'getCurrentList') return com.intellij.openapi.ListSelection.createAt(changes, current);
    if (name == 'getChangePresentation') return presentation(String(args[0]));
    if (name == 'select') { show(changes.indexOf(args[0])); return null; }
    if (name == 'selectNext') { show(current + 1); return null; }
    if (name == 'selectPrev') { show(current - 1); return null; }
    return objectMethod(self, name, args);
});
processor.setNavigator(navigator);
show(current);
var diffFile = new JavaAdapter(com.intellij.diff.editor.DiffViewerVirtualFile,
    { createViewer: function (forProject) { return processor; } }, 'Sandbox review diff');
com.intellij.diff.editor.DiffEditorTabFilesManager.getInstance(project).showDiffFile(diffFile, true);
String(true)
"""

    /** Opens a review diff over `files`, showing `files[shown]`. */
    fun openReviewDiffWithFiles(files: List<ReviewFile>, shown: Int = 0) {
        val spec = lit(files.map { mapOf("path" to it.path, "before" to it.before, "after" to it.after) })
        js(EDITORS + REVIEW_DIFF + OPEN.replace("__FILES__", spec).replace("__SHOWN__", shown.toString()).replace("__PROCESSOR__", PROCESSOR))
    }

    /** Class name of the viewer behind the selected diff tab, or `NONE`. */
    fun selectedDiffProcessor(): String = js(
        REVIEW_DIFF + """
        var viewer = selectedReviewViewer();
        String(viewer == null ? 'NONE' : viewer.getClass().getName())
        """,
    )

    fun diffShows(after: String): Boolean = js(EDITORS + "String(diffEditorShowing(${lit(after)}) != null)") == "true"

    /** 0-based caret line in the diff editor showing `after`. */
    fun diffCaretLine(after: String): Int = js(
        EDITORS + """
        var editor = diffEditorShowing(${lit(after)});
        if (editor == null) throw 'no diff editor shows that text';
        String(editor.getCaretModel().getLogicalPosition().line)
        """,
    ).toInt()

    fun threadIdsOpenInFileTab(file: String): List<String> = SandboxUi.stringList(
        js(
            EDITORS + """
            var editor = textEditorOf('$file');
            var ids = [];
            function collect(c) {
                if (c.getClass().getName() == 'dev.marginalis.plugin.ui.ThreadPanel') ids.push(String(threadOf(c).getId()));
                var children = c.getComponents ? c.getComponents() : [];
                for (var i = 0; i < children.length; i++) collect(children[i]);
            }
            if (editor != null) collect(editor.getContentComponent());
            String(JSON.stringify(ids))
            """,
        ),
    )
}
