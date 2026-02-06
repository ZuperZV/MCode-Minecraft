package com.github.zuperzv.mcodeminecraft.preview

import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ModelAutoPreviewService(private val project: Project) {

    init {
        println("ModelAutoPreviewService STARTED")

        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {

                    val file = event.newFile ?: return
                    println("FILE SELECTED: " + file.name)

                    if (file.extension != "json") return

                    try {
                        val json = String(file.contentsToByteArray())

                        println("JSON LOADED -> sending to viewer")

                        project.getService(ModelViewerService::class.java)
                            .loadModel(json)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )
    }
}
