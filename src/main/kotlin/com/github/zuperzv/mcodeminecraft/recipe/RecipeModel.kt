package com.github.zuperzv.mcodeminecraft.recipe

data class IngredientChoice(
    val itemId: String? = null,
    val tagId: String? = null
)

data class IngredientSlot(
    val choices: List<IngredientChoice>
)

data class ItemStack(
    val id: String,
    val count: Int = 1
)

data class FluidStack(
    val id: String,
    val amount: Int = 0
)

data class RecipeExtraField(
    val key: String,
    val value: String
)

enum class RecipeKind {
    CRAFTING_SHAPED,
    CRAFTING_SHAPELESS,
    FURNACE,
    STONECUTTING,
    SMITHING,
    UNKNOWN
}

data class RecipeSlotView(
    val binding: SlotBinding,
    val ingredient: IngredientSlot?,
    val output: RecipeParser.RecipeOutput?
)

data class PositionedSlot(
    val row: Int,
    val col: Int,
    val view: RecipeSlotView
)

data class RecipeLayout(
    val kind: RecipeKind,
    val gridWidth: Int,
    val gridHeight: Int,
    val inputSlots: List<PositionedSlot>,
    val outputSlot: RecipeSlotView?,
    val fluidSlots: List<FluidSlotView>,
    val extraFields: List<RecipeExtraField>
)

data class RecipeDocument(
    val kind: RecipeKind,
    val rootText: String,
    val layout: RecipeLayout
)

sealed class SlotBinding {
    data class ShapedInput(val row: Int, val col: Int) : SlotBinding()
    data class ShapelessInput(val index: Int) : SlotBinding()
    data class SingleInput(val field: String) : SlotBinding()
    data class FluidInput(val field: String) : SlotBinding()
    object Result : SlotBinding()
    object Output : SlotBinding()
}

sealed class IngredientSelection {
    data class Item(val id: String) : IngredientSelection()
    data class Tag(val id: String) : IngredientSelection()
    data class Fluid(val id: String) : IngredientSelection()
    object Clear : IngredientSelection()
}

data class FluidSlotView(
    val binding: SlotBinding.FluidInput,
    val fluid: FluidStack?
)
