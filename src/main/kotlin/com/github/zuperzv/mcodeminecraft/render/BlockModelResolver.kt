package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.ResourceLocation
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class BlockFace(
    val texture: String,
    val uv: DoubleArray?
)

data class BlockElement(
    val from: Vec3,
    val to: Vec3,
    val faces: Map<String, BlockFace>,
    val rotation: ElementRotation?
)

data class ElementRotation(
    val origin: Vec3,
    val axis: String,
    val angle: Double
)

data class ResolvedBlockModel(
    val id: ResourceLocation,
    val textures: Map<String, String>,
    val elements: List<BlockElement>,
    val displayGui: ModelDisplayTransform?
)

class BlockModelResolver(
    private val catalog: AssetCatalog,
    private val modelResolver: ModelResolver = ModelResolver(catalog)
) {
    private val cache = ConcurrentHashMap<ResourceLocation, ResolvedBlockModel>()
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(BlockModelResolver::class.java)

    fun resolveBlockModel(location: ResourceLocation): ResolvedBlockModel? {
        cache[location]?.let { return it }
        val parsed = loadModel(location) ?: return null
        val resolved = resolveRecursive(location, parsed, 0)
        if (resolved != null) {
            cache[location] = resolved
        }
        return resolved
    }

    fun resolveItemToBlockModel(itemId: String): ResolvedBlockModel? {
        val itemModel = modelResolver.resolveItemModel(itemId) ?: return null
        val blockRef = itemModel.blockParent ?: return null
        return resolveBlockModel(blockRef)
    }

    fun resolveBlockModelForId(id: String): ResolvedBlockModel? {
        val location = ResourceLocation.parse(id)
        val blockstateRef = resolveBlockstateModelRef(location)
        if (blockstateRef == null) {
            logger.warn("BlockModelResolver: blockstate not found for ${location}")
        } else {
            logger.warn("BlockModelResolver: blockstate model for ${location} -> $blockstateRef")
        }
        val modelRef = blockstateRef ?: ResourceLocation.of(location.namespace, "block/${location.path}")
        if (blockstateRef == null) {
            logger.warn("BlockModelResolver: fallback block model for ${location} -> $modelRef")
        }
        return resolveBlockModel(modelRef)
    }

    private fun resolveRecursive(location: ResourceLocation, model: RawBlockModel, depth: Int): ResolvedBlockModel? {
        if (depth > MAX_DEPTH) return null
        val parentRef = model.parent?.let { ResourceLocation.parse(it, location.namespace) }
        val parent = parentRef?.let { resolveBlockModel(it) }
        val mergedTextures = LinkedHashMap<String, String>()
        parent?.textures?.let { mergedTextures.putAll(it) }
        mergedTextures.putAll(model.textures)
        val elements = if (model.elements.isNotEmpty()) model.elements else parent?.elements.orEmpty()
        val displayGui = model.displayGui ?: parent?.displayGui
        return ResolvedBlockModel(
            id = location,
            textures = mergedTextures,
            elements = elements,
            displayGui = displayGui
        )
    }

    private fun loadModel(location: ResourceLocation): RawBlockModel? {
        catalog.openModelStream(location)?.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject
                val parent = obj.get("parent")?.asString
                val textures = parseTextures(obj.getAsJsonObject("textures"))
                val elements = parseElements(obj.getAsJsonArray("elements"))
                val display = obj.getAsJsonObject("display")
                val displayGui = display?.getAsJsonObject("gui")?.let { parseDisplay(it) }
                logger.warn("BlockModelResolver: model ${location} parent=${parent ?: "none"} elements=${elements.size} textures=${textures.size}")
                return RawBlockModel(parent, textures, elements, displayGui)
            }
        }
        logger.warn("BlockModelResolver: model not found for ${location}")
        return null
    }

    private fun resolveBlockstateModelRef(blockId: ResourceLocation): ResourceLocation? {
        catalog.openBlockstateStream(blockId)?.use { stream ->
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return null
                val obj = root.asJsonObject
                val variantModel = obj.getAsJsonObject("variants")?.let { parseVariantModel(it) }
                if (variantModel != null) {
                    logger.warn("BlockModelResolver: blockstate variants for ${blockId} -> $variantModel")
                    return ResourceLocation.parse(variantModel, blockId.namespace)
                }
                val multipartModel = obj.getAsJsonArray("multipart")?.let { parseMultipartModel(it) }
                if (multipartModel != null) {
                    logger.warn("BlockModelResolver: blockstate multipart for ${blockId} -> $multipartModel")
                    return ResourceLocation.parse(multipartModel, blockId.namespace)
                }
            }
        }
        return null
    }

    private fun parseVariantModel(variants: JsonObject): String? {
        val entry = variants.entrySet().firstOrNull() ?: return null
        val value = entry.value
        if (value.isJsonObject) {
            return value.asJsonObject.get("model")?.asString
        }
        if (value.isJsonArray) {
            val array = value.asJsonArray
            val first = array.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
            return first.get("model")?.asString
        }
        return null
    }

    private fun parseMultipartModel(array: JsonArray): String? {
        val first = array.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
        val apply = first.get("apply") ?: return null
        if (apply.isJsonObject) {
            return apply.asJsonObject.get("model")?.asString
        }
        if (apply.isJsonArray) {
            val applyArr = apply.asJsonArray
            val firstApply = applyArr.firstOrNull { it.isJsonObject }?.asJsonObject ?: return null
            return firstApply.get("model")?.asString
        }
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

    private fun parseElements(array: JsonArray?): List<BlockElement> {
        if (array == null) return emptyList()
        val elements = ArrayList<BlockElement>()
        array.forEach { element ->
            if (!element.isJsonObject) return@forEach
            val obj = element.asJsonObject
            val from = parseVec3(obj.getAsJsonArray("from")) ?: return@forEach
            val to = parseVec3(obj.getAsJsonArray("to")) ?: return@forEach
            val faces = parseFaces(obj.getAsJsonObject("faces"))
            val rotation = obj.getAsJsonObject("rotation")?.let { parseRotation(it) }
            elements.add(BlockElement(from, to, faces, rotation))
        }
        return elements
    }

    private fun parseFaces(obj: JsonObject?): Map<String, BlockFace> {
        if (obj == null) return emptyMap()
        val result = LinkedHashMap<String, BlockFace>()
        obj.entrySet().forEach { entry ->
            val faceObj = entry.value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val texture = faceObj.get("texture")?.asString ?: return@forEach
            val uv = faceObj.getAsJsonArray("uv")?.let { parseUv(it) }
            result[entry.key.lowercase()] = BlockFace(texture = texture, uv = uv)
        }
        return result
    }

    private fun parseUv(array: JsonArray): DoubleArray? {
        if (array.size() < 4) return null
        val values = DoubleArray(4)
        for (i in 0..3) {
            val value = array[i].takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                ?: return null
            values[i] = value
        }
        return values
    }

    private fun parseDisplay(obj: JsonObject): ModelDisplayTransform {
        val rotation = parseVec3(obj.getAsJsonArray("rotation")) ?: Vec3(0.0, 0.0, 0.0)
        val scale = parseVec3(obj.getAsJsonArray("scale")) ?: Vec3(1.0, 1.0, 1.0)
        return ModelDisplayTransform(rotation = rotation, scale = scale)
    }

    private fun parseRotation(obj: JsonObject): ElementRotation? {
        val origin = parseVec3(obj.getAsJsonArray("origin")) ?: return null
        val axis = obj.get("axis")?.asString?.lowercase() ?: return null
        val angle = obj.get("angle")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            ?: return null
        return ElementRotation(origin, axis, angle)
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

    fun clearCache() {
        cache.clear()
    }

    private data class RawBlockModel(
        val parent: String?,
        val textures: Map<String, String>,
        val elements: List<BlockElement>,
        val displayGui: ModelDisplayTransform?
    )

    companion object {
        private const val MAX_DEPTH = 16
    }
}
