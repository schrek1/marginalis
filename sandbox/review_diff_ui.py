"""Review-diff steps for the sandbox (MARG-17), next to `sandbox_ui`: a diff driven by the platform's
`MutableDiffRequestProcessor`, the processor behind the GitHub and GitLab review diffs.

The sandbox has no GitHub/GitLab review to open, so `open_review_diff_with_files` builds the same kind of diff from
the platform alone: a `MutableDiffRequestProcessor` (collaborationTools module) whose `Navigator` lists several
project files, each shown as a revision copy (the right side only names the project file), opened in an editor tab
like a review. Switching files is asynchronous as in a real review: a loading request first, the file's diff on a
later EDT turn.

Fold these into `sandbox_ui.py` once this branch carries the in-repo harness (MARG-29).
"""
from __future__ import annotations

import json

import sandbox_ui as ui

PROCESSOR = "com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor"

_REVIEW_DIFF = """
function collaborationLoader() {
    var modules = com.intellij.ide.plugins.PluginManagerCore.getPluginSet().getUnsortedEnabledModules().iterator();
    while (modules.hasNext()) {
        var module = modules.next();
        // 2025.2 (the floor) names a content module by the moduleName field, later platforms by moduleId.
        var name = module.moduleId ? module.moduleId.getName() : module.moduleName;
        if (String(name) == 'intellij.platform.collaborationTools') return module.getPluginClassLoader();
    }
    throw 'the intellij.platform.collaborationTools module is not loaded';
}
function selectedReviewViewer() {
    var selected = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).getSelectedEditor();
    if (selected == null || !(selected instanceof com.intellij.diff.editor.DiffEditorViewerFileEditor)) return null;
    return selected.getEditorViewer();
}
"""

_OPEN = """
var files = __FILES__;
var current = __SHOWN__;
var loader = collaborationLoader();
var processorType = java.lang.Class.forName('__PROCESSOR__', false, loader);
var navigatorType = java.lang.Class.forName('__PROCESSOR__$Navigator', false, loader);
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


def open_review_diff_with_files(files: list[tuple[str, str, str]], shown: int = 0) -> None:
    """Opens a review diff over `files` — (project path, before text, after text) each — showing `files[shown]`."""
    spec = json.dumps([{"path": path, "before": before, "after": after} for path, before, after in files])
    script = (_OPEN.replace("__FILES__", spec).replace("__SHOWN__", str(shown)).replace("__PROCESSOR__", PROCESSOR))
    ui.js(ui._EDITORS + _REVIEW_DIFF + script)


def selected_diff_processor() -> str:
    """Class name of the viewer behind the selected diff tab, or `NONE`."""
    return ui.js(_REVIEW_DIFF + """
        var viewer = selectedReviewViewer();
        String(viewer == null ? 'NONE' : viewer.getClass().getName())
    """)


def diff_shows(after: str) -> bool:
    return ui.js(ui._EDITORS + f"String(diffEditorShowing({json.dumps(after)}) != null)") == "true"


def diff_caret_line(after: str) -> int:
    """0-based caret line in the diff editor showing `after`."""
    return int(ui.js(ui._EDITORS + f"""
        var editor = diffEditorShowing({json.dumps(after)});
        if (editor == null) throw 'no diff editor shows that text';
        String(editor.getCaretModel().getLogicalPosition().line)
    """))


def thread_ids_open_in_file_tab(file: str) -> list[str]:
    raw = ui.js(ui._EDITORS + f"""
        var editor = textEditorOf('{file}');
        var ids = [];
        function collect(c) {{
            if (c.getClass().getName() == 'dev.marginalis.plugin.ui.ThreadPanel') ids.push(String(threadOf(c).getId()));
            var children = c.getComponents ? c.getComponents() : [];
            for (var i = 0; i < children.length; i++) collect(children[i]);
        }}
        if (editor != null) collect(editor.getContentComponent());
        String(JSON.stringify(ids))
    """)
    return json.loads(raw)
