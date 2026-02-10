package com.github.zuperzv.mcodeminecraft.services

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.github.zuperzv.mcodeminecraft.settings.ModelViewerSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.awt.Font
import java.math.BigDecimal
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ModelViewerService(private val project: Project) {

    private val logger = Logger.getInstance(ModelViewerService::class.java)
    private val settings = ApplicationManager.getApplication().getService(ModelViewerSettings::class.java)
    private val useAxisRotationFormat = detectAxisRotationFormat()

    val TEXT_HIGHLIGHT_TEXT: Color =
        JBColor.namedColor(
            "SplitPane.highlight",
            JBColor(0x3C3F41, 0x3C3F41)
        )

    private var browser: JBCefBrowser? = null
    private var pendingJson: String? = null
    private var pageReady = false
    private var pendingViewMode: String? = null
    private var pendingOrthographic: Boolean? = null
    private var pendingGridEnabled: Boolean? = null
    private var pendingTransformMode: String? = null
    private var pendingTransformSpace: String? = null
    private var pendingTransformAxis: String? = null
    private var pendingTransformSnap: Double? = null
    private var viewMode: String = "textured"
    private var orthographic: Boolean = false
    private var gridEnabled: Boolean = false
    private var transformMode: String = "translate"
    private var transformSpace: String = "local"
    private var transformAxis: String = "y"
    private var transformSnap: Double = 1.0
    private var activeModelFile: com.intellij.openapi.vfs.VirtualFile? = null
    private var activeModelEditor: Editor? = null
    private var hoverQuery: JBCefJSQuery? = null
    private var transformQuery: JBCefJSQuery? = null
    private var selectionQuery: JBCefJSQuery? = null
    private var hoverHighlighter: RangeHighlighter? = null
    private var hoverEditor: Editor? = null
    private var lastHoverIndex: Int? = null
    private var selectedHighlighter: RangeHighlighter? = null
    private var selectedEditor: Editor? = null
    private var selectedIndex: Int? = null
    private val selectionListeners = CopyOnWriteArrayList<(Int?) -> Unit>()
    private val scrollAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    @Volatile
    private var suppressAutoPreview = false

    init {
        val stored = settings.state
        viewMode = normalizeViewMode(stored.viewMode)
        orthographic = stored.orthographic
        gridEnabled = stored.gridEnabled
    }

    fun setBrowser(b: JBCefBrowser) {
        println("Browser registered")
        browser = b
        pageReady = false
        hoverQuery?.dispose()
        transformQuery?.dispose()
        selectionQuery?.dispose()
        @Suppress("DEPRECATION")
        hoverQuery = JBCefJSQuery.create(b).apply {
            addHandler { query ->
                println("Hover query received: $query")
                logger.warn("Hover query received: $query")
                handleHoverRequest(query)
                null
            }
        }
        @Suppress("DEPRECATION")
        transformQuery = JBCefJSQuery.create(b).apply {
            addHandler { query ->
                handleTransformUpdate(query)
                null
            }
        }
        @Suppress("DEPRECATION")
        selectionQuery = JBCefJSQuery.create(b).apply {
            addHandler { query ->
                handleSelectionUpdate(query)
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
                        pendingViewMode = viewMode
                        pendingOrthographic = orthographic
                        pendingGridEnabled = gridEnabled
                        pendingTransformMode = transformMode
                        pendingTransformSpace = transformSpace
                        pendingTransformAxis = transformAxis
                        pendingTransformSnap = transformSnap
                        pendingJson?.let {
                            loadModel(it)
                            pendingJson = null
                        }
                        pendingViewMode?.let { mode ->
                            setViewMode(mode)
                            pendingViewMode = null
                        }
                        pendingOrthographic?.let { enabled ->
                            setOrthographic(enabled)
                            pendingOrthographic = null
                        }
                        pendingGridEnabled?.let { enabled ->
                            setGridEnabled(enabled)
                            pendingGridEnabled = null
                        }
                        pendingTransformMode?.let { mode ->
                            setTransformMode(mode)
                            pendingTransformMode = null
                        }
                        pendingTransformSpace?.let { space ->
                            setTransformSpace(space)
                            pendingTransformSpace = null
                        }
                        pendingTransformAxis?.let { axis ->
                            setTransformAxis(axis)
                            pendingTransformAxis = null
                        }
                        pendingTransformSnap?.let { snap ->
                            setTransformSnap(snap)
                            pendingTransformSnap = null
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

    fun getTransformQueryInjection(functionName: String): String {
        if (transformQuery == null) {
            println("Transform query not ready; injection skipped")
            logger.warn("Transform query not ready; injection skipped")
        }
        return transformQuery?.inject(functionName) ?: ""
    }

    fun getSelectionQueryInjection(functionName: String): String {
        if (selectionQuery == null) {
            println("Selection query not ready; injection skipped")
            logger.warn("Selection query not ready; injection skipped")
        }
        return selectionQuery?.inject(functionName) ?: ""
    }

    fun setActiveModelFile(file: com.intellij.openapi.vfs.VirtualFile?) {
        activeModelFile = file
    }

    fun setActiveModelEditor(editor: Editor?) {
        activeModelEditor = editor
    }

    fun getActiveModelFile(): com.intellij.openapi.vfs.VirtualFile? {
        return activeModelFile
    }

    fun getActiveModelEditor(): Editor? {
        return activeModelEditor
    }

    fun getActiveModelText(): String? {
        val editor = activeModelEditor
        if (editor != null && !editor.isDisposed) {
            return editor.document.text
        }
        val file = activeModelFile ?: return null
        return runCatching { String(file.contentsToByteArray()) }.getOrNull()
    }

    fun getSelectedIndex(): Int? {
        return selectedIndex
    }

    fun addSelectionListener(parentDisposable: Disposable, listener: (Int?) -> Unit) {
        selectionListeners.add(listener)
        Disposer.register(parentDisposable) { selectionListeners.remove(listener) }
    }

    fun loadModel(json: String, resetCamera: Boolean = true) {
        println("loadModel called")

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

    fun getViewMode(): String {
        return viewMode
    }

    fun isOrthographic(): Boolean {
        return orthographic
    }

    fun isGridEnabled(): Boolean {
        return gridEnabled
    }

    fun isSuppressingPreview(): Boolean {
        return suppressAutoPreview
    }

    fun useAxisRotationFormat(): Boolean {
        return useAxisRotationFormat
    }

    fun setViewMode(mode: String) {
        val normalized = normalizeViewMode(mode)
        viewMode = normalized
        settings.setViewMode(normalized)
        if (browser == null || !pageReady) {
            pendingViewMode = normalized
            return
        }
        runViewerScript("window.setViewMode && window.setViewMode('$normalized');")
    }

    fun setOrthographic(enabled: Boolean) {
        orthographic = enabled
        settings.setOrthographic(enabled)
        if (browser == null || !pageReady) {
            pendingOrthographic = enabled
            return
        }
        runViewerScript("window.setOrthographic && window.setOrthographic(${enabled});")
    }

    fun setGridEnabled(enabled: Boolean) {
        gridEnabled = enabled
        settings.setGridEnabled(enabled)
        if (browser == null || !pageReady) {
            pendingGridEnabled = enabled
            return
        }
        runViewerScript("window.setGridEnabled && window.setGridEnabled(${enabled});")
    }

    fun setTransformMode(mode: String) {
        val normalized = normalizeTransformMode(mode)
        transformMode = normalized
        if (browser == null || !pageReady) {
            pendingTransformMode = normalized
            return
        }
        runViewerScript("window.setTransformMode && window.setTransformMode('$normalized');")
    }

    fun setTransformSpace(space: String) {
        val normalized = normalizeTransformSpace(space)
        transformSpace = normalized
        if (browser == null || !pageReady) {
            pendingTransformSpace = normalized
            return
        }
        runViewerScript("window.setTransformSpace && window.setTransformSpace('$normalized');")
    }

    fun setTransformAxis(axis: String) {
        val normalized = normalizeTransformAxis(axis)
        transformAxis = normalized
        if (browser == null || !pageReady) {
            pendingTransformAxis = normalized
            return
        }
        runViewerScript("window.setTransformAxis && window.setTransformAxis('$normalized');")
    }

    fun setTransformSnap(snap: Double) {
        val normalized = if (snap.isFinite() && snap > 0) snap else 1.0
        transformSnap = normalized
        if (browser == null || !pageReady) {
            pendingTransformSnap = normalized
            return
        }
        runViewerScript("window.setTransformSnap && window.setTransformSnap(${normalized});")
    }

    fun resetCamera() {
        if (browser == null || !pageReady) {
            return
        }
        runViewerScript("window.resetCamera && window.resetCamera();")
    }

    fun setSelectedElement(index: Int?) {
        if (browser == null || !pageReady) {
            return
        }
        val value = index ?: -1
        runViewerScript("window.setSelectedElement && window.setSelectedElement(${value});")
    }

    fun setElementHidden(index: Int, hidden: Boolean) {
        if (browser == null || !pageReady) {
            return
        }
        runViewerScript("window.setElementHidden && window.setElementHidden(${index}, ${hidden});")
    }

    private fun runViewerScript(jsCode: String) {
        browser?.cefBrowser?.executeJavaScript(
            jsCode,
            browser?.cefBrowser?.url ?: "http://localhost/",
            0
        )
    }

    private fun normalizeViewMode(mode: String): String {
        return when {
            mode.equals("solid", true) -> "solid"
            mode.equals("wireframe", true) -> "wireframe"
            else -> "textured"
        }
    }

    private fun normalizeTransformMode(mode: String): String {
        return when {
            mode.equals("rotate", true) -> "rotate"
            mode.equals("scale", true) -> "scale"
            else -> "translate"
        }
    }

    private fun normalizeTransformSpace(space: String): String {
        return if (space.equals("world", true)) "world" else "local"
    }

    private fun normalizeTransformAxis(axis: String): String {
        return when {
            axis.equals("x", true) -> "x"
            axis.equals("y", true) -> "y"
            axis.equals("z", true) -> "z"
            else -> "y"
        }
    }

    fun clearHoverHighlight() {
        hoverHighlighter?.dispose()
        hoverHighlighter = null
        hoverEditor = null
        lastHoverIndex = null
        applySelectedHighlight()
    }

    private fun clearSelectedHighlight() {
        selectedHighlighter?.dispose()
        selectedHighlighter = null
        selectedEditor = null
    }

    private fun applySelectedHighlight() {
        val index = selectedIndex ?: run {
            clearSelectedHighlight()
            return
        }
        if (hoverHighlighter != null) {
            clearSelectedHighlight()
            return
        }
        val editor = resolveHoverEditor()
        if (editor == null || editor.isDisposed) {
            clearSelectedHighlight()
            return
        }
        val ranges = findElementRanges(editor.document.text)
        if (index < 0 || index >= ranges.size) {
            clearSelectedHighlight()
            return
        }
        val range = ranges[index]
        if (selectedEditor != editor) {
            clearSelectedHighlight()
        }
        if (selectedHighlighter == null || selectedEditor != editor) {
            val attrs = TextAttributes(
                null,
                TEXT_HIGHLIGHT_TEXT,
                null,
                EffectType.BOXED,
                Font.PLAIN
            )
            selectedHighlighter = editor.markupModel.addRangeHighlighter(
                range.first,
                range.last + 1,
                HighlighterLayer.SELECTION - 2,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
            )
            selectedEditor = editor
        }
    }

    private fun handleTransformUpdate(payload: String) {
        val root = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (e: Exception) {
            logger.warn("Transform update parse failed: $payload", e)
            return
        }
        val index = root.get("index")?.asInt ?: return
        val fromValues = readVector(root, "from") ?: return
        val toValues = readVector(root, "to") ?: return
        val rotationObj = root.get("rotation")?.takeIf { it.isJsonObject }?.asJsonObject
        val rotation = rotationObj?.let { obj ->
            val origin = readVector(obj, "origin") ?: return@let null
            val x = obj.get("x")?.asDouble
            val y = obj.get("y")?.asDouble
            val z = obj.get("z")?.asDouble
            val axis = obj.get("axis")?.asString?.lowercase()
            val angle = obj.get("angle")?.asDouble
            when {
                x != null && y != null && z != null ->
                    RotationUpdate(origin, x = x, y = y, z = z)
                axis != null && angle != null ->
                    RotationUpdate(origin, axis = axis, angle = angle)
                else -> null
            }
        }
        val editor = resolveActiveEditorForUpdate() ?: return
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) {
                return@invokeLater
            }
            suppressAutoPreview = true
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = editor.document
                    val text = document.text
                    val ranges = findElementRanges(text)
                    if (index < 0 || index >= ranges.size) {
                        return@runWriteCommandAction
                    }
                    val range = ranges[index]
                    val elementText = text.substring(range.first, range.last + 1)
                    var updatedElement = replaceArray(elementText, "from", fromValues)
                    updatedElement = replaceArray(updatedElement, "to", toValues)
                    updatedElement = updateRotationBlock(updatedElement, rotation)
                    if (updatedElement == elementText) {
                        return@runWriteCommandAction
                    }
                    document.replaceString(range.first, range.last + 1, updatedElement)
                }
            } finally {
                suppressAutoPreview = false
            }
        }
    }

    private data class RotationUpdate(
        val origin: DoubleArray,
        val x: Double? = null,
        val y: Double? = null,
        val z: Double? = null,
        val axis: String? = null,
        val angle: Double? = null
    )

    private fun replaceArray(text: String, key: String, values: DoubleArray): String {
        val pattern = Regex("(?s)(\"$key\"\\s*:\\s*)(\\[[\\s\\S]*?\\])")
        return pattern.replace(text) { match ->
            val prefix = match.groupValues[1]
            val arrayText = match.groupValues[2]
            var index = 0
            val numberPattern = Regex("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")
            val updatedArray = numberPattern.replace(arrayText) { numberMatch ->
                if (index < values.size) {
                    val formatted = formatNumber(values[index])
                    index += 1
                    formatted
                } else {
                    numberMatch.value
                }
            }
            "$prefix$updatedArray"
        }
    }

    private fun updateRotationBlock(text: String, rotation: RotationUpdate?): String {
        if (rotation == null) {
            return text
        }
        val rotationPattern = Regex("(?s)\"rotation\"\\s*:\\s*\\{[\\s\\S]*?\\}")
        val match = rotationPattern.find(text)
        val originNumbers = rotation.origin.joinToString(", ") { formatNumber(it) }
        val rotationInline = if (useAxisRotationFormat) {
            val axis = rotation.axis ?: resolveAxisFromComponents(rotation)
            val angle = rotation.angle ?: resolveAngleFromComponents(rotation, axis)
            "\"rotation\": {\"angle\": ${formatNumber(angle)}, \"axis\": \"$axis\", \"origin\": [$originNumbers]}"
        } else {
            val axis = rotation.axis ?: resolveAxisFromComponents(rotation)
            val angle = rotation.angle ?: resolveAngleFromComponents(rotation, axis)
            val x = rotation.x ?: if (axis == "x") angle else 0.0
            val y = rotation.y ?: if (axis == "y") angle else 0.0
            val z = rotation.z ?: if (axis == "z") angle else 0.0
            "\"rotation\": {\"x\": ${formatNumber(x)}, \"y\": ${formatNumber(y)}, \"z\": ${formatNumber(z)}, \"origin\": [$originNumbers]}"
        }
        if (match == null) {
            val facesMatch = Regex("\\n(\\s*)\"faces\"").find(text) ?: return text
            val indent = facesMatch.groupValues[1]
            return text.replaceRange(
                facesMatch.range.first,
                facesMatch.range.first,
                "\n$indent$rotationInline,"
            )
        }
        return text.replaceRange(match.range, rotationInline)
    }

    private fun resolveAxisFromComponents(rotation: RotationUpdate): String {
        val x = kotlin.math.abs(rotation.x ?: 0.0)
        val y = kotlin.math.abs(rotation.y ?: 0.0)
        val z = kotlin.math.abs(rotation.z ?: 0.0)
        return when {
            x >= y && x >= z -> "x"
            y >= x && y >= z -> "y"
            else -> "z"
        }
    }

    private fun resolveAngleFromComponents(rotation: RotationUpdate, axis: String): Double {
        return when (axis) {
            "x" -> rotation.x ?: 0.0
            "y" -> rotation.y ?: 0.0
            "z" -> rotation.z ?: 0.0
            else -> rotation.y ?: 0.0
        }
    }

    private fun detectAxisRotationFormat(): Boolean {
        val basePath = project.basePath ?: return false
        val file = File(basePath, "gradle.properties")
        if (!file.exists()) {
            return false
        }
        val versionLine = file.readLines()
            .firstOrNull { it.trim().startsWith("minecraft_version") } ?: return false
        val match = Regex("minecraft_version\\s*=\\s*([^#\\s]+)").find(versionLine) ?: return false
        val version = parseVersion(match.groupValues[1]) ?: return false
        return version < McVersion(1, 21, 11)
    }

    private data class McVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<McVersion> {
        override fun compareTo(other: McVersion): Int {
            if (major != other.major) return major.compareTo(other.major)
            if (minor != other.minor) return minor.compareTo(other.minor)
            return patch.compareTo(other.patch)
        }
    }

    private fun parseVersion(raw: String): McVersion? {
        val cleaned = raw.trim().replace(Regex("[^0-9.]"), "")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return McVersion(major, minor, patch)
    }

    private fun formatNumber(value: Double): String {
        return BigDecimal.valueOf(value)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun handleSelectionUpdate(request: String) {
        val trimmed = request.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") {
            selectedIndex = null
            clearSelectedHighlight()
            notifySelectionListeners()
            return
        }
        val index = trimmed.toIntOrNull()
        if (index == null || index < 0) {
            selectedIndex = null
            clearSelectedHighlight()
            notifySelectionListeners()
            return
        }
        selectedIndex = index
        applySelectedHighlight()
        notifySelectionListeners()
    }

    private fun notifySelectionListeners() {
        val value = selectedIndex
        selectionListeners.forEach { listener ->
            listener(value)
        }
    }

    private fun readVector(root: com.google.gson.JsonObject, name: String): DoubleArray? {
        val array = root.getAsJsonArray(name) ?: return null
        if (array.size() < 3) return null
        return doubleArrayOf(
            array[0].asDouble,
            array[1].asDouble,
            array[2].asDouble
        )
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
                    clearSelectedHighlight()
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

    private fun resolveActiveEditorForUpdate(): Editor? {
        return resolveHoverEditor()
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
