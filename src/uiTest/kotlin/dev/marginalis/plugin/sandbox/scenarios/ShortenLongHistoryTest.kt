package dev.marginalis.plugin.sandbox.scenarios

import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import org.junit.jupiter.api.Test

/**
 * A long thread shortens its history: the read middle hides behind one line (MARG-12); a port of
 * `sandbox/scenarios/shorten_long_history.py`.
 *
 * The agent posts a seven-message thread. Unread, every message shows; read and reopened, the panel keeps the first
 * message and the last two and hides the four between behind one link; the link shows the full history, which stays
 * on the next visit, and Shorten History in the thread header hides the middle again.
 */
class ShortenLongHistoryTest : SandboxScenario("shorten_long_history") {

    @Test
    fun aReadLongThreadHidesItsMiddle() = case("run") {
        SandboxUi.showToolWindow()
        SandboxUi.openInEditor(FILE)
        val thread = Agent.agentAddsLineThread(FILE, 1, BODIES.first())
        BODIES.drop(1).forEach { body -> Agent.agentReplies(thread, body) }
        SandboxUi.openThreadInEditor(thread, FILE)
        settle()
        shot("1-unread")
        check("an unread long thread shows every message", shown(thread) == BODIES, shown(thread))
        check("an unread long thread hides nothing", historyLinks(thread).isEmpty(), historyLinks(thread))

        SandboxUi.userReads(thread)
        settle()
        check("reading does not hide messages under the reader", shown(thread) == BODIES, shown(thread))
        check("a read thread that is still open offers to shorten", historyAction(thread) == SHORTEN, historyAction(thread))

        reopen(thread)
        shot("2-read-reopened")
        val shortened = listOf(BODIES[0], BODIES[5], BODIES[6])
        check("a read long thread shows the first and the last two", shown(thread) == shortened, shown(thread))
        check("the four between hide behind one link", historyLinks(thread) == listOf(HIDDEN), historyLinks(thread))

        clickLink(thread, HIDDEN)
        settle()
        shot("3-full-history")
        check("the link shows the full history", shown(thread) == BODIES, shown(thread))
        check("the full history offers to shorten", historyAction(thread) == SHORTEN, historyAction(thread))

        reopen(thread)
        check("the full history stays on the next visit", shown(thread) == BODIES, shown(thread))

        SandboxUi.pressPanelAction(thread, SHORTEN)
        settle()
        shot("4-shortened")
        check("Shorten history hides the middle again", shown(thread) == shortened, shown(thread))
        check("a shortened thread offers the full history again", historyAction(thread) == FULL, historyAction(thread))
    }

    private fun shown(threadId: String): List<String> {
        val texts = SandboxUi.panelView(threadId).texts
        return BODIES.filter { it in texts }
    }

    private fun historyLinks(threadId: String): List<String> = SandboxUi.stringList(
        SandboxUi.js(
            SandboxUi.FIND + """
            var links = [];
            find(component, function (c) {
                if (c instanceof com.intellij.ui.components.ActionLink && /more messages?$/.test(String(c.getText())))
                    links.push(String(c.getText()));
                return false;
            });
            String(JSON.stringify(links))
            """,
            SandboxUi.threadPanel(threadId),
        ),
    )

    private fun clickLink(threadId: String, text: String) {
        val escaped = SandboxUi.lit(text)
        SandboxUi.js(
            SandboxUi.FIND + """
            var link = find(component, function (c) {
                return c instanceof com.intellij.ui.components.ActionLink && String(c.getText()) == $escaped;
            });
            if (link == null) throw 'no link ' + $escaped;
            link.doClick();
            String(true)
            """,
            SandboxUi.threadPanel(threadId),
        )
    }

    /** The thread header's history button as the toolbar last updated it, or null while it is hidden. */
    private fun historyAction(threadId: String): String? = SandboxUi.js(
        SandboxUi.FIND + """
        var button = find(component, function (c) {
            return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton
                && /History$/.test(String(c.getPresentation().getText())) && c.isVisible();
        });
        String(button == null ? '' : button.getPresentation().getText())
        """,
        SandboxUi.threadPanel(threadId),
    ).ifEmpty { null }

    private fun reopen(threadId: String) {
        SandboxUi.closeAllEditors()
        settle()
        SandboxUi.openInEditor(FILE)
        SandboxUi.openThreadInEditor(threadId, FILE)
        settle()
    }

    private companion object {
        const val FILE = "src/review_target.py"
        val BODIES = (1..7).map { n -> "Message $n of the long thread." }
        const val HIDDEN = "Show 4 more messages"
        const val SHORTEN = "Shorten History"
        const val FULL = "Show Full History"

        fun settle() = Thread.sleep(2_000)
    }
}
