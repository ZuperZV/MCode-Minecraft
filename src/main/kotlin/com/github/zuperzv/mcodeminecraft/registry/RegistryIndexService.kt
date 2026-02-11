package com.github.zuperzv.mcodeminecraft.registry

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class RegistryIndex(
    val version: String,
    val items: Set<String>,
    val blocks: Set<String>
)

@Service(Service.Level.PROJECT)
class RegistryIndexService(private val project: Project) {
    private val logger = Logger.getInstance(RegistryIndexService::class.java)
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val versionResolver = MinecraftVersionResolver()
    private val envDetector = MinecraftEnvironmentDetector()
    private val loader = RegistryReportLoader()
    private val cacheDir = Path.of(PathManager.getSystemPath(), "mc-registry-cache")
    private val downloader = MinecraftJarDownloader(cacheDir)
    private val cached = ConcurrentHashMap<String, CompletableFuture<RegistryIndex?>>()

    fun getIndexAsync(): CompletableFuture<RegistryIndex?> {
        val version = versionResolver.resolve(project)
        if (version.isNullOrBlank()) {
            logger.info("RegistryIndexService: minecraft_version not found")
            return CompletableFuture.completedFuture(null)
        }
        return cached.computeIfAbsent(version) {
            CompletableFuture.supplyAsync({
                try {
                    buildIndex(version)
                } catch (e: Exception) {
                    logger.warn("Registry index failed for $version", e)
                    null
                }
            }, executor)
        }
    }

    fun clearCache() {
        cached.clear()
    }

    private fun buildIndex(version: String): RegistryIndex {
        val generated = envDetector.findGeneratedReports(project)
        if (generated != null) {
            logger.warn("RegistryIndexService: using generated reports")
            val entries = loader.loadFromGeneratedReports(generated)
            logger.warn("RegistryIndexService: generated entries=${entries.size}")
            return toIndex(version, entries)
        }

        val clientJar = downloader.getClientJar(version)
        val serverJar = downloader.getServerJar(version)
        logger.warn("RegistryIndexService: client jar=$clientJar")
        logger.warn("RegistryIndexService: server jar=$serverJar")

        val fromServer = loader.loadFromJarReports(serverJar)
        if (fromServer.isNotEmpty()) {
            logger.warn("RegistryIndexService: server reports entries=${fromServer.size}")
            return toIndex(version, fromServer)
        }

        val fromClient = loader.loadFromJarReports(clientJar)
        if (fromClient.isNotEmpty()) {
            logger.warn("RegistryIndexService: client reports entries=${fromClient.size}")
            return toIndex(version, fromClient)
        }

        val fallback = loader.loadFallbackRegistries(serverJar)
        logger.warn("RegistryIndexService: fallback registry entries=${fallback.size}")
        return toIndex(version, fallback)
    }

    private fun toIndex(version: String, entries: List<RegistryEntry>): RegistryIndex {
        val items = entries.filter { it.type == RegistryType.ITEM }.map { it.id }.toSet()
        val blocks = entries.filter { it.type == RegistryType.BLOCK }.map { it.id }.toSet()
        logger.warn("RegistryIndexService: items=${items.size} blocks=${blocks.size}")
        return RegistryIndex(version, items, blocks)
    }
}
