package com.github.zuperzv.mcodeminecraft.assets

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class ProjectAssetRepository(
    private val resourceRoots: List<Path>,
    val index: ProjectAssetIndex
) {
    fun openModelStream(model: ResourceLocation): InputStream? {
        return openResource(model.toModelEntryPath())
    }

    fun openTextureStream(texture: ResourceLocation): InputStream? {
        return openResource(texture.toTextureEntryPath())
    }

    fun openBlockstateStream(blockId: ResourceLocation): InputStream? {
        return openResource(blockId.toBlockstateEntryPath())
    }

    private fun openResource(relativePath: String): InputStream? {
        for (root in resourceRoots) {
            val path = root.resolve(relativePath)
            if (Files.exists(path) && Files.isRegularFile(path)) {
                return Files.newInputStream(path)
            }
        }
        return null
    }
}
