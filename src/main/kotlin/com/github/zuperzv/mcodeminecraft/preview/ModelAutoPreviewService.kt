package com.github.zuperzv.mcodeminecraft.preview

import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
class ModelAutoPreviewService(private val project: Project) {

    private var activeJsonFile: VirtualFile? = null

    init {
        println("ModelAutoPreviewService STARTED")

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (file != activeJsonFile || file.extension != "json") return
                    try {
                        project.getService(ModelViewerService::class.java)
                            .loadModel(event.document.text, resetCamera = false)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            project
        )

        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {

                    val file = event.newFile ?: return
                    println("FILE SELECTED: " + file.name)

                    if (file.extension != "json") {
                        activeJsonFile = null
                        project.getService(ModelViewerService::class.java).clearHoverHighlight()
                        return
                    }
                    activeJsonFile = file

                    try {
                        val json = String(file.contentsToByteArray())

                        println("JSON LOADED -> sending to viewer")

                        project.getService(ModelViewerService::class.java)
                            .loadModel(json, resetCamera = true)

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )
    }
}
