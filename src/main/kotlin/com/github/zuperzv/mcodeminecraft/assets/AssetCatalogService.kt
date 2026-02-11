package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class AssetCatalogService(private val project: Project) {
    @Volatile
    private var cachedFuture: CompletableFuture<AssetCatalog?>? = null

    fun getCatalogAsync(): CompletableFuture<AssetCatalog?> {
        synchronized(this) {
            cachedFuture?.let { return it }
            val projectFuture = project.getService(ProjectAssetIndexService::class.java).getRepositoryAsync()
            val vanillaFuture = project.getService(MinecraftAssetIndexService::class.java).getRepositoryAsync()
            val combined = CompletableFuture.allOf(projectFuture, vanillaFuture).thenApply {
                val projectRepo = projectFuture.get()
                val vanillaRepo = vanillaFuture.get()
                AssetCatalog(projectRepo, vanillaRepo)
            }
            cachedFuture = combined
            return combined
        }
    }

    fun clearCache() {
        synchronized(this) {
            cachedFuture = null
        }
    }
}
