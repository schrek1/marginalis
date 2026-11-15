package dev.marginalis.plugin.ui.diff;

import com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.vcs.changes.ui.PresentableChange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

// Java because the platform's review diff processor, behind the GitHub and GitLab reviews, is Kotlin-internal:
// that binds Kotlin callers only, and from Java the Plugin Verifier still checks every call, which reflection would hide.
@SuppressWarnings("UnstableApiUsage")
final class ReviewDiffFileList implements ReviewFiles {

    static final String PROCESSOR_CLASS_NAME = "com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor";

    private final MutableDiffRequestProcessor.Navigator<Object> navigator;
    private final List<?> changes;
    private final List<String> absolutePaths;
    private final int shownIndex;

    @SuppressWarnings("unchecked")
    private ReviewDiffFileList(MutableDiffRequestProcessor.Navigator<?> navigator) {
        this.navigator = (MutableDiffRequestProcessor.Navigator<Object>) navigator;
        ListSelection<?> selection = navigator.getCurrentList();
        changes = selection.getList();
        absolutePaths = changes.stream().map(change -> pathOf(this.navigator.getChangePresentation(change))).toList();
        shownIndex = selection.getSelectedIndex();
    }

    static @Nullable ReviewFiles of(@NotNull Object viewer) {
        return viewer instanceof MutableDiffRequestProcessor processor ? new ReviewDiffFileList(processor.getNavigator()) : null;
    }

    private static @Nullable String pathOf(@Nullable PresentableChange change) {
        return change == null ? null : change.getFilePath().getPath();
    }

    @Override
    public @NotNull List<String> getAbsolutePaths() {
        return absolutePaths;
    }

    @Override
    public int getShownIndex() {
        return shownIndex;
    }

    @Override
    public void show(int index) {
        navigator.select(changes.get(index));
    }
}
