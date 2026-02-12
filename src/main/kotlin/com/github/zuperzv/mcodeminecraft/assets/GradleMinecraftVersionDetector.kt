package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.project.Project
import java.io.File
import java.io.FileInputStream
import java.util.Properties

class GradleMinecraftVersionDetector {
    fun detect(project: Project, propertyName: String): String? {
        val basePath = project.basePath ?: return null
        val propertiesFile = File(basePath, "gradle.properties")
        if (!propertiesFile.exists()) {
            return null
        }

        val properties = Properties()
        FileInputStream(propertiesFile).use { stream ->
            properties.load(stream)
        }

        val raw = properties.getProperty(propertyName) ?: return null
        return normalize(raw)
    }

    private fun normalize(raw: String): String? {
        val trimmed = raw.trim().trim('"', '\'')
        return trimmed.takeIf { it.isNotEmpty() }
    }
}