package com.github.zuperzv.mcodeminecraft.assets

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class ProjectAssetIndexer {
    fun index(resourceRoots: List<Path>): ProjectAssetIndex {
        val modelDefinitions = HashMap<ResourceLocation, ModelDefinition>()
        val modelsById = HashMap<String, ResourceLocation>()
        val displayNames = HashMap<String, String>()
        val availableTextures = HashSet<ResourceLocation>()
        val blockIds = HashSet<String>()
        val itemIds = HashSet<String>()
        val itemTags = HashMap<String, MutableSet<String>>()
        val blockTags = HashMap<String, MutableSet<String>>()
        val fluidTags = HashMap<String, MutableSet<String>>()

        for (root in resourceRoots) {
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                continue
            }
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { path ->
                    val relative = root.relativize(path).toString().replace("\\", "/")
                    when {
                        isLangFile(relative) -> {
                            parseLang(path, displayNames)
                        }
                        isBlockModel(relative) -> {
                            val (namespace, modelPath) = parseAssetPath(relative, "models/block") ?: return@forEach
                            val location = ResourceLocation.of(namespace, "block/$modelPath")
                            val id = "$namespace:$modelPath".lowercase(Locale.US)
                            modelsById[id] = location
                            blockIds.add(id)
                            modelDefinitions[location] = parseModel(path, location)
                        }
                        isItemModel(relative) -> {
                            val (namespace, modelPath) = parseAssetPath(relative, "models/item") ?: return@forEach
                            val location = ResourceLocation.of(namespace, "item/$modelPath")
                            val id = "$namespace:$modelPath".lowercase(Locale.US)
                            modelsById[id] = location
                            itemIds.add(id)
                            modelDefinitions[location] = parseModel(path, location)
                        }
                        isTexture(relative) -> {
                            val (namespace, texturePath) = parseAssetPath(relative, "textures") ?: return@forEach
                            availableTextures.add(ResourceLocation.of(namespace, texturePath.removeSuffix(".png")))
                        }
                        isItemTag(relative) -> {
                            val (namespace, tagPath) = parseDataPath(relative, "tags/items") ?: return@forEach
                            val tagId = "$namespace:$tagPath".lowercase(Locale.US)
                            itemTags[tagId] = parseTagValues(path)
                        }
                        isBlockTag(relative) -> {
                            val (namespace, tagPath) = parseDataPath(relative, "tags/blocks") ?: return@forEach
                            val tagId = "$namespace:$tagPath".lowercase(Locale.US)
                            blockTags[tagId] = parseTagValues(path)
                        }
                        isFluidTag(relative) -> {
                            val (namespace, tagPath) = parseDataPath(relative, "tags/fluids") ?: return@forEach
                            val tagId = "$namespace:$tagPath".lowercase(Locale.US)
                            fluidTags[tagId] = parseTagValues(path)
                        }
                    }
                }
            }
        }

        val modelTextures = resolveModelTextures(modelDefinitions)
        return ProjectAssetIndex(
            modelsById = modelsById.toMap(),
            modelTextures = modelTextures,
            displayNames = displayNames.toMap(),
            availableTextures = availableTextures.toSet(),
            blockIds = blockIds.toSet(),
            itemIds = itemIds.toSet(),
            tagIndex = TagIndex(
                itemTags = itemTags.mapValues { it.value.toSet() },
                blockTags = blockTags.mapValues { it.value.toSet() },
                fluidTags = fluidTags.mapValues { it.value.toSet() }
            )
        )
    }

    private fun parseLang(path: Path, displayNames: MutableMap<String, String>) {
        Files.newInputStream(path).use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return
                val obj = root.asJsonObject
                for (entry in obj.entrySet()) {
                    val value = entry.value
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                        continue
                    }
                    val key = entry.key
                    when {
                        key.startsWith("block.") || key.startsWith("item.") -> {
                            val id = key.substringAfter('.').lowercase(Locale.US).replace('.', ':')
                            displayNames[id] = value.asString
                        }
                        key.startsWith("fluid.") -> {
                            val id = key.substringAfter('.').lowercase(Locale.US).replace('.', ':')
                            displayNames[id] = value.asString
                        }
                        key.startsWith("block.minecraft.") -> {
                            val id = "minecraft:${key.removePrefix("block.minecraft.")}".lowercase(Locale.US)
                            displayNames[id] = value.asString
                        }
                        key.startsWith("item.minecraft.") -> {
                            val id = "minecraft:${key.removePrefix("item.minecraft.")}".lowercase(Locale.US)
                            displayNames[id] = value.asString
                        }
                        key.startsWith("fluid.minecraft.") -> {
                            val id = "minecraft:${key.removePrefix("fluid.minecraft.")}".lowercase(Locale.US)
                            displayNames[id] = value.asString
                        }
                    }
                }
            }
        }
    }

    private fun parseModel(path: Path, location: ResourceLocation): ModelDefinition {
        Files.newInputStream(path).use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader).asJsonObject
                val parentRaw = root.get("parent")?.asString
                val parent = parentRaw?.let { ResourceLocation.parse(it, location.namespace) }
                val textures = root.getAsJsonObject("textures")?.entrySet()?.mapNotNull { textureEntry ->
                    val value = textureEntry.value
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                        return@mapNotNull null
                    }
                    textureEntry.key to value.asString
                }?.toMap() ?: emptyMap()
                return ModelDefinition(location, parent, textures)
            }
        }
    }

    private fun parseTagValues(path: Path): MutableSet<String> {
        Files.newInputStream(path).use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return mutableSetOf()
                val obj = root.asJsonObject
                val values = obj.getAsJsonArray("values") ?: return mutableSetOf()
                return values.mapNotNull { element ->
                    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                        return@mapNotNull null
                    }
                    element.asString.trim()
                }.toMutableSet()
            }
        }
    }

    private fun resolveModelTextures(
        modelDefinitions: Map<ResourceLocation, ModelDefinition>
    ): Map<ResourceLocation, Map<String, ResourceLocation>> {
        val rawCache = HashMap<ResourceLocation, Map<String, String>>()
        val resolveCache = HashMap<ResourceLocation, Map<String, ResourceLocation>>()
        val visiting = HashSet<ResourceLocation>()

        fun resolveRaw(model: ResourceLocation): Map<String, String> {
            rawCache[model]?.let { return it }
            if (!visiting.add(model)) {
                return emptyMap()
            }
            val definition = modelDefinitions[model] ?: run {
                visiting.remove(model)
                return emptyMap()
            }
            val merged = LinkedHashMap<String, String>()
            val parent = definition.parent
            if (parent != null) {
                merged.putAll(resolveRaw(parent))
            }
            merged.putAll(definition.textures)
            visiting.remove(model)
            rawCache[model] = merged
            return merged
        }

        fun resolveTextures(model: ResourceLocation): Map<String, ResourceLocation> {
            resolveCache[model]?.let { return it }
            val raw = resolveRaw(model)
            val resolved = LinkedHashMap<String, ResourceLocation>()
            for ((key, value) in raw) {
                val expanded = resolveTextureReference(value, raw, 0) ?: continue
                resolved[key] = ResourceLocation.parse(expanded, model.namespace)
            }
            resolveCache[model] = resolved
            return resolved
        }

        return modelDefinitions.keys.associateWith { resolveTextures(it) }
    }

    private fun resolveTextureReference(
        value: String,
        textures: Map<String, String>,
        depth: Int
    ): String? {
        if (depth > MAX_TEXTURE_REF_DEPTH) {
            return null
        }
        if (!value.startsWith("#")) {
            return value
        }
        val next = textures[value.removePrefix("#")] ?: return null
        return resolveTextureReference(next, textures, depth + 1)
    }

    private fun isLangFile(relative: String): Boolean {
        return relative.startsWith("assets/") && relative.endsWith("/lang/en_us.json")
    }

    private fun isBlockModel(relative: String): Boolean {
        return relative.startsWith("assets/") && relative.contains("/models/block/") && relative.endsWith(".json")
    }

    private fun isItemModel(relative: String): Boolean {
        return relative.startsWith("assets/") && relative.contains("/models/item/") && relative.endsWith(".json")
    }

    private fun isTexture(relative: String): Boolean {
        return relative.startsWith("assets/") && relative.contains("/textures/") && relative.endsWith(".png")
    }

    private fun isItemTag(relative: String): Boolean {
        return relative.startsWith("data/") && relative.contains("/tags/items/") && relative.endsWith(".json")
    }

    private fun isBlockTag(relative: String): Boolean {
        return relative.startsWith("data/") && relative.contains("/tags/blocks/") && relative.endsWith(".json")
    }

    private fun isFluidTag(relative: String): Boolean {
        return relative.startsWith("data/") && relative.contains("/tags/fluids/") && relative.endsWith(".json")
    }

    private fun parseAssetPath(relative: String, folder: String): Pair<String, String>? {
        val parts = relative.split("/")
        if (parts.size < 4) return null
        val namespace = parts[1]
        val folderIndex = parts.indexOf("models").takeIf { folder.startsWith("models") }
        if (folderIndex != null) {
            val suffixIndex = folderIndex + 1
            val expected = folder.split("/").getOrNull(1) ?: return null
            if (parts.getOrNull(suffixIndex) != expected) return null
            val modelPath = parts.drop(suffixIndex + 1).joinToString("/").removeSuffix(".json")
            return namespace to modelPath
        }
        val texturesIndex = parts.indexOf("textures").takeIf { folder == "textures" } ?: return null
        val texturePath = parts.drop(texturesIndex + 1).joinToString("/")
        return namespace to texturePath
    }

    private fun parseDataPath(relative: String, folder: String): Pair<String, String>? {
        val parts = relative.split("/")
        if (parts.size < 4) return null
        val namespace = parts[1]
        val folderParts = folder.split("/")
        val folderIndex = parts.indexOf(folderParts[0])
        if (folderIndex == -1) return null
        if (parts.drop(folderIndex).take(folderParts.size) != folderParts) return null
        val tagPath = parts.drop(folderIndex + folderParts.size).joinToString("/").removeSuffix(".json")
        return namespace to tagPath
    }

    private data class ModelDefinition(
        val location: ResourceLocation,
        val parent: ResourceLocation?,
        val textures: Map<String, String>
    )

    companion object {
        private const val MAX_TEXTURE_REF_DEPTH = 16
    }
}
