package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.intellij.openapi.project.Project
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class SlotClickHandler(
    private val project: Project,
    private val binding: SlotBinding,
    private val catalog: AssetCatalog,
    private val iconProvider: RecipeIconProvider,
    private val isOutput: Boolean
) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) {
            return
        }
        val mode = if (isOutput) {
            RecipeItemPickerDialog.SelectionMode.ITEM_ONLY
        } else {
            RecipeItemPickerDialog.SelectionMode.ITEM_OR_TAG
        }
        val dialog = RecipeItemPickerDialog(project, catalog, iconProvider, mode)
        if (!dialog.showAndGet()) {
            return
        }
        val selection = dialog.getSelection() ?: return
        project.getService(RecipeDocumentService::class.java).applySelection(binding, selection)
    }
}
