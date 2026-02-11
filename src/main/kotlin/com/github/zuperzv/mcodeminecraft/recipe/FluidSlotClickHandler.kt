package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.intellij.openapi.project.Project
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class FluidSlotClickHandler(
    private val project: Project,
    private val binding: SlotBinding.FluidInput,
    private val catalog: AssetCatalog,
    private val iconProvider: RecipeIconProvider
) : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent) {
        if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) {
            return
        }
        val dialog = RecipeFluidPickerDialog(catalog, iconProvider)
        if (!dialog.showAndGet()) {
            return
        }
        val selection = dialog.getSelection() ?: return
        project.getService(RecipeDocumentService::class.java).applySelection(binding, selection)
    }
}
