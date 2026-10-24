package dev.marginalis.plugin.ui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.util.ScalableIcon
import com.intellij.util.IconUtil
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import kotlin.math.ceil

class LiveBadgeIcon private constructor(
    private val original: Icon,
    private val originalAlongside: Icon?,
    private val bolt: Icon,
    private val place: Place,
    private val scale: Float,
    private val variants: ConcurrentHashMap<Float, LiveBadgeIcon>,
) : ScalableIcon {

    enum class Corner { BOTTOM_RIGHT, BOTTOM_LEFT }

    class Place(val corner: Corner, val sizeOfBox: Float, val gapOfBox: Float)

    constructor(base: Icon, bolt: Icon, place: Place, alongside: Icon? = null) : this(base, alongside, bolt, place, 1f, ConcurrentHashMap()) {
        variants[1f] = this
    }

    private val base: Icon = scaled(original)
    private val alongside: Icon? = originalAlongside?.let(::scaled)

    @Volatile
    private var badge: Pair<Int, Icon>? = null

    private data class PixelDensity(val scaleX: Double, val scaleY: Double)

    private class Rasters(val theme: Int) {
        val byDensity = ConcurrentHashMap<PixelDensity, BufferedImage>()
    }

    @Volatile
    private var rasters = Rasters(themeChanges)

    override fun getIconWidth(): Int = base.iconWidth

    override fun getIconHeight(): Int = base.iconHeight

    override fun getScale(): Float = scale

    override fun scale(scaleFactor: Float): Icon =
        if (scaleFactor == scale) this else variants.computeIfAbsent(scaleFactor) { LiveBadgeIcon(original, originalAlongside, bolt, place, it, variants) }

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val box = iconWidth
        val badge = badgeFor(box)
        val left = if (place.corner == Corner.BOTTOM_RIGHT) box - badge.iconWidth else 0
        val top = iconHeight - badge.iconHeight
        g.drawImage(withGap(c, g as Graphics2D) { gap(box, left, top, badge.iconWidth) }, x, y, iconWidth, iconHeight, null)
        alongside?.paintIcon(c, g, x, y)
        badge.paintIcon(c, g, x + left, y + top)
    }

    private fun withGap(c: Component?, g: Graphics2D, gap: () -> Shape): BufferedImage {
        val scaleX = g.transform.scaleX
        val scaleY = g.transform.scaleY
        val theme = themeChanges
        val rasters = rasters.takeIf { it.theme == theme } ?: Rasters(theme).also { rasters = it }
        return rasters.byDensity.computeIfAbsent(PixelDensity(scaleX, scaleY)) { draw(c, scaleX, scaleY, gap) }
    }

    private fun draw(c: Component?, scaleX: Double, scaleY: Double, gap: () -> Shape): BufferedImage {
        val image = BufferedImage(
            ceil(iconWidth * scaleX).toInt().coerceAtLeast(1),
            ceil(iconHeight * scaleY).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_ARGB,
        )
        val canvas = image.createGraphics()
        try {
            canvas.scale(scaleX, scaleY)
            base.paintIcon(c, canvas, 0, 0)
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.composite = AlphaComposite.DstOut
            canvas.fill(gap())
        } finally {
            canvas.dispose()
        }
        return image
    }

    private fun gap(box: Int, left: Int, top: Int, size: Int): Shape {
        val unit = size / VIEWBOX
        val outline = BOLT_OUTLINE.createTransformedShape(AffineTransform(unit, 0.0, 0.0, unit, left.toDouble(), top.toDouble()))
        val margin = 2 * place.gapOfBox * box + BOLT_STROKE * unit
        return Area(outline).apply {
            add(Area(BasicStroke(margin.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND).createStrokedShape(outline)))
        }
    }

    private fun badgeFor(box: Int): Icon {
        badge?.takeIf { it.first == box }?.let { return it.second }
        val size = place.sizeOfBox * box
        return IconUtil.scale(bolt, null, size / bolt.iconWidth).also { badge = box to it }
    }

    private fun scaled(icon: Icon): Icon = when {
        scale == 1f -> icon
        icon is ScalableIcon -> icon.scale(scale)
        else -> IconUtil.scale(icon, null, scale)
    }

    class ThemeChange : LafManagerListener {
        override fun lookAndFeelChanged(source: LafManager) {
            themeChanges++
        }
    }

    private companion object {
        @Volatile
        var themeChanges = 0

        const val VIEWBOX = 16.0
        const val BOLT_STROKE = 1.5
        val BOLT_OUTLINE: Path2D = Path2D.Double().apply {
            moveTo(9.3, 1.2)
            lineTo(2.2, 9.5)
            lineTo(7.5, 9.5)
            lineTo(6.6, 14.8)
            lineTo(13.8, 6.5)
            lineTo(8.5, 6.5)
            closePath()
        }
    }
}
