package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class MinecraftAssetIndexService(private val project: Project) {
    private val logger = Logger.getInstance(MinecraftAssetIndexService::class.java)
    private val detector = GradleMinecraftVersionDetector()
    private val downloader = MinecraftAssetDownloader(MojangVersionManifestClient())
    private val indexer = MinecraftAssetIndexer()
    private val executor = AppExecutorUtil.getAppExecutorService()

    @Volatile
    private var cachedVersion: String? = null
    private var cachedFuture: CompletableFuture<MinecraftAssetRepository?>? = null

    fun preload() {
        getRepositoryAsync()
    }

    fun getRepositoryAsync(): CompletableFuture<MinecraftAssetRepository?> {
        val version = detector.detect(project)
        if (version.isNullOrBlank()) {
            return CompletableFuture.completedFuture(null)
        }
        synchronized(this) {
            if (cachedVersion == version && cachedFuture != null) {
                return cachedFuture as CompletableFuture<MinecraftAssetRepository?>
            }
            val future = CompletableFuture.supplyAsync({
                try {
                    val jar = downloader.getClientJar(version)
                    val index = indexer.index(jar, version)
                    MinecraftAssetRepository(jar, index)
                } catch (e: Exception) {
                    logger.warn("Minecraft asset indexing failed for $version", e)
                    null
                }
            }, executor)
            cachedVersion = version
            cachedFuture = future
            return future
        }
    }

    fun getRepositoryIfReady(): MinecraftAssetRepository? {
        val future = cachedFuture ?: return null
        if (!future.isDone || future.isCompletedExceptionally) {
            return null
        }
        return runCatching { future.get() }.getOrNull()
    }

    fun clearCache() {
        synchronized(this) {
            cachedVersion = null
            cachedFuture = null
        }
    }
}
