package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MinecraftAssetPreloadActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(MinecraftAssetIndexService::class.java).preload()
    }
}
