package dev.marginalis.plugin.ui

import com.intellij.openapi.util.ScalableIcon
import com.intellij.util.IconUtil
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import kotlin.math.roundToInt

class WalkthroughStripeIcon private constructor(
    private val original: Icon,
    private val stripe: Color,
    private val scale: Float,
) : ScalableIcon {

    constructor(mark: Icon, stripe: Color) : this(mark, stripe, 1f)

    private val mark: Icon = when {
        scale == 1f -> original
        original is ScalableIcon -> original.scale(scale)
        else -> IconUtil.scale(original, null, scale)
    }
    private val stripeWidth = (mark.iconWidth * STRIPE_OF_MARK).roundToInt().coerceAtLeast(1)
    private val gap = (mark.iconWidth * GAP_OF_MARK).roundToInt().coerceAtLeast(1)

    override fun getIconWidth(): Int = stripeWidth + gap + mark.iconWidth

    override fun getIconHeight(): Int = mark.iconHeight

    override fun getScale(): Float = scale

    override fun scale(scaleFactor: Float): Icon = if (scaleFactor == scale) this else WalkthroughStripeIcon(original, stripe, scaleFactor)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val brush = g.create()
        try {
            brush.color = stripe
            brush.fillRect(x, y, stripeWidth, iconHeight)
        } finally {
            brush.dispose()
        }
        mark.paintIcon(c, g, x + stripeWidth + gap, y)
    }

    override fun equals(other: Any?): Boolean =
        other is WalkthroughStripeIcon && other.original == original && other.stripe == stripe && other.scale == scale

    override fun hashCode(): Int = original.hashCode() * 31 + stripe.hashCode()

    private companion object {
        const val STRIPE_OF_MARK = 2f / 16
        const val GAP_OF_MARK = 1f / 16
    }
}
