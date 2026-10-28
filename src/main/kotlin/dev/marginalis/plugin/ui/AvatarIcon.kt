package dev.marginalis.plugin.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import dev.marginalis.core.AvatarGeometry
import dev.marginalis.core.Face
import dev.marginalis.core.Monogram
import dev.marginalis.plugin.avatars.AvatarImages
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import kotlin.math.cos
import kotlin.math.sin

internal object AvatarGeometries {
    @Volatile private var cached = AvatarGeometry()

    fun current(): AvatarGeometry {
        val scale = JBUIScale.scale(1f).toDouble()
        return cached.takeIf { it.scale == scale } ?: AvatarGeometry(scale).also { cached = it }
    }
}

class AvatarIcon(
    private val face: Face,
    private val surface: () -> Color,
    private val images: AvatarImages? = AvatarImages.getInstance(),
) : Icon {

    override fun getIconWidth(): Int = AvatarGeometries.current().size

    override fun getIconHeight(): Int = AvatarGeometries.current().size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(x, y)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val geometry = AvatarGeometries.current()
            val colour = AuthorColors.of(face.key)
            val background = surface()
            paintTile(g2, geometry, colour, background)
            paintRing(g2, geometry, colour)
            if (face.agent) paintBadge(g2, geometry, colour, background)
        } finally {
            g2.dispose()
        }
    }

    private fun paintTile(g: Graphics2D, geometry: AvatarGeometry, colour: Color, background: Color) {
        val tileShape = with(geometry) { RoundRectangle2D.Double(tileOrigin, tileOrigin, tile, tile, 2 * radius, 2 * radius) }
        val picture = images?.image(face, geometry.tilePixels(g.transform.scaleX))
        if (picture != null) {
            g.paint = TexturePaint(picture, with(geometry) { Rectangle2D.Double(tileOrigin, tileOrigin, tile, tile) })
            g.fill(tileShape)
        } else {
            g.color = ColorUtil.mix(background, colour, MONOGRAM_TINT)
            g.fill(tileShape)
            paintInitials(g, geometry, colour)
        }
    }

    private fun paintInitials(g: Graphics2D, geometry: AvatarGeometry, colour: Color) {
        val initials = Monogram.initials(face.name)
        val font = JBUI.Fonts.label()
            .deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_SEMIBOLD))
            .deriveFont(geometry.initialsFontSize(initials).toFloat())
        val glyphs = font.createGlyphVector(g.fontRenderContext, initials)
        val bounds = glyphs.visualBounds
        val centre = geometry.tileCentre
        g.color = colour
        g.drawGlyphVector(
            glyphs,
            (centre - bounds.centerX).toFloat(),
            (centre - bounds.centerY).toFloat(),
        )
    }

    private fun paintRing(g: Graphics2D, geometry: AvatarGeometry, colour: Color) {
        g.color = colour
        g.stroke = BasicStroke(geometry.ring.toFloat())
        g.draw(
            with(geometry) {
                RoundRectangle2D.Double(ringOrigin, ringOrigin, ringSize, ringSize, 2 * ringRadius, 2 * ringRadius)
            },
        )
    }

    private fun paintBadge(g: Graphics2D, geometry: AvatarGeometry, colour: Color, background: Color) {
        val centre = geometry.badgeCentre
        g.color = background
        g.fill(circle(centre, geometry.haloRadius))
        g.color = colour
        g.fill(circle(centre, geometry.badge / 2))
        if (geometry.sparks) {
            g.color = background
            g.fill(spark(centre, geometry.badge * SPARK_SHARE))
        }
    }

    private fun circle(centre: Double, radius: Double) =
        Ellipse2D.Double(centre - radius, centre - radius, 2 * radius, 2 * radius)

    private fun spark(centre: Double, reach: Double): Path2D = Path2D.Double().apply {
        val waist = reach * SPARK_WAIST
        for (point in 0 until 8) {
            val angle = Math.PI / 4 * point - Math.PI / 2
            val radius = if (point % 2 == 0) reach else waist
            val px = centre + radius * cos(angle)
            val py = centre + radius * sin(angle)
            if (point == 0) moveTo(px, py) else lineTo(px, py)
        }
        closePath()
    }

    private companion object {
        const val MONOGRAM_TINT = 0.2
        const val SPARK_SHARE = 0.36
        const val SPARK_WAIST = 0.3
    }
}
