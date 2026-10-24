package dev.marginalis.plugin.ui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.ScalableIcon
import dev.marginalis.plugin.testing.MarginalisTestCase
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.Icon

class LiveBadgeIconTest : MarginalisTestCase() {

    private class CountingIcon : Icon {
        var paints = 0
            private set

        override fun getIconWidth(): Int = 16

        override fun getIconHeight(): Int = 16

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            paints++
            g.color = Color.BLUE
            g.fillRect(x, y, iconWidth, iconHeight)
        }
    }

    private val beneath = CountingIcon()

    private val live by lazy {
        LiveBadgeIcon(
            beneath,
            IconLoader.getIcon("/icons/liveBadge.svg", MarginalisIcons::class.java),
            LiveBadgeIcon.Place(LiveBadgeIcon.Corner.BOTTOM_RIGHT, sizeOfBox = 0.5f, gapOfBox = 1.25f / 16),
        )
    }

    private fun paint(icon: Icon, pixelDensity: Double = 1.0) {
        val image = BufferedImage((icon.iconWidth * pixelDensity).toInt(), (icon.iconHeight * pixelDensity).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.scale(pixelDensity, pixelDensity)
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
    }

    fun testScalingByTheSameFactorAgainGivesTheSameIcon() {
        val larger = live.scale(1.5f)

        assertSame(larger, live.scale(1.5f))
        assertSame(live, (larger as ScalableIcon).scale(1f))
    }

    fun testRepaintingLeavesTheIconBeneathAlone() {
        paint(live)
        paint(live)

        assertEquals(1, beneath.paints)
    }

    fun testRepaintingAtAnotherPixelDensityRedrawsTheIconBeneath() {
        paint(live)
        paint(live, pixelDensity = 2.0)
        paint(live, pixelDensity = 2.0)

        assertEquals(2, beneath.paints)
    }

    fun testAlternatingBetweenTwoPixelDensitiesDrawsTheIconBeneathOncePerDensity() {
        paint(live)
        paint(live, pixelDensity = 2.0)
        paint(live)
        paint(live, pixelDensity = 2.0)

        assertEquals(2, beneath.paints)
    }

    fun testAThemeChangeRedrawsTheIconBeneathAtEveryPixelDensity() {
        paint(live)
        paint(live, pixelDensity = 2.0)

        ApplicationManager.getApplication().messageBus.syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(LafManager.getInstance())
        paint(live)
        paint(live, pixelDensity = 2.0)

        assertEquals(4, beneath.paints)
    }

    fun testRepaintingAfterAThemeChangeRedrawsTheIconBeneath() {
        paint(live)

        ApplicationManager.getApplication().messageBus.syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(LafManager.getInstance())
        paint(live)

        assertEquals(2, beneath.paints)
    }
}
