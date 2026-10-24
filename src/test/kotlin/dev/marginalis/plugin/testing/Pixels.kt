package dev.marginalis.plugin.testing

import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.Icon
import kotlin.math.abs

class Pixels private constructor(private val image: BufferedImage) {

    val width: Int get() = image.width

    val height: Int get() = image.height

    fun at(x: Double, y: Double): Color = Color(image.getRGB(x.toInt(), y.toInt()))

    fun showsAt(colour: Color, x: Double, y: Double): Boolean = close(at(x, y), colour)

    fun changedFrom(before: Pixels): Set<Pair<Int, Int>> =
        everywhere().filterTo(HashSet()) { (x, y) -> image.getRGB(x, y) != before.image.getRGB(x, y) }

    fun anyIn(colour: Color, xs: IntRange, ys: IntRange, tolerance: Int = 24): Boolean =
        xs.any { x -> ys.any { y -> close(Color(image.getRGB(x, y)), colour, tolerance) } }

    fun alphaAt(x: Int, y: Int): Int = image.getRGB(x, y) ushr 24

    fun everywhere(): List<Pair<Int, Int>> = (0 until width).flatMap { x -> (0 until height).map { y -> x to y } }

    fun painted(colour: Color, tolerance: Int = 24): List<Pair<Int, Int>> =
        everywhere().filter { (x, y) -> alphaAt(x, y) >= VISIBLE && close(Color(image.getRGB(x, y)), colour, tolerance) }

    fun clearedFrom(before: Pixels): List<Pair<Int, Int>> =
        everywhere().filter { (x, y) -> before.alphaAt(x, y) >= OPAQUE && alphaAt(x, y) == 0 }

    fun sameAs(other: Pixels, at: Collection<Pair<Int, Int>>, tolerance: Int = 3): Boolean =
        at.all { (x, y) -> premultiplied(image.getRGB(x, y)).zip(premultiplied(other.image.getRGB(x, y))).all { (a, b) -> abs(a - b) <= tolerance } }

    fun badgeDotArea(): List<Pair<Int, Int>> = everywhere().filter { (x, y) -> x >= width / 2 && y < height * 3 / 8 }

    private fun premultiplied(argb: Int): List<Int> {
        val alpha = argb ushr 24
        return listOf(alpha) + listOf(16, 8, 0).map { shift -> (argb shr shift and 0xFF) * alpha / 255 }
    }

    companion object {
        val SURFACE: Color = Color.WHITE

        fun of(icon: Icon, surface: Color = SURFACE): Pixels {
            val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            try {
                g.color = surface
                g.fillRect(0, 0, image.width, image.height)
                icon.paintIcon(null, g, 0, 0)
            } finally {
                g.dispose()
            }
            return Pixels(image)
        }

        private const val VISIBLE = 64
        private const val OPAQUE = 128

        fun onTransparent(icon: Icon, scale: Int = 1): Pixels {
            val image = BufferedImage(icon.iconWidth * scale, icon.iconHeight * scale, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.scale(scale.toDouble(), scale.toDouble())
                icon.paintIcon(null, g, 0, 0)
            } finally {
                g.dispose()
            }
            return Pixels(image)
        }

        fun close(a: Color, b: Color, tolerance: Int = 24): Boolean =
            abs(a.red - b.red) <= tolerance && abs(a.green - b.green) <= tolerance && abs(a.blue - b.blue) <= tolerance
    }
}
