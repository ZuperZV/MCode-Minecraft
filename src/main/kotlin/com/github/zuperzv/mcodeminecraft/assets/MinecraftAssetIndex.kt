package com.github.zuperzv.mcodeminecraft.assets

data class MinecraftAssetIndex(
    val version: String,
    val modelsById: Map<String, ResourceLocation>,
    val modelTextures: Map<ResourceLocation, Map<String, ResourceLocation>>,
    val displayNames: Map<String, String>,
    val availableTextures: Set<ResourceLocation>,
    val blockIds: Set<String>,
    val itemIds: Set<String>,
    val tagIndex: TagIndex
)
