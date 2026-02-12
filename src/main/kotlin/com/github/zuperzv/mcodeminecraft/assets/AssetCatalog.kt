package com.github.zuperzv.mcodeminecraft.assets

import java.io.InputStream
import java.util.Locale

class AssetCatalog(
    private val projectRepository: ProjectAssetRepository?,
    private val vanillaRepository: MinecraftAssetRepository?
) {
    private val projectIndex = projectRepository?.index
    private val vanillaIndex = vanillaRepository?.index

    fun allItemIds(): Set<String> {
        val items = HashSet<String>()
        projectIndex?.itemIds?.let { items.addAll(it) }
        vanillaIndex?.itemIds?.let { items.addAll(it) }
        return items
    }

    fun allBlockIds(): Set<String> {
        val blocks = HashSet<String>()
        projectIndex?.blockIds?.let { blocks.addAll(it) }
        vanillaIndex?.blockIds?.let { blocks.addAll(it) }
        return blocks
    }

    fun allTags(): Set<String> {
        val tags = HashSet<String>()
        projectIndex?.tagIndex?.allTags()?.let { tags.addAll(it) }
        vanillaIndex?.tagIndex?.allTags()?.let { tags.addAll(it) }
        return tags
    }

    fun allFluidIds(): Set<String> {
        val fluids = HashSet<String>()
        projectIndex?.tagIndex?.allFluidIds()?.let { fluids.addAll(it) }
        vanillaIndex?.tagIndex?.allFluidIds()?.let { fluids.addAll(it) }
        return fluids
    }

    fun hasItem(id: String): Boolean {
        val normalized = normalizeId(id)
        return (projectIndex?.itemIds?.contains(normalized) == true) ||
            (projectIndex?.blockIds?.contains(normalized) == true) ||
            (vanillaIndex?.itemIds?.contains(normalized) == true) ||
            (vanillaIndex?.blockIds?.contains(normalized) == true)
    }

    fun hasTag(tagId: String): Boolean {
        return projectIndex?.tagIndex?.hasTag(tagId) == true ||
            vanillaIndex?.tagIndex?.hasTag(tagId) == true
    }

    fun resolveDisplayName(id: String): String? {
        val normalized = normalizeId(id)
        return projectIndex?.displayNames?.get(normalized)
            ?: vanillaIndex?.displayNames?.get(normalized)
    }

    fun resolveModel(id: String): ResourceLocation? {
        val normalized = normalizeId(id)
        return projectIndex?.modelsById?.get(normalized)
            ?: vanillaIndex?.modelsById?.get(normalized)
    }

    fun resolveTextures(model: ResourceLocation): Map<String, ResourceLocation> {
        return projectIndex?.modelTextures?.get(model)
            ?: vanillaIndex?.modelTextures?.get(model)
            ?: emptyMap()
    }

    fun openModelStream(model: ResourceLocation): InputStream? {
        if (projectIndex?.modelTextures?.containsKey(model) == true) {
            return projectRepository?.openModelStream(model)
        }
        return vanillaRepository?.openModelStream(model)
            ?: projectRepository?.openModelStream(model)
    }

    fun openTextureStream(texture: ResourceLocation): InputStream? {
        if (projectIndex?.availableTextures?.contains(texture) == true) {
            return projectRepository?.openTextureStream(texture)
        }
        return vanillaRepository?.openTextureStream(texture)
            ?: projectRepository?.openTextureStream(texture)
    }

    fun openBlockstateStream(blockId: ResourceLocation): InputStream? {
        return projectRepository?.openBlockstateStream(blockId)
            ?: vanillaRepository?.openBlockstateStream(blockId)
    }

    private fun normalizeId(id: String): String {
        return id.trim().lowercase(Locale.US)
    }
}
