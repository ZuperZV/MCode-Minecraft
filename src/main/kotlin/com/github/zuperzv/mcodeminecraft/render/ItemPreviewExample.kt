package com.github.zuperzv.mcodeminecraft.render

import com.intellij.openapi.project.Project
import javax.swing.JComponent

object ItemPreviewExample {
    fun createDiamondSwordPreview(project: Project): JComponent {
        val panel = ItemPreviewPanel(project)
        panel.setItemId("minecraft:diamond_sword")
        return panel
    }
}
