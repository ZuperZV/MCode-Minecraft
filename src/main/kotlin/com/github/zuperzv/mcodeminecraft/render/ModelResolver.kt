package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.ResourceLocation
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Vec3(val x: Double, val y: Double, val z: Double)

data class ModelDisplayTransform(
    val rotation: Vec3 = Vec3(0.0, 0.0, 0.0),
    val scale: Vec3 = Vec3(1.0, 1.0, 1.0)
)

data class ResolvedModel(
    val id: ResourceLocation,
    val textures: Map<String, String>,
    val isGenerated: Boolean,
    val blockParent: ResourceLocation?,
    val displayGui: ModelDisplayTransform?
)

class ModelResolver(private val catalog: AssetCatalog) {
    private val cache = ConcurrentHashMap<ResourceLocation, ResolvedModel>()
    private val locale = Locale.US
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(ModelResolver::class.java)

    fun resolveItemModel(itemId: String): ResolvedModel? {
        val parsed = ResourceLocation.parse(itemId)
        val itemLocation = ResourceLocation.of(parsed.namespace, "item/${parsed.path}")
        val direct = resolveModel(itemLocation)
        if (direct != null) {
            logger.warn("ModelResolver: item model for $itemId -> $itemLocation")
            return direct
        }
        val fallback = catalog.resolveModel(itemId)
        if (fallback != null && fallback.path.startsWith("item/")) {
            logger.warn("ModelResolver: fallback item model for $itemId -> $fallback")
            return resolveModel(fallback)
        }
        logger.warn("ModelResolver: no item model for $itemId")
        return null
    }

    fun resolveModel(location: ResourceLocation): ResolvedModel? {
        cache[location]?.let { return it }
        val parsed = loadModel(location) ?: return null
        val resolved = resolveRecursive(location, parsed, 0)
        if (resolved != null) {
            cache[location] = resolved
        }
        return resolved
    }

    private fun resolveRecursive(
        location: ResourceLocation,
        model: RawModel,
        depth: Int
    ): ResolvedModel? {
        if (depth > MAX_DEPTH) return null
        val parentRef = model.parent?.let { ResourceLocation.parse(it, location.namespace) }
        val parent = parentRef?.let { resolveModel(it) }
        val mergedTextures = LinkedHashMap<String, String>()
        parent?.textures?.let { mergedTextures.putAll(it) }
        mergedTextures.putAll(model.textures)
        val isGenerated = parent?.isGenerated == true || model.parent == ITEM_GENERATED
        val blockParent = model.parent?.takeIf { it.contains("block/") }?.let {
            ResourceLocation.parse(it, location.namespace)
        } ?: parent?.blockParent
        val displayGui = model.displayGui ?: parent?.displayGui
        return ResolvedModel(
            id = location,
            textures = mergedTextures,
            isGenerated = isGenerated,
            blockParent = blockParent,
            displayGui = displayGui
        )
    }

    private fun loadModel(location: ResourceLocation): RawModel? {
        catalog.openModelStream(location)?.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject
                val parent = obj.get("parent")?.asString
                val textures = parseTextures(obj.getAsJsonObject("textures"))
                val display = obj.getAsJsonObject("display")
                val displayGui = display?.getAsJsonObject("gui")?.let { parseDisplay(it) }
                logger.warn("ModelResolver: model ${location} parent=${parent ?: "none"} textures=${textures.size}")
                return RawModel(parent, textures, displayGui)
            }
        }
        logger.warn("ModelResolver: model not found for ${location}")
        return null
    }

    private fun parseTextures(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        obj.entrySet().forEach { entry ->
            if (entry.value.isJsonPrimitive && entry.value.asJsonPrimitive.isString) {
                result[entry.key] = entry.value.asString.trim()
            }
        }
        return result
    }

    private fun parseDisplay(obj: JsonObject): ModelDisplayTransform {
        val rotation = parseVec3(obj.getAsJsonArray("rotation")) ?: Vec3(0.0, 0.0, 0.0)
        val scale = parseVec3(obj.getAsJsonArray("scale")) ?: Vec3(1.0, 1.0, 1.0)
        return ModelDisplayTransform(rotation = rotation, scale = scale)
    }

    private fun parseVec3(array: JsonArray?): Vec3? {
        if (array == null || array.size() < 3) return null
        val x = array[0].asDoubleOrNull() ?: return null
        val y = array[1].asDoubleOrNull() ?: return null
        val z = array[2].asDoubleOrNull() ?: return null
        return Vec3(x, y, z)
    }

    private fun com.google.gson.JsonElement.asDoubleOrNull(): Double? {
        return if (isJsonPrimitive && asJsonPrimitive.isNumber) asDouble else null
    }

    fun resolveTextureRef(ref: String, textures: Map<String, String>, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        if (!ref.startsWith("#")) return ref
        val key = ref.removePrefix("#").lowercase(locale)
        val next = textures[key] ?: return null
        return resolveTextureRef(next, textures, depth + 1)
    }

    private data class RawModel(
        val parent: String?,
        val textures: Map<String, String>,
        val displayGui: ModelDisplayTransform?
    )

    companion object {
        private const val MAX_DEPTH = 16
        private const val ITEM_GENERATED = "minecraft:item/generated"
    }
}
