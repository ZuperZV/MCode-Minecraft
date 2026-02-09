package com.github.zuperzv.mcodeminecraft.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.Font

@Service(Service.Level.PROJECT)
class ModelViewerService(private val project: Project) {

    private val logger = Logger.getInstance(ModelViewerService::class.java)

    val TEXT_HIGHLIGHT_TEXT: Color =
        JBColor.namedColor(
            "SplitPane.highlight",
            JBColor(0x3C3F41, 0x3C3F41)
        )

    private var browser: JBCefBrowser? = null
    private var pendingJson: String? = null
    private var pageReady = false
    private var activeModelFile: com.intellij.openapi.vfs.VirtualFile? = null
    private var activeModelEditor: Editor? = null
    private var hoverQuery: JBCefJSQuery? = null
    private var hoverHighlighter: RangeHighlighter? = null
    private var hoverEditor: Editor? = null
    private var lastHoverIndex: Int? = null
    private val scrollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)

    fun setBrowser(b: JBCefBrowser) {
        println("Browser registered")
        browser = b
        hoverQuery?.dispose()
        @Suppress("DEPRECATION")
        hoverQuery = JBCefJSQuery.create(b).apply {
            addHandler { query ->
                println("Hover query received: $query")
                logger.warn("Hover query received: $query")
                handleHoverRequest(query)
                null
            }
        }

        b.jbCefClient.addLoadHandler(
            object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(
                    browser: CefBrowser,
                    frame: CefFrame,
                    httpStatusCode: Int
                ) {
                    if (frame.isMain) {
                        println("Viewer HTML ready")
                        pageReady = true
                        pendingJson?.let {
                            loadModel(it)
                            pendingJson = null
                        }
                    }
                }
            },
            b.cefBrowser
        )
    }

    fun getHoverQueryInjection(functionName: String): String {
        if (hoverQuery == null) {
            println("Hover query not ready; injection skipped")
            logger.warn("Hover query not ready; injection skipped")
        }
        return hoverQuery?.inject(functionName) ?: ""
    }

    fun setActiveModelFile(file: com.intellij.openapi.vfs.VirtualFile?) {
        activeModelFile = file
    }

    fun setActiveModelEditor(editor: Editor?) {
        activeModelEditor = editor
    }

    fun loadModel(json: String, resetCamera: Boolean = true) {
        println("loadModel called")

        pageReady = true

        if (browser == null || !pageReady) {
            println("Viewer not ready -> queue")
            pendingJson = json
            return
        }

        val escaped = json
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")

        val jsCode = "window.loadModelFromJson(`$escaped`, ${resetCamera});"

        browser!!.cefBrowser.executeJavaScript(
            jsCode,
            browser!!.cefBrowser.url,
            0
        )
    }

    fun clearHoverHighlight() {
        hoverHighlighter?.dispose()
        hoverHighlighter = null
        hoverEditor = null
        lastHoverIndex = null
    }

    private fun handleHoverRequest(request: String) {
        println("Hover request raw: '$request'")
        val trimmed = request.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") {
            println("Hover request not numeric: '$trimmed'")
            logger.warn("Hover request not numeric: '$trimmed'")
            return
        }
        if (trimmed == "ping") {
            println("Hover ping received")
            logger.warn("Hover ping received")
            return
        }
        val index = trimmed.toIntOrNull() ?: return
        if (index < 0) {
            println("Hover cleared")
            logger.warn("Hover cleared")
            clearHoverHighlight()
            return
        }
        if (index == lastHoverIndex) return
        val editor = resolveHoverEditor()
        if (editor == null) {
            println("Hover editor not available")
            logger.warn("Hover editor not available")
            clearHoverHighlight()
            return
        }
        val ranges = findElementRanges(editor.document.text)
        println("Hover ranges found: ${ranges.size}")
        if (index >= ranges.size) {
            println("Hover index out of range: $index >= ${ranges.size}")
            logger.warn("Hover index out of range: $index >= ${ranges.size}")
            clearHoverHighlight()
            return
        }
        val range = ranges[index]
        if (hoverEditor != editor || lastHoverIndex != index) {
            ApplicationManager.getApplication().invokeLater {
                if (editor.isDisposed) {
                    return@invokeLater
                }
                if (hoverEditor != editor || lastHoverIndex != index) {
                    clearHoverHighlight()
                    val attrs = TextAttributes(
                        null,
                        TEXT_HIGHLIGHT_TEXT,
                        null,
                        EffectType.ROUNDED_BOX,
                        Font.BOLD
                    )
                    hoverHighlighter = editor.markupModel.addRangeHighlighter(
                        range.first,
                        range.last + 1,
                        HighlighterLayer.SELECTION - 1,
                        attrs,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    hoverEditor = editor
                    lastHoverIndex = index
                }
                val projectFocus = com.intellij.openapi.wm.IdeFocusManager.getInstance(project)
                val offset = range.first
                val expectedEditor = editor
                val expectedIndex = index
                println("Hover highlight applied: index=$index range=${range.first}-${range.last}")
                logger.warn("Hover highlight applied: index=$index range=${range.first}-${range.last}")
                projectFocus.requestFocus(editor.contentComponent, true)
                editor.caretModel.moveToOffset(offset)
                scrollAlarm.cancelAllRequests()
                scrollAlarm.addRequest({
                    if (expectedEditor.isDisposed) {
                        return@addRequest
                    }
                    if (hoverEditor != expectedEditor || lastHoverIndex != expectedIndex) {
                        return@addRequest
                    }
                    if (!isOffsetVisible(expectedEditor, offset)) {
                        expectedEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    }
                }, 500)
                projectFocus.doWhenFocusSettlesDown {
                    if (editor.isDisposed) {
                        return@doWhenFocusSettlesDown
                    }
                    projectFocus.requestFocus(editor.contentComponent, true)
                    editor.caretModel.moveToOffset(offset)
                    scrollAlarm.cancelAllRequests()
                    scrollAlarm.addRequest({
                        if (expectedEditor.isDisposed) {
                            return@addRequest
                        }
                        if (hoverEditor != expectedEditor || lastHoverIndex != expectedIndex) {
                            return@addRequest
                        }
                        if (!isOffsetVisible(expectedEditor, offset)) {
                            expectedEditor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                        }
                    }, 500)
                }
            }
        }
    }

    private fun resolveHoverEditor(): Editor? {
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedTextEditor
        val activeEditor = activeModelEditor?.takeIf { !it.isDisposed }
        if (activeEditor != null) {
            return activeEditor
        }
        if (activeModelFile == null) {
            return selected
        }
        val activeDoc = FileDocumentManager.getInstance().getDocument(activeModelFile!!)
        if (selected != null && selected.document == activeDoc) {
            return selected
        }
        val editor = manager.getEditors(activeModelFile!!).firstOrNull {
            it is TextEditor
        } as? TextEditor
        return editor?.editor ?: selected
    }

    private fun findElementRanges(text: String): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        val key = "\"elements\""
        var startIdx = text.indexOf(key)
        while (startIdx >= 0) {
            var i = startIdx + key.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ':') {
                startIdx = text.indexOf(key, startIdx + 1)
                continue
            }
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '[') {
                startIdx = text.indexOf(key, startIdx + 1)
                continue
            }
            i++
            var inString = false
            var escaped = false
            var braceDepth = 0
            var currentStart = -1
            while (i < text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{' -> {
                            if (braceDepth == 0) {
                                currentStart = i
                            }
                            braceDepth++
                        }
                        '}' -> {
                            if (braceDepth > 0) {
                                braceDepth--
                                if (braceDepth == 0 && currentStart >= 0) {
                                    ranges.add(currentStart..i)
                                    currentStart = -1
                                }
                            }
                        }
                        ']' -> if (braceDepth == 0) {
                            return ranges
                        }
                    }
                }
                i++
            }
            return ranges
        }
        return ranges
    }

    private fun isOffsetVisible(editor: Editor, offset: Int): Boolean {
        val visualPos = editor.offsetToVisualPosition(offset)
        val point = editor.visualPositionToXY(visualPos)
        return editor.scrollingModel.visibleArea.contains(point)
    }
}
