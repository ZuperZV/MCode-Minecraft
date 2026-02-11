package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class MinecraftAssetCache(
    private val baseDir: Path = Paths.get(
        PathManager.getSystemPath(),
        "mcodeminecraft",
        "minecraft-assets"
    )
) {
    fun getVersionDir(version: String): Path {
        val dir = baseDir.resolve(version)
        Files.createDirectories(dir)
        return dir
    }

    fun getClientJarPath(version: String): Path {
        return getVersionDir(version).resolve("client.jar")
    }
}
