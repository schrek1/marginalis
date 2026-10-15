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

    fun anyIn(colour: Color, xs: IntRange, ys: IntRange, tolerance: Int = 24): Boolean =
        xs.any { x -> ys.any { y -> close(Color(image.getRGB(x, y)), colour, tolerance) } }

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

        fun close(a: Color, b: Color, tolerance: Int = 24): Boolean =
            abs(a.red - b.red) <= tolerance && abs(a.green - b.green) <= tolerance && abs(a.blue - b.blue) <= tolerance
    }
}
