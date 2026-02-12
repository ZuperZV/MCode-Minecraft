package com.github.zuperzv.mcodeminecraft.assets

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class MinecraftAssetPreloadActivity : StartupActivity {
    override fun runActivity(project: Project) {
        project.getService(MinecraftAssetIndexService::class.java).preload()
    }
}
