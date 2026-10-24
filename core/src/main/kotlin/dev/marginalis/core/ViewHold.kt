package dev.marginalis.core

object ViewHold {

    data class Span<K>(val key: K, val top: Int, val bottom: Int) {
        fun overlaps(viewTop: Int, viewHeight: Int): Boolean = bottom > viewTop && top < viewTop + viewHeight
    }

    data class Held<K>(val key: K, val offsetFromViewTop: Int)

    data class WritingIn<K>(val card: K, val replyBox: Span<K>? = null)

    fun <K> pick(cards: List<Span<K>>, viewTop: Int, viewHeight: Int, clicked: K? = null, writing: WritingIn<K>? = null): Held<K>? {
        val inView = cards.filter { it.overlaps(viewTop, viewHeight) }
        val chosen = inView.firstOrNull { clicked != null && it.key == clicked }
            ?: writing?.let { heldWhileWriting(it, inView, viewTop, viewHeight) }
            ?: topmostWhileScrolledDown(inView, viewTop)
            ?: return null
        return Held(chosen.key, chosen.top - viewTop)
    }

    fun <K> viewTopKeeping(held: Held<K>, heldTop: Int, contentHeight: Int, viewHeight: Int): Int =
        (heldTop - held.offsetFromViewTop).coerceIn(0, (contentHeight - viewHeight).coerceAtLeast(0))

    private fun <K> heldWhileWriting(writing: WritingIn<K>, inView: List<Span<K>>, viewTop: Int, viewHeight: Int): Span<K>? =
        writing.replyBox?.takeIf { it.overlaps(viewTop, viewHeight) } ?: inView.firstOrNull { it.key == writing.card }

    private fun atTheVeryTop(viewTop: Int): Boolean = viewTop <= 0

    private fun <K> topmostWhileScrolledDown(inView: List<Span<K>>, viewTop: Int): Span<K>? =
        if (atTheVeryTop(viewTop)) null else innermostStraddling(inView, viewTop) ?: inView.minByOrNull { it.top }

    private fun <K> innermostStraddling(inView: List<Span<K>>, viewTop: Int): Span<K>? =
        inView.filter { it.top <= viewTop }.maxByOrNull { it.top }
}
