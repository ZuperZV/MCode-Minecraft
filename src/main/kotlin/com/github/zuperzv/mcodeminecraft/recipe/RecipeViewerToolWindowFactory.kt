package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.AssetCatalogService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JComponent
import javax.swing.SwingUtilities

class RecipeViewerToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RecipeViewerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)

        project.getService(AssetCatalogService::class.java).getCatalogAsync().thenAccept { catalog ->
            SwingUtilities.invokeLater {
                panel.setCatalog(catalog)
            }
        }
        project.getService(RecipeDocumentService::class.java).addListener { document ->
            SwingUtilities.invokeLater {
                panel.updateRecipe(document)
            }
        }
    }
}

private class RecipeViewerPanel(private val project: Project) {
    private val view = RecipeViewerView(project)
    val component: JComponent = view

    fun setCatalog(catalog: AssetCatalog?) {
        view.setCatalog(catalog)
    }

    fun updateRecipe(document: RecipeDocument?) {
        view.updateRecipe(document)
    }
}
