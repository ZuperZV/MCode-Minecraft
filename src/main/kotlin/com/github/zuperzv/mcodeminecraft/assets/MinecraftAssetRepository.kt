package com.github.zuperzv.mcodeminecraft.assets

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipFile

class MinecraftAssetRepository(
    private val clientJar: Path,
    val index: MinecraftAssetIndex
) : MinecraftAssetLookup {

    override fun getModelForId(id: String): ResourceLocation? {
        return index.modelsById[normalizeId(id)]
    }

    override fun getTexturesForModel(model: ResourceLocation): Map<String, ResourceLocation> {
        return index.modelTextures[model].orEmpty()
    }

    override fun getDisplayName(id: String): String? {
        return index.displayNames[normalizeId(id)]
    }

    override fun listBlockIds(): Set<String> {
        return index.blockIds
    }

    override fun listItemIds(): Set<String> {
        return index.itemIds
    }

    override fun openModelStream(model: ResourceLocation): InputStream? {
        return openJarEntry(model.toModelEntryPath())
    }

    override fun openTextureStream(texture: ResourceLocation): InputStream? {
        return openJarEntry(texture.toTextureEntryPath())
    }

    fun openBlockstateStream(blockId: ResourceLocation): InputStream? {
        return openJarEntry(blockId.toBlockstateEntryPath())
    }

    private fun openJarEntry(entryPath: String): InputStream? {
        val zip = ZipFile(clientJar.toFile())
        val entry = zip.getEntry(entryPath) ?: run {
            zip.close()
            return null
        }
        val raw = zip.getInputStream(entry)
        return object : FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    zip.close()
                }
            }
        }
    }

    private fun normalizeId(id: String): String {
        return id.trim().lowercase(Locale.US)
    }
}
