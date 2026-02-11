package com.github.zuperzv.mcodeminecraft.assets

import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class MinecraftAssetDownloader(
    private val manifestClient: MojangVersionManifestClient,
    private val cache: MinecraftAssetCache = MinecraftAssetCache(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
) {
    private val versionLocks = ConcurrentHashMap<String, Any>()

    fun getClientJar(version: String): Path {
        val lock = versionLocks.computeIfAbsent(version) { Any() }
        synchronized(lock) {
            val target = cache.getClientJarPath(version)
            val info = manifestClient.getClientJarInfo(version)
            if (Files.exists(target)) {
                if (info.sha1 == null || matchesSha1(target, info.sha1)) {
                    return target
                }
            }
            val temp = target.resolveSibling("client.jar.download")
            downloadToFile(info.url, temp)
            if (info.sha1 != null && !matchesSha1(temp, info.sha1)) {
                Files.deleteIfExists(temp)
                throw IOException("SHA1 mismatch for $version client.jar")
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            return target
        }
    }

    private fun downloadToFile(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI(url))
            .GET()
            .timeout(Duration.ofMinutes(2))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP ${response.statusCode()} for $url")
        }
        response.body().use { body ->
            Files.newOutputStream(target).use { output ->
                body.copyTo(output)
            }
        }
    }

    private fun matchesSha1(path: Path, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            updateDigest(digest, input)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun updateDigest(digest: MessageDigest, input: InputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
            read = input.read(buffer)
        }
    }
}
