package com.github.zuperzv.mcodeminecraft.registry

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class MinecraftVersionResolver {
    fun resolve(project: Project): String? {
        val basePath = project.basePath ?: return null
        val gradleProps = Path.of(basePath, "gradle.properties")
        if (!Files.exists(gradleProps)) return null
        val props = Properties()
        Files.newInputStream(gradleProps).use { props.load(it) }
        return props.getProperty("minecraft_version")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
