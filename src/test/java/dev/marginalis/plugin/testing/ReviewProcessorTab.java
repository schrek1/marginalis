package dev.marginalis.plugin.testing;

import com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.editor.DiffViewerVirtualFile;
import com.intellij.diff.impl.DiffEditorViewer;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.requests.LoadingDiffRequest;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.ui.PresentableChange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A pull/merge request review in an editor tab, run by the platform's review diff processor (the one behind the GitHub
 * and GitLab reviews) over revision copies of project files. As in a real review, switching files shows a loading
 * request first and the file's diff on a later EDT turn.
 *
 * Java because the processor is Kotlin-internal to the platform.
 */
@SuppressWarnings("UnstableApiUsage")
public final class ReviewProcessorTab {

    public record ReviewedFile(String path, String before, String after) {
    }

    private final Project project;
    private final List<ReviewedFile> files;
    private MutableDiffRequestProcessor processor;
    private int shown = -1;
    private DiffRequest shownRequest;

    private ReviewProcessorTab(Project project, List<ReviewedFile> files) {
        this.project = project;
        // Not List.copyOf: ListSelection asks the list whether it contains null, which an immutable copy refuses.
        this.files = new ArrayList<>(files);
    }

    /** Opens the review on its first file. */
    public static ReviewProcessorTab open(Project project, FileEditorManager editors, List<ReviewedFile> files) {
        ReviewProcessorTab review = new ReviewProcessorTab(project, files);
        editors.openFile(new DiffViewerVirtualFile("Review") {
            @Override
            public @NotNull DiffEditorViewer createViewer(@NotNull Project project) {
                return review.createProcessor();
            }
        }, true);
        return review;
    }

    public @Nullable String shownFile() {
        return shown < 0 ? null : files.get(shown).path();
    }

    /** Shows the same file's diff again in a new viewer, as the review does whenever its data refreshes. */
    public void refresh() {
        processor.applyRequest(Objects.requireNonNull(shownRequest, "the review shows no file's diff yet"));
    }

    private MutableDiffRequestProcessor createProcessor() {
        processor = new MutableDiffRequestProcessor(project);
        processor.setNavigator(new FileNavigator());
        show(0);
        return processor;
    }

    private void show(int index) {
        shown = index;
        ReviewedFile file = files.get(index);
        processor.applyRequest(new LoadingDiffRequest(file.path()));
        ApplicationManager.getApplication().invokeLater(() -> {
            if (shown != index || processor.isDisposed()) return;
            shownRequest = requestFor(file);
            processor.applyRequest(shownRequest);
        });
    }

    private DiffRequest requestFor(ReviewedFile file) {
        DiffContentFactory contents = DiffContentFactory.getInstance();
        VirtualFile projectFile = projectFile(file.path());
        return new SimpleDiffRequest(
            file.path(),
            contents.create(project, file.before(), projectFile),
            contents.create(project, file.after(), projectFile),
            "Base",
            "Pull request"
        );
    }

    private VirtualFile projectFile(String path) {
        VirtualFile root = Objects.requireNonNull(ProjectUtil.guessProjectDir(project));
        return Objects.requireNonNull(root.findFileByRelativePath(path), path);
    }

    private final class FileNavigator implements MutableDiffRequestProcessor.Navigator<ReviewedFile> {

        @Override
        public @NotNull ListSelection<ReviewedFile> getCurrentList() {
            return ListSelection.createAt(files, shown);
        }

        @Override
        public void selectNext(boolean fromUpdate) {
            if (shown + 1 < files.size()) show(shown + 1);
        }

        @Override
        public void selectPrev(boolean fromUpdate) {
            if (shown > 0) show(shown - 1);
        }

        @Override
        public void select(ReviewedFile file) {
            show(files.indexOf(file));
        }

        @Override
        public @NotNull PresentableChange getChangePresentation(ReviewedFile file) {
            FilePath path = VcsUtil.getFilePath(projectFile(file.path()));
            return new PresentableChange() {
                @Override
                public @NotNull FilePath getFilePath() {
                    return path;
                }

                @Override
                public @NotNull FileStatus getFileStatus() {
                    return FileStatus.MODIFIED;
                }
            };
        }
    }
}
