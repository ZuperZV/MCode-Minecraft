package com.github.zuperzv.mcodeminecraft.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class MinecraftAssetIndexer {
    fun index(clientJar: Path, version: String): MinecraftAssetIndex {
        val modelDefinitions = HashMap<ResourceLocation, ModelDefinition>()
        val modelsById = HashMap<String, ResourceLocation>()
        val displayNames = HashMap<String, String>()
        val availableTextures = HashSet<ResourceLocation>()
        val blockIds = HashSet<String>()
        val itemIds = HashSet<String>()
        val itemTags = HashMap<String, MutableSet<String>>()
        val blockTags = HashMap<String, MutableSet<String>>()
        val fluidTags = HashMap<String, MutableSet<String>>()

        ZipFile(clientJar.toFile()).use { zip ->
            for (entry in zip.entriesSequence()) {
                val name = entry.name
                when {
                    name == LANG_PATH -> {
                        zip.getInputStream(entry).use { stream ->
                            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                                parseLang(JsonParser.parseReader(reader).asJsonObject, displayNames, blockIds, itemIds)
                            }
                        }
                    }
                    name.startsWith(BLOCK_MODEL_PREFIX) && name.endsWith(".json") -> {
                        val modelPath = name.removePrefix(BLOCK_MODEL_PREFIX).removeSuffix(".json")
                        val location = ResourceLocation.of("minecraft", "block/$modelPath")
                        val id = "minecraft:$modelPath".lowercase(Locale.US)
                        modelsById[id] = location
                        blockIds.add(id)
                        modelDefinitions[location] = parseModel(zip, entry, location)
                    }
                    name.startsWith(ITEM_MODEL_PREFIX) && name.endsWith(".json") -> {
                        val modelPath = name.removePrefix(ITEM_MODEL_PREFIX).removeSuffix(".json")
                        val location = ResourceLocation.of("minecraft", "item/$modelPath")
                        val id = "minecraft:$modelPath".lowercase(Locale.US)
                        modelsById[id] = location
                        itemIds.add(id)
                        modelDefinitions[location] = parseModel(zip, entry, location)
                    }
                    name.startsWith(TEXTURE_PREFIX) && !entry.isDirectory && name.endsWith(".png") -> {
                        val texturePath = name.removePrefix(TEXTURE_PREFIX).removeSuffix(".png")
                        availableTextures.add(ResourceLocation.of("minecraft", texturePath))
                    }
                    name.startsWith(ITEM_TAG_PREFIX) && name.endsWith(".json") -> {
                        val tagPath = name.removePrefix(ITEM_TAG_PREFIX).removeSuffix(".json")
                        val tagId = "minecraft:$tagPath".lowercase(Locale.US)
                        itemTags[tagId] = parseTagValues(zip, entry)
                    }
                    name.startsWith(BLOCK_TAG_PREFIX) && name.endsWith(".json") -> {
                        val tagPath = name.removePrefix(BLOCK_TAG_PREFIX).removeSuffix(".json")
                        val tagId = "minecraft:$tagPath".lowercase(Locale.US)
                        blockTags[tagId] = parseTagValues(zip, entry)
                    }
                    name.startsWith(FLUID_TAG_PREFIX) && name.endsWith(".json") -> {
                        val tagPath = name.removePrefix(FLUID_TAG_PREFIX).removeSuffix(".json")
                        val tagId = "minecraft:$tagPath".lowercase(Locale.US)
                        fluidTags[tagId] = parseTagValues(zip, entry)
                    }
                }
            }
        }

        val modelTextures = resolveModelTextures(modelDefinitions)
        return MinecraftAssetIndex(
            version = version,
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

    private fun parseLang(
        root: JsonObject,
        displayNames: MutableMap<String, String>,
        blockIds: MutableSet<String>,
        itemIds: MutableSet<String>
    ) {
        for (entry in root.entrySet()) {
            val value = entry.value
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                continue
            }
            val key = entry.key
            when {
                key.startsWith("block.minecraft.") -> {
                    val id = "minecraft:${key.removePrefix("block.minecraft.")}".lowercase(Locale.US)
                    displayNames[id] = value.asString
                    blockIds.add(id)
                }
                key.startsWith("item.minecraft.") -> {
                    val id = "minecraft:${key.removePrefix("item.minecraft.")}".lowercase(Locale.US)
                    displayNames[id] = value.asString
                    itemIds.add(id)
                }
                key.startsWith("fluid.minecraft.") -> {
                    val id = "minecraft:${key.removePrefix("fluid.minecraft.")}".lowercase(Locale.US)
                    displayNames[id] = value.asString
                }
            }
        }
    }

    private fun parseModel(zip: ZipFile, entry: ZipEntry, location: ResourceLocation): ModelDefinition {
        zip.getInputStream(entry).use { stream ->
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

    private fun parseTagValues(zip: ZipFile, entry: ZipEntry): MutableSet<String> {
        zip.getInputStream(entry).use { stream ->
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

    private fun ZipFile.entriesSequence(): Sequence<ZipEntry> = sequence {
        val enumeration = entries()
        while (enumeration.hasMoreElements()) {
            yield(enumeration.nextElement())
        }
    }

    private data class ModelDefinition(
        val location: ResourceLocation,
        val parent: ResourceLocation?,
        val textures: Map<String, String>
    )

    companion object {
        private const val BLOCK_MODEL_PREFIX = "assets/minecraft/models/block/"
        private const val ITEM_MODEL_PREFIX = "assets/minecraft/models/item/"
        private const val TEXTURE_PREFIX = "assets/minecraft/textures/"
        private const val LANG_PATH = "assets/minecraft/lang/en_us.json"
        private const val ITEM_TAG_PREFIX = "data/minecraft/tags/items/"
        private const val BLOCK_TAG_PREFIX = "data/minecraft/tags/blocks/"
        private const val FLUID_TAG_PREFIX = "data/minecraft/tags/fluids/"
        private const val MAX_TEXTURE_REF_DEPTH = 16
    }
}
