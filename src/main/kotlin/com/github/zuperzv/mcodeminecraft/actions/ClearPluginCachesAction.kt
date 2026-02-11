package com.github.zuperzv.mcodeminecraft.actions

import com.github.zuperzv.mcodeminecraft.services.PluginCacheService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class ClearPluginCachesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!confirm(project)) return
        val result = project.getService(PluginCacheService::class.java).clearAllCaches()
        val deleted = if (result.deleted.isEmpty()) "None" else result.deleted.joinToString("\n")
        val errors = if (result.errors.isEmpty()) "None" else result.errors.joinToString("\n")
        Messages.showInfoMessage(
            project,
            "Deleted:\n$deleted\n\nErrors:\n$errors",
            "MCode Minecraft Cache Cleanup"
        )
    }

    private fun confirm(project: Project): Boolean {
        val answer = Messages.showYesNoDialog(
            project,
            "Delete all MCode Minecraft plugin caches?",
            "Clear Plugin Caches",
            null
        )
        return answer == Messages.YES
    }
}
