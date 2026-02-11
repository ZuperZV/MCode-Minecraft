package com.github.zuperzv.mcodeminecraft.registry

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarFile

data class RegistryEntry(
    val id: String,
    val namespace: String,
    val path: String,
    val type: RegistryType
)

enum class RegistryType {
    ITEM,
    BLOCK
}

class RegistryReportLoader {
    private val locale = Locale.US

    fun loadFromGeneratedReports(reports: EnvironmentReports): List<RegistryEntry> {
        val items = reports.items?.let { loadSimpleReport(it, RegistryType.ITEM) }.orEmpty()
        val blocks = reports.blocks?.let { loadSimpleReport(it, RegistryType.BLOCK) }.orEmpty()
        val fallback = reports.registries?.let {
            Files.newInputStream(it).use { stream ->
                loadRegistriesJson(stream, "minecraft:item", RegistryType.ITEM) +
                    loadRegistriesJson(stream, "minecraft:block", RegistryType.BLOCK)
            }
        }.orEmpty()
        return combine(items, blocks, fallback)
    }

    fun loadFromJarReports(jarPath: Path): List<RegistryEntry> {
        return withJarSources(jarPath) { sources ->
            val items = ArrayList<RegistryEntry>()
            val blocks = ArrayList<RegistryEntry>()
            val fallback = ArrayList<RegistryEntry>()
            sources.forEach { source ->
                items.addAll(loadReportFromSource(source, "reports/items.json", RegistryType.ITEM))
                blocks.addAll(loadReportFromSource(source, "reports/blocks.json", RegistryType.BLOCK))
                fallback.addAll(loadRegistriesFromSource(source, "reports/registries.json"))
            }
            combine(items, blocks, fallback)
        }
    }

    fun loadFallbackRegistries(jarPath: Path): List<RegistryEntry> {
        return withJarSources(jarPath) { sources ->
            val result = ArrayList<RegistryEntry>()
            sources.forEach { source ->
                result.addAll(loadRegistriesJsonFromData(source))
                result.addAll(loadEntriesFromTags(source, RegistryType.ITEM))
                result.addAll(loadEntriesFromTags(source, RegistryType.BLOCK))
            }
            result
        }
    }

    private fun loadSimpleReport(path: Path, type: RegistryType): List<RegistryEntry> =
        Files.newInputStream(path).use { loadSimpleReport(it, type) }

    private fun loadSimpleReport(stream: InputStream, type: RegistryType): List<RegistryEntry> {
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val root = JsonParser.parseReader(reader)
            if (!root.isJsonObject) return emptyList()
            return root.asJsonObject.entrySet().map { entry ->
                toEntry(entry.key, type)
            }
        }
    }

    private fun loadRegistriesJson(
        stream: InputStream,
        registryKey: String,
        type: RegistryType
    ): List<RegistryEntry> {
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val root = JsonParser.parseReader(reader)
            if (!root.isJsonObject) return emptyList()
            val registry = root.asJsonObject.getAsJsonObject(registryKey) ?: return emptyList()
            val entries = registry.get("entries")
            return when {
                entries != null && entries.isJsonObject ->
                    entries.asJsonObject.entrySet().map { entry -> toEntry(entry.key, type) }
                entries != null && entries.isJsonArray ->
                    entries.asJsonArray.mapNotNull { element -> element.asStringOrNull() }
                        .map { id -> toEntry(id, type) }
                registry.has("values") && registry.get("values").isJsonArray ->
                    registry.getAsJsonArray("values").mapNotNull { it.asStringOrNull() }
                        .map { id -> toEntry(id, type) }
                else -> emptyList()
            }
        }
    }

    private fun loadReportFromSource(
        source: JarSource,
        path: String,
        type: RegistryType
    ): List<RegistryEntry> {
        val entry = source.jar.getJarEntry(path) ?: return emptyList()
        source.jar.getInputStream(entry).use { stream ->
            return loadSimpleReport(stream, type)
        }
    }

    private fun loadRegistriesFromSource(source: JarSource, path: String): List<RegistryEntry> {
        val entry = source.jar.getJarEntry(path) ?: return emptyList()
        source.jar.getInputStream(entry).use { stream ->
            return loadRegistriesJson(stream, "minecraft:item", RegistryType.ITEM) +
                loadRegistriesJson(stream, "minecraft:block", RegistryType.BLOCK)
        }
    }

    private fun loadRegistriesJsonFromData(source: JarSource): List<RegistryEntry> {
        val matches = source.jar.entries().asSequence()
            .filter { it.name.startsWith("data/") && it.name.endsWith("registries.json") }
            .toList()
        if (matches.isEmpty()) return emptyList()
        val result = ArrayList<RegistryEntry>()
        matches.forEach { entry ->
            source.jar.getInputStream(entry).use { stream ->
                result.addAll(loadRegistriesJson(stream, "minecraft:item", RegistryType.ITEM))
                result.addAll(loadRegistriesJson(stream, "minecraft:block", RegistryType.BLOCK))
            }
        }
        return result
    }

    private fun loadEntriesFromTags(source: JarSource, type: RegistryType): List<RegistryEntry> {
        val prefixes = when (type) {
            RegistryType.ITEM -> listOf("/tags/item/", "/tags/items/")
            RegistryType.BLOCK -> listOf("/tags/block/", "/tags/blocks/")
        }
        val tagFiles = source.jar.entries().asSequence()
            .filter { entry ->
                entry.name.startsWith("data/") &&
                    entry.name.endsWith(".json") &&
                    prefixes.any { entry.name.contains(it) }
            }
            .toList()
        if (tagFiles.isEmpty()) return emptyList()

        val tagValues = HashMap<String, List<String>>()
        tagFiles.forEach { entry ->
            val tagId = toTagId(entry.name) ?: return@forEach
            source.jar.getInputStream(entry).use { stream ->
                val values = readTagValues(stream)
                if (values.isNotEmpty()) {
                    tagValues[tagId] = values
                }
            }
        }
        if (tagValues.isEmpty()) return emptyList()

        val resolved = HashSet<String>()
        val visiting = HashSet<String>()

        fun resolveTag(tag: String) {
            if (!visiting.add(tag)) return
            val values = tagValues[tag] ?: return
            values.forEach { value ->
                if (value.startsWith("#")) {
                    resolveTag(value.removePrefix("#").lowercase(locale))
                } else {
                    resolved.add(value.lowercase(locale))
                }
            }
        }

        tagValues.keys.forEach { resolveTag(it) }
        return resolved.map { id -> toEntry(id, type) }
    }

    private fun toTagId(path: String): String? {
        if (!path.startsWith("data/")) return null
        val parts = path.split("/")
        if (parts.size < 5) return null
        val namespace = parts[1]
        val tagIndex = parts.indexOf("tags")
        if (tagIndex < 0 || tagIndex + 2 >= parts.size) return null
        val kind = parts[tagIndex + 1]
        val suffixParts = parts.drop(tagIndex + 2)
        val tagPath = suffixParts.joinToString("/").removeSuffix(".json")
        return "$namespace:$tagPath"
    }

    private fun readTagValues(stream: InputStream): List<String> {
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val root = JsonParser.parseReader(reader)
            if (!root.isJsonObject) return emptyList()
            val values = root.asJsonObject.getAsJsonArray("values") ?: return emptyList()
            return values.mapNotNull { it.asStringOrNull() }
        }
    }

    private data class JarSource(
        val name: String,
        val jar: JarFile,
        val tempPath: Path?
    )

    private fun <T> withJarSources(jarPath: Path, action: (List<JarSource>) -> T): T {
        val sources = ArrayList<JarSource>()
        try {
            val outer = JarFile(jarPath.toFile())
            sources.add(JarSource(jarPath.fileName.toString(), outer, null))
            val nested = outer.entries().asSequence()
                .filter { it.name.startsWith("META-INF/versions/") && it.name.endsWith(".jar") }
                .toList()
            nested.forEach { entry ->
                val temp = Files.createTempFile("mc-nested", ".jar")
                outer.getInputStream(entry).use { input ->
                    Files.newOutputStream(temp).use { output -> input.copyTo(output) }
                }
                sources.add(JarSource(entry.name, JarFile(temp.toFile()), temp))
            }
            return action(sources)
        } finally {
            sources.forEach { source ->
                runCatching { source.jar.close() }
                source.tempPath?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun JsonElement.asStringOrNull(): String? {
        return if (isJsonPrimitive && asJsonPrimitive.isString) asString else null
    }

    private fun toEntry(id: String, type: RegistryType): RegistryEntry {
        val parts = id.split(":", limit = 2)
        val namespace = if (parts.size == 2) parts[0] else "minecraft"
        val path = if (parts.size == 2) parts[1] else id
        return RegistryEntry(id = id, namespace = namespace, path = path, type = type)
    }

    private fun combine(
        items: List<RegistryEntry>,
        blocks: List<RegistryEntry>,
        fallback: List<RegistryEntry>
    ): List<RegistryEntry> {
        val map = LinkedHashMap<String, RegistryEntry>()
        (items + blocks + fallback).forEach { entry ->
            map.putIfAbsent("${entry.type}:${entry.id}", entry)
        }
        return map.values.toList()
    }
}
