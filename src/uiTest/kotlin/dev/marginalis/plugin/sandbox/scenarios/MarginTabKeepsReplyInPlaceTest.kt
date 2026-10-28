package dev.marginalis.plugin.sandbox.scenarios

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.marginalis.plugin.sandbox.Agent
import dev.marginalis.plugin.sandbox.SandboxScenario
import dev.marginalis.plugin.sandbox.SandboxUi
import dev.marginalis.plugin.sandbox.readUntil
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The Margin tab keeps what you look at in place while other threads change (MARG-45).
 *
 * Three tall project threads; the agent posts a long message into the expanded thread above the one being looked at.
 * The card being written in (a reply draft, then a new project thread's draft) must keep its on-screen position, its
 * focus and its caret; without a draft, the topmost visible card must keep its position.
 *
 * The sandbox window is never the active window, so no real focus can land in it. Where a plain focus request is not
 * remembered, the scenario records the reply box as the window's most recent focus owner, which is exactly the state
 * of a user who typed there and switched to another application.
 */
class MarginTabKeepsReplyInPlaceTest : SandboxScenario("margin_tab_keeps_reply_in_place") {

    private var upper = ""
    private var middle = ""
    private var lower = ""

    @Test
    @Order(1)
    fun threeTallThreads() = case("three_tall_threads") {
        // Each new message must make the thread above taller; folding the read ones before it would shrink it instead.
        SandboxUi.chooseLongThreadFolding("FOLD_NOTHING")
        val created = listOf("one" to 30, "two" to 12, "three" to 30).map { (name, paragraphs) ->
            Agent.agentStartsProjectThread(tallBody("Thread $name", paragraphs))
        }
        SandboxUi.openMarginTab(created[0])
        // Whatever the tab's sort order, the message goes into the card above the one being looked at.
        val order = readUntil("the three threads listed in the Margin tab", read = { SandboxUi.marginTabOrder().filter { it in created } }) {
            it.size == created.size
        }
        upper = order[0]
        middle = order[1]
        lower = order[2]
        listOf(upper, middle).forEach(SandboxUi::openMarginTab)
        val expanded = readUntil("the threads above and in view expanded", read = { expandedThreadIds() }) { upper in it && middle in it }
        check("the threads above and in view are expanded", upper in expanded && middle in expanded, expanded)
    }

    @Test
    @Order(2)
    fun readingKeepsTheTopmostCard() = case("reading_keeps_the_topmost_card") {
        focusOutsideCards()
        // The card in view straddles the view top; the growing thread is entirely above it.
        scrollTo("top(${SandboxUi.lit(middle)}) + 10")
        settle()
        val (before, after) = messageAbove(middle, upper, "1-reading")
        check(
            "reading without a draft, the topmost visible card stays where it was on screen",
            abs(after.offset - before.offset) <= TOLERANCE_PX, before to after,
        )
    }

    @Test
    @Order(3)
    fun draftInAThreadStays() = case("draft_in_a_thread_stays") {
        val focus = startDraftIn(middle)
        settle()
        // The thread above straddles the view top, so only the card being written in can serve as the anchor.
        scrollTo("Math.max(top(${SandboxUi.lit(upper)}) + 20, top(${SandboxUi.lit(middle)}) - h / 2)")
        settle()
        val (before, after) = messageAbove(middle, upper, "2-draft-in-thread")
        report("      focus $focus")
        check(
            "a reply draft stays at the same place on screen when the thread above grows",
            abs(after.offset - before.offset) <= TOLERANCE_PX, before to after,
        )
        check("the reply box keeps the focus", after.focused, after)
        check("the reply box keeps the draft and the caret", after.text == DRAFT_TEXT && after.caret == DRAFT_CARET, after)
    }

    @Test
    @Order(4)
    fun draftInANewProjectThreadStays() = case("draft_in_a_new_project_thread_stays") {
        SandboxUi.userStartsNewProjectThread()
        settle()
        val focus = startDraftIn(DRAFT)
        settle()
        val order = SandboxUi.marginTabOrder().filter { it in setOf(upper, middle, lower, DRAFT) }
        val at = order.indexOf(DRAFT)
        // The expanded thread right above the draft; with new threads on top nothing is above it, so the one below.
        val grower = if (at > 0) order[at - 1] else order[at + 1]
        if (grower == lower) {
            SandboxUi.openMarginTab(grower)
            settle()
        }
        scrollTo("top('draft') - h / 2")
        settle()
        val (before, after) = messageAbove(DRAFT, grower, "3-draft-new-thread")
        report("      focus $focus, draft at $at of $order")
        check(
            "a new project thread's draft stays at the same place on screen when another thread grows",
            abs(after.offset - before.offset) <= TOLERANCE_PX, before to after,
        )
        check(
            "the new thread's reply box keeps the focus, the draft and the caret",
            after.focused && after.text == DRAFT_TEXT && after.caret == DRAFT_CARET, after,
        )
    }

    private data class OnScreen(val offset: Double, val viewTop: Double, val focused: Boolean, val text: String?, val caret: Int?, val grower: Int)

    /** The agent posts into `grower`; `after` is read once it has grown and `watched` is back in place, as every case checks. */
    private fun messageAbove(watched: String, grower: String, shot: String): Pair<OnScreen, OnScreen> {
        val before = onScreen(watched, grower)
        shot("$shot-before")
        Agent.agentReplies(grower, tallBody("Agent update", 10))
        // Time for the re-render to take the draft's focus or caret, which it must not.
        settle()
        val after = readUntil("$shot: the thread above grown, $watched in place", read = { onScreen(watched, grower) }) { now ->
            now.grower > before.grower && abs(now.offset - before.offset) <= TOLERANCE_PX
        }
        shot("$shot-after")
        check("$shot: the message made the thread above taller", after.grower > before.grower, before.grower to after.grower)
        return before to after
    }

    /** Where `key` sits in the view, whether it still holds the focus and its draft, and the height of `grower`. */
    private fun onScreen(key: String, grower: String): OnScreen {
        val shown = tabJs(
            """
            var host = target(${SandboxUi.lit(key)});
            var viewport = viewportOf();
            var area = replyAreaOf(host);
            var editor = area == null ? null : area.getEditor();
            var focused = windowOf(host).getMostRecentFocusOwner();
            String(JSON.stringify({
                offset: topIn(host) - viewport.getViewPosition().y,
                viewTop: viewport.getViewPosition().y,
                focused: focused != null && javax.swing.SwingUtilities.isDescendingFrom(focused, host),
                text: area == null ? null : String(area.getText()),
                caret: editor == null ? null : read(function () { return editor.getCaretModel().getOffset(); }) | 0,
                grower: target(${SandboxUi.lit(grower)}).getHeight()
            }))
            """,
        ).asJsonObject
        return OnScreen(
            offset = shown.get("offset").asDouble,
            viewTop = shown.get("viewTop").asDouble,
            focused = shown.get("focused").asBoolean,
            text = shown.get("text")?.takeUnless { it.isJsonNull }?.asString,
            caret = shown.get("caret")?.takeUnless { it.isJsonNull }?.asInt,
            grower = shown.get("grower").asInt,
        )
    }

    private fun expandedThreadIds(): List<String> = tabJs(
        """
        var order = [];
        var children = stackOf().getComponents();
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (String(child.getClass().getSimpleName()) == 'ThreadCard' && child.getPanel() != null)
                order.push(String(child.getThread().getId()));
        }
        String(JSON.stringify(order))
        """,
    ).asJsonArray.map { it.asString }

    /** Opens the composer (the real "Reply…" link when the card shows one), types the draft and places the caret. */
    private fun startDraftIn(key: String): String {
        tabJs(
            """
            var link = find(target(${SandboxUi.lit(key)}), function (c) {
                return c instanceof com.intellij.ui.components.ActionLink && c.getText() == 'Reply…';
            });
            if (link != null) link.doClick();
            String(JSON.stringify(link != null))
            """,
        )
        // Only a laid-out, showing reply box can take the focus.
        Thread.sleep(1_000)
        return tabJs(
            """
            var host = target(${SandboxUi.lit(key)});
            var area = replyAreaOf(host);
            area.setText(${SandboxUi.lit(DRAFT_TEXT)});
            var editor = area.getEditor();
            if (editor == null) throw 'the reply box has no editor yet';
            asUser(function () { editor.getCaretModel().moveToOffset($DRAFT_CARET); });
            String(JSON.stringify({ focus: rememberFocus(editor.getContentComponent()) }))
            """,
        ).asJsonObject.get("focus").asString
    }

    private fun focusOutsideCards() {
        tabJs("String(JSON.stringify({ focus: rememberFocus(fieldOf(marginPanel(), 'root')) }))")
    }

    /** Sets the tab's scroll position from a JS expression over `h` (view height) and `top(key)` / `bottom(key)`. */
    private fun scrollTo(viewTop: String) {
        tabJs(
            """
            var viewport = viewportOf();
            var h = viewport.getExtentSize().height;
            function top(key) { return topIn(target(key)); }
            function bottom(key) { return topIn(target(key)) + target(key).getHeight(); }
            viewport.setViewPosition(new java.awt.Point(0, Math.max(0, Math.round($viewTop))));
            String(JSON.stringify(viewport.getViewPosition().y))
            """,
        )
    }

    private fun tabJs(script: String) = JsonParser.parseString(SandboxUi.marginJs(TAB + script))

    private companion object {
        const val DRAFT = "draft"
        const val TOLERANCE_PX = 1
        const val DRAFT_TEXT = "Half-written reply: the release can go out once the"
        const val DRAFT_CARET = 20

        fun tallBody(title: String, paragraphs: Int) =
            (listOf("**$title**") + (1..paragraphs).map { n -> "$title, paragraph $n: notes on the release plan." }).joinToString("\n\n")

        fun settle() = Thread.sleep(2_000)

        val TAB = SandboxUi.FIND + """
function stackOf() { return fieldOf(marginPanel(), 'stack'); }
function viewportOf() { return fieldOf(marginPanel(), 'scroll').getViewport(); }
function target(key) {
    if (key == 'draft') return fieldOf(fieldOf(marginPanel(), 'draftShown'), 'panel');
    var card = fieldOf(marginPanel(), 'cards').get(key);
    if (card == null) throw 'no card for ' + key;
    return card;
}
function topIn(component) { return javax.swing.SwingUtilities.convertPoint(component, 0, 0, stackOf()).y; }
function replyAreaOf(component) {
    return find(component, function (c) { return c instanceof com.intellij.ui.EditorTextField; });
}
function windowOf(component) { return javax.swing.SwingUtilities.getWindowAncestor(component); }
function rememberFocus(component) {
    component.requestFocusInWindow();
    var window = windowOf(component);
    if (window.getMostRecentFocusOwner() === component) return 'requested';
    var types = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Class'), 1);
    types[0] = java.lang.Class.forName('java.awt.Component');
    var remember = java.lang.Class.forName('java.awt.KeyboardFocusManager').getDeclaredMethod('setMostRecentFocusOwner', types);
    remember.setAccessible(true);
    var args = java.lang.reflect.Array.newInstance(java.lang.Class.forName('java.lang.Object'), 1);
    args[0] = component;
    remember.invoke(null, args);
    if (window.getMostRecentFocusOwner() !== component) throw 'the focus owner could not be recorded';
    return 'recorded';
}
"""
    }
}
