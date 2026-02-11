package com.github.zuperzv.mcodeminecraft.recipe

import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JComponent

class RecipeSlotComponent : JComponent() {
    var icon: Icon? = null
        set(value) {
            field = value
            repaint()
        }
    var count: Int? = null
        set(value) {
            field = value
            repaint()
        }
    var missing: Boolean = false
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(SLOT_SIZE, SLOT_SIZE)
        minimumSize = Dimension(SLOT_SIZE, SLOT_SIZE)
        maximumSize = Dimension(SLOT_SIZE, SLOT_SIZE)
        toolTipText = ""
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = BACKGROUND
        g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
        g2.color = if (missing) MISSING_BORDER else BORDER
        g2.drawRoundRect(0, 0, width - 1, height - 1, 6, 6)
        icon?.let {
            val x = (width - it.iconWidth) / 2
            val y = (height - it.iconHeight) / 2
            it.paintIcon(this, g2, x, y)
        }
        count?.takeIf { it > 1 }?.let { value ->
            g2.font = g2.font.deriveFont(Font.BOLD, 11f)
            g2.color = Color.WHITE
            val text = value.toString()
            val metrics = g2.fontMetrics
            val textWidth = metrics.stringWidth(text)
            val textHeight = metrics.height
            val x = width - textWidth - 4
            val y = height - 4
            g2.drawString(text, x, y)
        }
    }

    companion object {
        private const val SLOT_SIZE = 44
        private val BACKGROUND = Color(0x2B2B2B)
        private val BORDER = Color(0x555555)
        private val MISSING_BORDER = Color(0xB24848)
    }
}
