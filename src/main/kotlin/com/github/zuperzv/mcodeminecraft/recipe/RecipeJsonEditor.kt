package com.github.zuperzv.mcodeminecraft.recipe

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object RecipeJsonEditor {
    fun applySelection(root: JsonObject, binding: SlotBinding, selection: IngredientSelection) {
        when (binding) {
            is SlotBinding.ShapedInput -> updateShaped(root, binding, selection)
            is SlotBinding.ShapelessInput -> updateShapeless(root, binding, selection)
            is SlotBinding.SingleInput -> updateSingle(root, binding, selection)
            is SlotBinding.FluidInput -> updateFluidInput(root, binding, selection)
            SlotBinding.Result -> updateResult(root, selection)
        }
    }

    private fun updateShaped(root: JsonObject, binding: SlotBinding.ShapedInput, selection: IngredientSelection) {
        val patternArray = root.getAsJsonArray("pattern") ?: JsonArray().also { root.add("pattern", it) }
        val keyObject = root.getAsJsonObject("key") ?: JsonObject().also { root.add("key", it) }
        val rows = patternArray.map { it.asString }.toMutableList()
        while (rows.size <= binding.row) {
            rows.add("")
        }
        val rowChars = rows[binding.row].padEnd(binding.col + 1, ' ').toCharArray()
        val currentChar = rowChars[binding.col]
        when (selection) {
            IngredientSelection.Clear -> {
                if (currentChar != ' ') {
                    rowChars[binding.col] = ' '
                    val updated = String(rowChars).trimEnd()
                    rows[binding.row] = updated
                    if (!patternContainsChar(rows, currentChar)) {
                        keyObject.remove(currentChar.toString())
                    }
                }
            }
            is IngredientSelection.Item,
            is IngredientSelection.Tag -> {
                val charToUse = if (currentChar == ' ' || !keyObject.has(currentChar.toString())) {
                    chooseUnusedKeyChar(keyObject, rows)
                } else {
                    currentChar
                }
                rowChars[binding.col] = charToUse
                rows[binding.row] = String(rowChars).trimEnd()
                keyObject.add(charToUse.toString(), createIngredientElement(selection))
            }
            is IngredientSelection.Fluid -> return
        }
        while (patternArray.size() > 0) {
            patternArray.remove(0)
        }
        rows.forEach { patternArray.add(it) }
    }

    private fun updateShapeless(root: JsonObject, binding: SlotBinding.ShapelessInput, selection: IngredientSelection) {
        val ingredients = root.getAsJsonArray("ingredients") ?: JsonArray().also { root.add("ingredients", it) }
        when (selection) {
            IngredientSelection.Clear -> {
                if (binding.index in 0 until ingredients.size()) {
                    ingredients.remove(binding.index)
                }
            }
            is IngredientSelection.Item,
            is IngredientSelection.Tag -> {
                if (binding.index in 0 until ingredients.size()) {
                    ingredients.set(binding.index, createIngredientElement(selection))
                } else {
                    ingredients.add(createIngredientElement(selection))
                }
            }
            is IngredientSelection.Fluid -> return
        }
    }

    private fun updateSingle(root: JsonObject, binding: SlotBinding.SingleInput, selection: IngredientSelection) {
        when (selection) {
            IngredientSelection.Clear -> root.remove(binding.field)
            is IngredientSelection.Item,
            is IngredientSelection.Tag -> root.add(binding.field, createIngredientElement(selection))
            is IngredientSelection.Fluid -> return
        }
    }

    private fun updateFluidInput(root: JsonObject, binding: SlotBinding.FluidInput, selection: IngredientSelection) {
        when (selection) {
            IngredientSelection.Clear -> root.remove(binding.field)
            is IngredientSelection.Fluid -> {
                val obj = root.getAsJsonObject(binding.field) ?: JsonObject().also {
                    root.add(binding.field, it)
                }
                obj.addProperty("fluid", selection.id)
            }
            else -> return
        }
    }

    private fun updateResult(root: JsonObject, selection: IngredientSelection) {
        if (selection !is IngredientSelection.Item) {
            return
        }
        val current = root.get("result")
        when {
            current == null -> root.addProperty("result", selection.id)
            current.isJsonPrimitive && current.asJsonPrimitive.isString -> root.addProperty("result", selection.id)
            current.isJsonObject -> {
                val obj = current.asJsonObject
                val key = when {
                    obj.has("id") -> "id"
                    obj.has("item") -> "item"
                    else -> "id"
                }
                obj.addProperty(key, selection.id)
            }
            else -> root.addProperty("result", selection.id)
        }
    }

    private fun createIngredientElement(selection: IngredientSelection): JsonElement {
        val obj = JsonObject()
        when (selection) {
            is IngredientSelection.Item -> obj.addProperty("item", selection.id)
            is IngredientSelection.Tag -> obj.addProperty("tag", selection.id)
            is IngredientSelection.Fluid -> {}
            IngredientSelection.Clear -> {}
        }
        return obj
    }

    private fun patternContainsChar(rows: List<String>, char: Char): Boolean {
        return rows.any { it.indexOf(char) >= 0 }
    }

    private fun chooseUnusedKeyChar(keyObject: JsonObject, rows: List<String>): Char {
        val used = HashSet<Char>()
        keyObject.entrySet().forEach { entry ->
            entry.key.firstOrNull()?.let { used.add(it) }
        }
        rows.forEach { row ->
            row.forEach { used.add(it) }
        }
        val candidates = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return candidates.firstOrNull { it !in used } ?: 'X'
    }
}
