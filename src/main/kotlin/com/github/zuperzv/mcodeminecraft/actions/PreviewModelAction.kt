package com.github.zuperzv.mcodeminecraft.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.github.zuperzv.mcodeminecraft.services.ModelViewerService

class PreviewModelAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (file.extension != "json") return

        val json = file.contentsToByteArray().toString(Charsets.UTF_8)
        e.project?.getService(ModelViewerService::class.java)?.loadModel(json)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
