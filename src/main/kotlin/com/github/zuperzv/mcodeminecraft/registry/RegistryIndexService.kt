package com.github.zuperzv.mcodeminecraft.registry

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.nio.file.Paths
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

        val resourceRoots = findResourceRoots()
        val projectEntries = loader.loadFromProjectResources(resourceRoots)
        logger.warn("RegistryIndexService: project entries=${projectEntries.size}")

        val generated = envDetector.findGeneratedReports(project)
        if (generated != null) {
            logger.warn("RegistryIndexService: using generated reports")
            val entries = loader.loadFromGeneratedReports(generated)

            val merged = entries + projectEntries
            logger.warn("RegistryIndexService: generated entries=${entries.size} merged=${merged.size}")

            return toIndex(version, merged)
        }

        val clientJar = downloader.getClientJar(version)
        val serverJar = downloader.getServerJar(version)

        logger.warn("RegistryIndexService: client jar=$clientJar")
        logger.warn("RegistryIndexService: server jar=$serverJar")

        val fromServer = loader.loadFromJarReports(serverJar)
        if (fromServer.isNotEmpty()) {
            val merged = fromServer + projectEntries
            logger.warn("RegistryIndexService: server reports entries=${fromServer.size} merged=${merged.size}")
            return toIndex(version, merged)
        }

        val fromClient = loader.loadFromJarReports(clientJar)
        if (fromClient.isNotEmpty()) {
            val merged = fromClient + projectEntries
            logger.warn("RegistryIndexService: client reports entries=${fromClient.size} merged=${merged.size}")
            return toIndex(version, merged)
        }

        val fallback = loader.loadFallbackRegistries(serverJar)
        val merged = fallback + projectEntries
        logger.warn("RegistryIndexService: fallback registry entries=${fallback.size} merged=${merged.size}")

        return toIndex(version, merged)
    }

    private fun findResourceRoots(): List<Path> {
        val result = ArrayList<Path>()

        val modules = ModuleManager.getInstance(project).modules
        modules.forEach { module ->
            val roots = ModuleRootManager.getInstance(module).sourceRoots
            roots.forEach { root ->
                val path = Paths.get(root.path)
                if (path.toString().contains("resources")) {
                    result.add(path)
                }
            }
        }

        return result
    }

    private fun toIndex(version: String, entries: List<RegistryEntry>): RegistryIndex {
        val items = entries.filter { it.type == RegistryType.ITEM }.map { it.id }.toSet()
        val blocks = entries.filter { it.type == RegistryType.BLOCK }.map { it.id }.toSet()
        logger.warn("RegistryIndexService: items=${items.size} blocks=${blocks.size}")
        return RegistryIndex(version, items, blocks)
    }
}
