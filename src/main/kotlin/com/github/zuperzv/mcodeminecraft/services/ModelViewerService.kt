package com.github.zuperzv.mcodeminecraft.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.Font

@Service(Service.Level.PROJECT)
class ModelViewerService(private val project: Project) {

    private var browser: JBCefBrowser? = null
    private var pendingJson: String? = null
    private var pageReady = false
    private var hoverQuery: JBCefJSQuery? = null
    private var hoverHighlighter: RangeHighlighter? = null
    private var hoverEditor: Editor? = null
    private var lastHoverIndex: Int? = null

    fun setBrowser(b: JBCefBrowser) {
        println("Browser registered")
        browser = b
        hoverQuery?.dispose()
        hoverQuery = JBCefJSQuery.create(b).apply {
            addHandler { query ->
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
        return hoverQuery?.inject(functionName) ?: ""
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
        val index = request.trim().toIntOrNull() ?: return
        if (index < 0) {
            clearHoverHighlight()
            return
        }
        if (index == lastHoverIndex) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            clearHoverHighlight()
            return
        }
        val ranges = findElementRanges(editor.document.text)
        if (index >= ranges.size) {
            clearHoverHighlight()
            return
        }
        val range = ranges[index]
        if (hoverEditor != editor || lastHoverIndex != index) {
            clearHoverHighlight()
            val attrs = TextAttributes(
                null,
                Color(0xC0, 0xC0, 0xC0),
                null,
                EffectType.BOXED,
                Font.PLAIN
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
}
