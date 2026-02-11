package com.github.zuperzv.mcodeminecraft.registry

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import java.nio.file.Files
import java.nio.file.Path

class MinecraftJarDownloader(
    private val cacheRoot: Path
) {
    private val logger = Logger.getInstance(MinecraftJarDownloader::class.java)

    fun getClientJar(version: String): Path = getJar(version, "client")

    fun getServerJar(version: String): Path = getJar(version, "server")

    private fun getJar(version: String, kind: String): Path {
        val versionDir = cacheRoot.resolve(version)
        val jarPath = versionDir.resolve("$kind.jar")
        if (Files.exists(jarPath)) {
            return jarPath
        }
        Files.createDirectories(versionDir)
        val manifest = fetchJson(MANIFEST_URL)
        val versionUrl = manifest.getAsJsonArray("versions")
            .firstOrNull { it.asJsonObject.get("id")?.asString == version }
            ?.asJsonObject
            ?.get("url")
            ?.asString
            ?: error("Minecraft version $version not found in manifest.")
        val versionJson = fetchJson(versionUrl)
        val downloads = versionJson.getAsJsonObject("downloads")
        val jarUrl = downloads.getAsJsonObject(kind)?.get("url")?.asString
            ?: error("Minecraft version $version has no $kind download.")
        downloadTo(jarUrl, jarPath)
        return jarPath
    }

    private fun fetchJson(url: String) =
        JsonParser.parseString(HttpRequests.request(url).readString()).asJsonObject

    private fun downloadTo(url: String, target: Path) {
        val temp = Files.createTempFile("mc-jar", ".jar")
        try {
            HttpRequests.request(url).saveToFile(temp, null)
            FileUtil.copy(temp.toFile(), target.toFile())
        } catch (e: Exception) {
            logger.warn("Failed to download jar from $url", e)
            throw e
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        private const val MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json"
    }
}
