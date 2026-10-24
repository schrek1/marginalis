package dev.marginalis.plugin.testing

import com.intellij.ui.BadgeDotProvider
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.Icon

object LiveBadge {
    val AMBER = Color(0xF2, 0xB4, 0x30)
    val DOT = Color(0xE5, 0x32, 0x32)
    const val GAP = 1.25
    private const val VIEWBOX = 16.0
    private const val STROKE = 1.5

    private val BOLT: List<Point2D> = listOf(9.3 to 1.2, 2.2 to 9.5, 7.5 to 9.5, 6.6 to 14.8, 13.8 to 6.5, 8.5 to 6.5)
        .map { (x, y) -> Point2D.Double(x, y) }

    fun shownIn(icon: Icon): Boolean {
        val bolt = Pixels.onTransparent(icon).painted(AMBER)
        return bolt.isNotEmpty() && bolt.all { (x, y) -> x >= icon.iconWidth / 2 && y >= icon.iconHeight / 2 }
    }

    class InTheCorner(private val box: Int, private val size: Int) {
        private val unit = size / VIEWBOX
        private val outline: List<Point2D> = BOLT.map { Point2D.Double(box - size + it.x * unit, box - size + it.y * unit) }
        private val shape = Path2D.Double().apply {
            moveTo(outline.first().x, outline.first().y)
            outline.drop(1).forEach { lineTo(it.x, it.y) }
            closePath()
        }

        val strokeHalf: Double get() = STROKE * unit / 2

        fun distanceOutside(x: Double, y: Double): Double =
            if (shape.contains(x, y)) 0.0
            else outline.indices.minOf { i -> Line2D.ptSegDist(outline[i].x, outline[i].y, outline[(i + 1) % outline.size].x, outline[(i + 1) % outline.size].y, x, y) }

        fun distancesOf(pixels: List<Pair<Int, Int>>, scale: Int): List<Double> =
            pixels.map { (x, y) -> distanceOutside((x + 0.5) / scale, (y + 0.5) / scale) }
    }

    fun withPlatformDot(base: Icon): Icon = PlatformDot(base)

    fun platformDotArea(width: Int, height: Int, scale: Int): List<Pair<Int, Int>> {
        val hole = dotHole(width, height)
        return (0 until width * scale).flatMap { x -> (0 until height * scale).map { y -> x to y } }
            .filter { (x, y) -> hole.contains((x + 0.5) / scale, (y + 0.5) / scale) }
    }

    private fun dotHole(width: Int, height: Int): Shape =
        listOf(true, false).map { BadgeDotProvider().createShape(width, height, it)!! }.maxBy { it.bounds2D.width }

    private fun dot(width: Int, height: Int): Shape =
        listOf(true, false).map { BadgeDotProvider().createShape(width, height, it)!! }.minBy { it.bounds2D.width }

    private class PlatformDot(private val base: Icon) : Icon {
        override fun getIconWidth(): Int = base.iconWidth

        override fun getIconHeight(): Int = base.iconHeight

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            base.paintIcon(c, g, x, y)
            val canvas = g.create() as Graphics2D
            try {
                canvas.translate(x, y)
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                canvas.composite = AlphaComposite.DstOut
                canvas.fill(dotHole(iconWidth, iconHeight))
                canvas.composite = AlphaComposite.SrcOver
                canvas.color = DOT
                canvas.fill(dot(iconWidth, iconHeight))
            } finally {
                canvas.dispose()
            }
        }
    }
}
