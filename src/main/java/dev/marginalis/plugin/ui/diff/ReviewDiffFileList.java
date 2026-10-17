package dev.marginalis.plugin.ui.diff;

import com.intellij.collaboration.ui.codereview.diff.MutableDiffRequestProcessor;
import com.intellij.openapi.ListSelection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The files of a pull/merge request review diff and a switch between them, through the platform's review diff
 * processor (behind both the GitHub and GitLab reviews).
 * <p>
 * Java on purpose: the processor is Kotlin-{@code internal} to the platform, which binds Kotlin callers only. From Java
 * it is a public class, and the Plugin Verifier checks these calls against every IDE, which reflection would hide.
 */
final class ReviewDiffFileList {

    private final MutableDiffRequestProcessor.Navigator<Object> navigator;
    private final List<?> changes;
    final List<String> paths;
    final int shownIndex;

    @SuppressWarnings("unchecked")
    private ReviewDiffFileList(MutableDiffRequestProcessor.Navigator<?> navigator) {
        this.navigator = (MutableDiffRequestProcessor.Navigator<Object>) navigator;
        ListSelection<?> selection = navigator.getCurrentList();
        changes = selection.getList();
        paths = changes.stream().map(change -> this.navigator.getChangePresentation(change).getFilePath().getPath()).toList();
        shownIndex = selection.getSelectedIndex();
    }

    /** @return null when {@code viewer} is not a review diff */
    static @Nullable ReviewDiffFileList of(Object viewer) {
        return viewer instanceof MutableDiffRequestProcessor processor ? new ReviewDiffFileList(processor.getNavigator()) : null;
    }

    void select(int index) {
        navigator.select(changes.get(index));
    }
}
