package dev.marginalis.core

import dev.marginalis.core.ProjectTabStartup.TabState

object ProjectTabClosing {

    fun closesTheTab(shows: MarginTabShows, threads: List<CommentThread>, shown: TabState): Boolean =
        shows == MarginTabShows.WITH_SOMETHING_IN_IT && shown == TabState.OPEN && !ProjectTabStartup.hasOpenProjectThread(threads)
}
