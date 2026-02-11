package com.github.zuperzv.mcodeminecraft.recipe

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class RecipeDocumentService(private val project: Project) {
    private val parser = RecipeParser()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val listeners = CopyOnWriteArrayList<(RecipeDocument?) -> Unit>()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    @Volatile
    private var activeJsonFile: VirtualFile? = null
    @Volatile
    private var activeEditor: Editor? = null
    @Volatile
    private var lastText: String? = null

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (file != activeJsonFile || file.extension != "json") return
                    scheduleParse(event.document.text)
                }
            },
            project
        )
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile
                    if (file == null || file.extension != "json") {
                        activeJsonFile = null
                        activeEditor = null
                        notifyListeners(null)
                        return
                    }
                    activeJsonFile = file
                    activeEditor = (event.newEditor as? com.intellij.openapi.fileEditor.TextEditor)?.editor
                        ?: FileEditorManager.getInstance(project).selectedTextEditor
                    lastText = null
                    scheduleParse(readFileText(file))
                }
            }
        )
    }

    fun addListener(listener: (RecipeDocument?) -> Unit) {
        listeners.add(listener)
    }

    fun applySelection(binding: SlotBinding, selection: IngredientSelection) {
        val editor = activeEditor ?: return
        val document = editor.document
        val text = document.text
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            return
        }
        RecipeJsonEditor.applySelection(root, binding, selection)
        val updated = gson.toJson(root)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(updated)
        }
    }

    private fun scheduleParse(text: String?) {
        if (text == null) {
            notifyListeners(null)
            return
        }
        if (text == lastText) {
            return
        }
        lastText = text
        alarm.cancelAllRequests()
        alarm.addRequest({
            val parsed = parser.parse(text)
            notifyListeners(parsed)
        }, 200)
    }

    private fun notifyListeners(document: RecipeDocument?) {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it(document) }
        }
    }

    private fun readFileText(file: VirtualFile): String? {
        return runCatching { String(file.contentsToByteArray()) }.getOrNull()
    }
}
