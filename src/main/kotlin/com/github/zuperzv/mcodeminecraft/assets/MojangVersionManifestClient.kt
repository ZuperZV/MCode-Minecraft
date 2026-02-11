package com.github.zuperzv.mcodeminecraft.assets

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class MojangVersionManifestClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) {
    private val versionUrlCache = ConcurrentHashMap<String, String>()

    fun getClientJarInfo(version: String): ClientJarInfo {
        val versionUrl = versionUrlCache[version] ?: run {
            val manifest = fetchJson(MANIFEST_URL)
            val versions = manifest.getAsJsonArray("versions")
            val match = versions.firstOrNull { element ->
                element.asJsonObject.get("id")?.asString == version
            } ?: throw IllegalStateException("Minecraft version not found in manifest: $version")
            val url = match.asJsonObject.get("url").asString
            versionUrlCache[version] = url
            url
        }
        val versionJson = fetchJson(versionUrl)
        val downloads = versionJson.getAsJsonObject("downloads")
        val client = downloads.getAsJsonObject("client")
        val url = client.get("url").asString
        val sha1 = client.get("sha1")?.asString
        return ClientJarInfo(version, url, sha1)
    }

    private fun fetchJson(url: String): JsonObject {
        val request = HttpRequest.newBuilder(URI(url))
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()} for $url")
        }
        response.body().use { body ->
            InputStreamReader(body, StandardCharsets.UTF_8).use { reader ->
                return JsonParser.parseReader(reader).asJsonObject
            }
        }
    }

    data class ClientJarInfo(
        val version: String,
        val url: String,
        val sha1: String?
    )

    companion object {
        private const val MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json"
    }
}
