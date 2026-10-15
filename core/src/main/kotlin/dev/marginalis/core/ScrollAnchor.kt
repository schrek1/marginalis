package dev.marginalis.core

object ScrollAnchor {

    data class Box(val key: String, val top: Int, val bottom: Int)

    data class Anchor(val key: String, val offsetFromViewTop: Int)

    fun pick(boxes: List<Box>, viewTop: Int, viewHeight: Int, preferred: List<String?>): Anchor? {
        val visible = boxes.filter { it.bottom > viewTop && it.top < viewTop + viewHeight }
        val chosen = preferred.firstNotNullOfOrNull { key -> visible.firstOrNull { it.key == key } }
            // At the very top nothing else is anchored, so threads arriving above come into view.
            ?: visible.takeIf { viewTop > 0 }?.let { topmost(it, viewTop) }
            ?: return null
        return Anchor(chosen.key, chosen.top - viewTop)
    }

    fun viewTopKeeping(anchor: Anchor, anchorTop: Int, contentHeight: Int, viewHeight: Int): Int =
        (anchorTop - anchor.offsetFromViewTop).coerceIn(0, (contentHeight - viewHeight).coerceAtLeast(0))

    // A conversation and its member threads all straddle the view top; the innermost one starts last.
    private fun topmost(visible: List<Box>, viewTop: Int): Box? =
        visible.filter { it.top <= viewTop }.maxByOrNull { it.top } ?: visible.minByOrNull { it.top }
}
