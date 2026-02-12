package com.github.zuperzv.mcodeminecraft.recipe

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

class RecipeParser {
    fun parse(text: String): RecipeDocument? {
        val root = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) {
            return null
        }
        val obj = root.asJsonObject
        val type = obj.get("type")?.asString ?: return null
        val kind = classify(type)
        val layout = buildLayout(kind, obj, type)
        return RecipeDocument(kind = kind, rootText = text, layout = layout)
    }

    private fun classify(type: String): RecipeKind {
        val normalized = type.lowercase()
        return when {
            normalized.contains("crafting_shaped") -> RecipeKind.CRAFTING_SHAPED
            normalized.contains("crafting_shapeless") -> RecipeKind.CRAFTING_SHAPELESS
            normalized.contains("smelting") ||
                normalized.contains("blasting") ||
                normalized.contains("smoking") ||
                normalized.contains("campfire_cooking") -> RecipeKind.FURNACE
            normalized.contains("stonecutting") -> RecipeKind.STONECUTTING
            normalized.contains("smithing") -> RecipeKind.SMITHING
            else -> RecipeKind.UNKNOWN
        }
    }

    private fun buildLayout(kind: RecipeKind, root: JsonObject, type: String): RecipeLayout {
        return when (kind) {
            RecipeKind.CRAFTING_SHAPED -> buildShaped(root, type)
            RecipeKind.CRAFTING_SHAPELESS -> buildShapeless(root, type)
            RecipeKind.FURNACE -> buildFurnace(root, type)
            RecipeKind.STONECUTTING -> buildStonecutting(root, type)
            RecipeKind.SMITHING -> buildSmithing(root, type)
            RecipeKind.UNKNOWN -> buildFallback(root, type)
        }
    }

    private fun buildShaped(root: JsonObject, type: String): RecipeLayout {
        val pattern = root.getAsJsonArray("pattern")?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        val key = root.getAsJsonObject("key") ?: JsonObject()
        val inputSlots = ArrayList<PositionedSlot>()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val char = pattern.getOrNull(row)?.getOrNull(col) ?: ' '
                val ingredient = if (char != ' ' && key.has(char.toString())) {
                    parseIngredient(key.get(char.toString()))
                } else {
                    null
                }
                val binding = SlotBinding.ShapedInput(row, col)
                inputSlots.add(PositionedSlot(row, col, RecipeSlotView(binding, ingredient, null)))
            }
        }
        val output = parseResult(root)
        return RecipeLayout(
            kind = RecipeKind.CRAFTING_SHAPED,
            gridWidth = 3,
            gridHeight = 3,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = listOf(RecipeExtraField("type", type))
        )
    }

    private fun buildShapeless(root: JsonObject, type: String): RecipeLayout {
        val ingredients = root.getAsJsonArray("ingredients") ?: JsonArray()
        val inputSlots = ArrayList<PositionedSlot>()
        var index = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val ingredient = ingredients.getOrNull(index)?.let { parseIngredient(it) }
                val bindingIndex = if (index < ingredients.size()) index else ingredients.size()
                val binding = SlotBinding.ShapelessInput(bindingIndex)
                inputSlots.add(PositionedSlot(row, col, RecipeSlotView(binding, ingredient, null)))
                index += 1
            }
        }
        val output = parseResult(root)
        return RecipeLayout(
            kind = RecipeKind.CRAFTING_SHAPELESS,
            gridWidth = 3,
            gridHeight = 3,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = listOf(RecipeExtraField("type", type))
        )
    }

    private fun buildFurnace(root: JsonObject, type: String): RecipeLayout {
        val ingredient = root.get("ingredient")?.let { parseIngredient(it) }
        val inputSlots = listOf(
            PositionedSlot(0, 0, RecipeSlotView(SlotBinding.SingleInput("ingredient"), ingredient, null))
        )
        val output = parseResult(root)
        val extra = ArrayList<RecipeExtraField>()
        extra.add(RecipeExtraField("type", type))
        root.get("experience")?.asDouble?.let { extra.add(RecipeExtraField("experience", it.toString())) }
        root.get("cookingtime")?.asInt?.let { extra.add(RecipeExtraField("cookingtime", it.toString())) }
        return RecipeLayout(
            kind = RecipeKind.FURNACE,
            gridWidth = 1,
            gridHeight = 1,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = extra
        )
    }

    private fun buildStonecutting(root: JsonObject, type: String): RecipeLayout {
        val ingredient = root.get("ingredient")?.let { parseIngredient(it) }
        val inputSlots = listOf(
            PositionedSlot(0, 0, RecipeSlotView(SlotBinding.SingleInput("ingredient"), ingredient, null))
        )
        val output = parseResult(root)
        val extra = listOf(RecipeExtraField("type", type))
        return RecipeLayout(
            kind = RecipeKind.STONECUTTING,
            gridWidth = 1,
            gridHeight = 1,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = extra
        )
    }

    private fun buildSmithing(root: JsonObject, type: String): RecipeLayout {
        val template = root.get("template")?.let { parseIngredient(it) }
        val base = root.get("base")?.let { parseIngredient(it) }
        val addition = root.get("addition")?.let { parseIngredient(it) }
        val inputSlots = listOf(
            PositionedSlot(0, 0, RecipeSlotView(SlotBinding.SingleInput("template"), template, null)),
            PositionedSlot(0, 1, RecipeSlotView(SlotBinding.SingleInput("base"), base, null)),
            PositionedSlot(0, 2, RecipeSlotView(SlotBinding.SingleInput("addition"), addition, null))
        )
        val output = parseResult(root)
        val extra = listOf(RecipeExtraField("type", type))
        return RecipeLayout(
            kind = RecipeKind.SMITHING,
            gridWidth = 3,
            gridHeight = 1,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = extra
        )
    }

    private fun buildFallback(root: JsonObject, type: String): RecipeLayout {
        val ingredients = root.getAsJsonArray("ingredients") ?: JsonArray()
        val singleIngredient = if (ingredients.size() == 0) root.get("ingredient")?.let { parseIngredient(it) } else null
        val inputSlots = ArrayList<PositionedSlot>()
        var index = 0
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val ingredient = ingredients.getOrNull(index)?.let { parseIngredient(it) }
                    ?: if (index == 0) singleIngredient else null
                val bindingIndex = if (index < ingredients.size()) index else ingredients.size()
                val binding = SlotBinding.ShapelessInput(bindingIndex)
                inputSlots.add(PositionedSlot(row, col, RecipeSlotView(binding, ingredient, null)))
                index += 1
            }
        }
        val output = parseResult(root)
        val extra = listOf(RecipeExtraField("type", type))
        return RecipeLayout(
            kind = RecipeKind.UNKNOWN,
            gridWidth = 3,
            gridHeight = 3,
            inputSlots = inputSlots,
            outputSlot = output?.let { RecipeSlotView(SlotBinding.Result, null, it) },
            fluidSlots = parseFluidSlots(root),
            extraFields = extra
        )
    }

    private fun parseIngredient(element: JsonElement): IngredientSlot? {
        if (element.isJsonNull) return null
        if (element.isJsonArray) {
            val choices = element.asJsonArray.mapNotNull { parseChoice(it) }
            return IngredientSlot(choices)
        }
        val choice = parseChoice(element) ?: return null
        return IngredientSlot(listOf(choice))
    }

    private fun parseChoice(element: JsonElement): IngredientChoice? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val item = obj.get("item")?.asString
        val tag = obj.get("tag")?.asString
        if (item == null && tag == null) return null
        return IngredientChoice(itemId = item, tagId = tag)
    }

    private fun parseResult(root: JsonObject): RecipeOutput? {
        val obj = root.get("result") ?: root.get("output") ?: return null

        if (obj.isJsonPrimitive && obj.asJsonPrimitive.isString) {
            return RecipeOutput.Item(obj.asString, 1)
        }

        if (obj.isJsonObject) {
            val json = obj.asJsonObject
            val count = json.get("count")?.asInt ?: json.get("amount")?.asInt ?: 1

            return when {
                json.has("item") || json.has("id") -> {
                    val id = json.get("id")?.asString ?: json.get("item")?.asString ?: return null
                    RecipeOutput.Item(id, count)
                }
                json.has("fluid") -> {
                    val fluidId = json.get("fluid").asString
                    RecipeOutput.Fluid(fluidId, count)
                }
                else -> null
            }
        }

        return null
    }

    sealed class RecipeOutput {
        data class Item(val id: String, val count: Int) : RecipeOutput()
        data class Fluid(val id: String, val amount: Int) : RecipeOutput()
    }

    private fun JsonElement.asStringOrNull(): String? {
        return if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    }

    private fun parseFluidSlots(root: JsonObject): List<FluidSlotView> {
        val input = root.getAsJsonObject("fluid_input") ?: return emptyList()
        val fluidId = input.get("fluid")?.asString
        val amount = input.get("amount")?.asInt ?: 0
        val stack = fluidId?.let { FluidStack(it, amount) }
        return listOf(
            FluidSlotView(
                binding = SlotBinding.FluidInput("fluid_input"),
                fluid = stack
            )
        )
    }

    private fun JsonArray.getOrNull(index: Int): JsonElement? {
        return if (index in 0 until size()) get(index) else null
    }
}
