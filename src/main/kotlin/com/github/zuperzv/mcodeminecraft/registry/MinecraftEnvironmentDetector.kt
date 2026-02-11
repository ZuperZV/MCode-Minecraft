package com.github.zuperzv.mcodeminecraft.registry

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path

data class EnvironmentReports(
    val items: Path?,
    val blocks: Path?,
    val registries: Path?
)

class MinecraftEnvironmentDetector {
    fun findGeneratedReports(project: Project): EnvironmentReports? {
        val basePath = project.basePath ?: return null
        val root = Path.of(basePath, "build", "generated", "reports")
        if (!Files.exists(root)) return null
        val items = root.resolve("items.json").takeIf { Files.exists(it) }
        val blocks = root.resolve("blocks.json").takeIf { Files.exists(it) }
        val registries = root.resolve("registries.json").takeIf { Files.exists(it) }
        if (items == null && blocks == null && registries == null) return null
        return EnvironmentReports(items, blocks, registries)
    }
}
