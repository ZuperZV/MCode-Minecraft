package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class ProjectAssetIndexService(private val project: Project) {
    private val logger = Logger.getInstance(ProjectAssetIndexService::class.java)
    private val indexer = ProjectAssetIndexer()
    private val executor = AppExecutorUtil.getAppExecutorService()

    @Volatile
    private var cachedFuture: CompletableFuture<ProjectAssetRepository?>? = null

    fun getRepositoryAsync(): CompletableFuture<ProjectAssetRepository?> {
        synchronized(this) {
            cachedFuture?.let { return it }
            val future = CompletableFuture.supplyAsync({
                try {
                    val roots = findResourceRoots()
                    if (roots.isEmpty()) {
                        return@supplyAsync null
                    }
                    val index = indexer.index(roots)
                    ProjectAssetRepository(roots, index)
                } catch (e: Exception) {
                    logger.warn("Project asset indexing failed", e)
                    null
                }
            }, executor)
            cachedFuture = future
            return future
        }
    }

    private fun findResourceRoots(): List<Path> {
        val basePath = project.basePath ?: return emptyList()
        val candidates = listOf(
            Paths.get(basePath, "src", "main", "resources"),
            Paths.get(basePath, "src", "generated", "resources")
        )
        return candidates.filter { java.nio.file.Files.exists(it) && java.nio.file.Files.isDirectory(it) }
    }

    fun clearCache() {
        synchronized(this) {
            cachedFuture = null
        }
    }
}
