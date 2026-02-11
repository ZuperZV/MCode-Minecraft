package com.github.zuperzv.mcodeminecraft.services

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalogService
import com.github.zuperzv.mcodeminecraft.assets.MinecraftAssetIndexService
import com.github.zuperzv.mcodeminecraft.assets.ProjectAssetIndexService
import com.github.zuperzv.mcodeminecraft.registry.RegistryIndexService
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

@Service(Service.Level.PROJECT)
class PluginCacheService(private val project: Project) {
    private val logger = Logger.getInstance(PluginCacheService::class.java)

    data class ClearResult(
        val deleted: List<Path>,
        val errors: List<String>
    )

    fun clearAllCaches(): ClearResult {
        val deleted = ArrayList<Path>()
        val errors = ArrayList<String>()

        project.getService(AssetCatalogService::class.java).clearCache()
        project.getService(MinecraftAssetIndexService::class.java).clearCache()
        project.getService(ProjectAssetIndexService::class.java).clearCache()
        project.getService(RegistryIndexService::class.java).clearCache()

        val systemPath = Path.of(PathManager.getSystemPath())
        val cacheDirs = listOf(
            systemPath.resolve("mcodeminecraft"),
            systemPath.resolve("mc-registry-cache")
        )
        cacheDirs.forEach { dir ->
            try {
                if (deleteRecursively(dir)) {
                    deleted.add(dir)
                }
            } catch (e: Exception) {
                logger.warn("Failed to delete cache dir $dir", e)
                errors.add("${dir.fileName}: ${e.message}")
            }
        }
        return ClearResult(deleted = deleted, errors = errors)
    }

    private fun deleteRecursively(path: Path): Boolean {
        if (!Files.exists(path)) return false
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
        return true
    }
}
