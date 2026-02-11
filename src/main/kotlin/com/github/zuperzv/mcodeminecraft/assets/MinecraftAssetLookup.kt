package com.github.zuperzv.mcodeminecraft.assets

import java.io.InputStream

interface MinecraftAssetLookup {
    fun getModelForId(id: String): ResourceLocation?

    fun getTexturesForModel(model: ResourceLocation): Map<String, ResourceLocation>

    fun getDisplayName(id: String): String?

    fun listBlockIds(): Set<String>

    fun listItemIds(): Set<String>

    fun openModelStream(model: ResourceLocation): InputStream?

    fun openTextureStream(texture: ResourceLocation): InputStream?
}
