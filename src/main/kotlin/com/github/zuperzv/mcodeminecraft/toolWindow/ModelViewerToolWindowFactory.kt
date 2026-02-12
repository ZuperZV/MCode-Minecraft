package com.github.zuperzv.mcodeminecraft.toolWindow

import com.github.zuperzv.mcodeminecraft.preview.ModelAutoPreviewService
import com.github.zuperzv.mcodeminecraft.services.AssetServer
import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.github.zuperzv.mcodeminecraft.util.AssetRootResolver
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import org.intellij.lang.annotations.Language
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ModelViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    val SELECTED_BACKGROUND_COLOR: Color =
        JBColor.namedColor(
            "ActionButton.hoverBorderColor",
            JBColor(0xc5dffc, 0x113a5c)
        )

    val BACKGROUND_COLOR: Color =
        JBColor.namedColor(
            "Panel.background",
            JBColor(0x2B2B2B, 0x2B2B2B)
        )

    val BORDER_COLOR: Color =
        JBColor.namedColor(
            "Borders.color",
            JBColor(0x26282b, 0x26282b)
        )

    private val TEXTURE_PREVIEW_SIZE = 32
    private val TEXTURE_PREFETCH_PADDING = 8

    private val logger = Logger.getInstance(ModelViewerToolWindowFactory::class.java)
    private val texturePreviewExecutor = AppExecutorUtil.getAppExecutorService()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        dumpUiManagerKeysOnce()

        project.getService(ModelAutoPreviewService::class.java)

        val viewerService = project.getService(ModelViewerService::class.java)

        val viewerPanel: JPanel = JPanel(BorderLayout())
        viewerPanel.isOpaque = false

        val viewerContainer = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                    )
                    val arc = JBUI.scale(52)
                    val inset = JBUI.scale(20)
                    val rectWidth = width - inset * 2
                    val rectHeight = height - inset * 2
                    val clipShape = RoundRectangle2D.Float(
                        inset.toFloat(),
                        inset.toFloat(),
                        rectWidth.toFloat(),
                        rectHeight.toFloat(),
                        arc.toFloat(),
                        arc.toFloat()
                    )
                    g2.color = SELECTED_BACKGROUND_COLOR
                    g2.fill(clipShape)
                    g2.clip = clipShape
                    super.paintComponent(g)
                    g2.clip = null
                    g2.color = JBColor.border()
                    g2.draw(clipShape)
                } finally {
                    g2.dispose()
                }
            }
        }
        viewerContainer.isOpaque = false
        viewerContainer.border = JBUI.Borders.empty(20 + 1, 20 + 1, 20 + 14, 20 + 1)

        val transformToolbar = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(4)))
        transformToolbar.border = JBUI.Borders.empty(6, 6, 0, 6)
        transformToolbar.isOpaque = false
        val moveButton = JToggleButton(AllIcons.General.Export).apply {
            toolTipText = "Move"
            isSelected = true
            margin = JBUI.insets(2, 3)
            preferredSize = JBUI.size(28, 28)
        }
        val rotateButton = JToggleButton(AllIcons.General.Refresh).apply {
            toolTipText = "Rotate"
            margin = JBUI.insets(2, 3)
            preferredSize = JBUI.size(28, 28)
        }
        val scaleButton = JToggleButton(AllIcons.Diff.ArrowLeftRight).apply {
            toolTipText = "Scale"
            margin = JBUI.insets(2, 3)
            preferredSize = JBUI.size(28, 28)
        }
        val modeGroup = ButtonGroup().apply {
            add(moveButton)
            add(rotateButton)
            add(scaleButton)
        }
        val spaceBox = ComboBox(arrayOf("Local", "World")).apply {
            toolTipText = "Local/Global"
            preferredSize = JBUI.size(84, 28)
        }
        val spacer = JBLabel("|").apply {
            preferredSize = JBUI.size(7, 28)
            horizontalAlignment = JBLabel.CENTER
        }
        val snapIcon = JBLabel(AllIcons.Actions.TraceOver).apply {
            toolTipText = "Snap"
            preferredSize = JBUI.size(28, 28)
            horizontalAlignment = JBLabel.CENTER
        }
        val snapField = JBTextField("1").apply {
            columns = 4
            toolTipText = "Snap (Shift = 0.25)"
            preferredSize = JBUI.size(42, 28)
        }
        transformToolbar.add(moveButton)
        transformToolbar.add(rotateButton)
        transformToolbar.add(scaleButton)
        transformToolbar.add(spaceBox)
        transformToolbar.add(spacer)
        transformToolbar.add(snapIcon)
        transformToolbar.add(snapField)
        transformToolbar.alignmentY -= 40
        viewerContainer.add(transformToolbar, BorderLayout.NORTH)
        viewerContainer.add(viewerPanel, BorderLayout.CENTER)

        fun updateSnapFromField() {
            val value = snapField.text.trim().toDoubleOrNull()
            if (value != null && value > 0) {
                viewerService.setTransformSnap(value)
            }
        }

        moveButton.addActionListener {
            if (moveButton.isSelected) {
                viewerService.setTransformMode("translate")
            }
        }
        rotateButton.addActionListener {
            if (rotateButton.isSelected) {
                viewerService.setTransformMode("rotate")
            }
        }
        scaleButton.addActionListener {
            if (scaleButton.isSelected) {
                viewerService.setTransformMode("scale")
            }
        }
        spaceBox.addActionListener {
            val selected = spaceBox.selectedItem as? String ?: "Local"
            val space = if (selected.equals("World", true)) "world" else "local"
            viewerService.setTransformSpace(space)
        }
        snapField.addActionListener { updateSnapFromField() }
        snapField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                updateSnapFromField()
            }
        })

        val browser = if (JBCefApp.isSupported()) {
            try {
                JBCefBrowser().also {
                    viewerService.setBrowser(it)
                    viewerPanel.add(it.component, BorderLayout.CENTER)
                }
            } catch (e: Exception) {
                logger.warn("Failed to create JCEF browser for tool window", e)
                viewerPanel.add(JBLabel("Model viewer failed to initialize. Check IDE log."), BorderLayout.CENTER)
                null
            }
        } else {
            viewerPanel.add(JBLabel("JCEF is not supported in this IDE/runtime."), BorderLayout.CENTER)
            null
        }

        val controlsPanel = createControlsPanel(project, viewerService)

        val viewModeGroupAction = DefaultActionGroup("View Mode", true).apply {
            templatePresentation.icon = AllIcons.Actions.ChangeView
        }
        viewModeGroupAction.add(object : ToggleAction("Solid") {
            override fun isSelected(e: AnActionEvent): Boolean {
                return viewerService.getViewMode() == "solid"
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) {
                    viewerService.setViewMode("solid")
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })
        viewModeGroupAction.add(object : ToggleAction("Textured") {
            override fun isSelected(e: AnActionEvent): Boolean {
                return viewerService.getViewMode() == "textured"
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) {
                    viewerService.setViewMode("textured")
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })
        viewModeGroupAction.add(object : ToggleAction("Wireframe") {
            override fun isSelected(e: AnActionEvent): Boolean {
                return viewerService.getViewMode() == "wireframe"
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                if (state) {
                    viewerService.setViewMode("wireframe")
                }
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        })

        val orthographicAction = object : ToggleAction("Orthographic") {
            init {
                templatePresentation.icon = AllIcons.General.InspectionsEye
            }


            override fun isSelected(e: AnActionEvent): Boolean {
                return viewerService.isOrthographic()
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                viewerService.setOrthographic(state)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        val gridAction = object : ToggleAction("Grid") {
            init {
                templatePresentation.icon = AllIcons.Graph.Grid
            }

            override fun isSelected(e: AnActionEvent): Boolean {
                return viewerService.isGridEnabled()
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                viewerService.setGridEnabled(state)
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        val resetCameraAction = object : DumbAwareAction(
            "Reset Camera",
            "Reset the camera to the default position",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                viewerService.resetCamera()
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        toolWindow.setTitleActions(listOf(viewModeGroupAction, orthographicAction, gridAction, resetCameraAction))

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, viewerContainer, controlsPanel)

        splitPane.resizeWeight = 0.7
        splitPane.isOneTouchExpandable = false
        splitPane.isOpaque = false
        splitPane.dividerSize = JBUI.scale(8)
        splitPane.border = null
        splitPane.ui = object : BasicSplitPaneUI() {
            override fun createDefaultDivider(): BasicSplitPaneDivider {
                return object : BasicSplitPaneDivider(this) {
                    override fun paint(g: Graphics) {
                        g.color = BORDER_COLOR
                        g.fillRect(0, 0, width, height + 10)
                    }
                }.apply {
                }
            }
        }

        val content = ContentFactory.getInstance()
            .createContent(splitPane, "", false)
        content.component.border = JBUI.Borders.empty()
        content.component.isOpaque = true
        content.component.background = JBColor.background()
        toolWindow.contentManager.addContent(content)


        val projectRoot = project.basePath!!.replace("\\", "/")

        val assetsFolder = File(project.basePath, "src/main/resources/assets")
        val assetServerPort = 6192
        try {
            AssetServer.start(assetsFolder, assetServerPort)
        } catch (e: Exception) {
            logger.warn("Failed to start asset server on port $assetServerPort", e)
        }

        browser?.let {
            val hoverInjection = viewerService.getHoverQueryInjection("hoverElement")
            val transformInjection = viewerService.getTransformQueryInjection("transformUpdate")
            val selectionInjection = viewerService.getSelectionQueryInjection("selectElement")
            println("Hover injection length: " + hoverInjection.length)
            println("Hover injection: " + hoverInjection)
            it.loadHTML(
                viewerHtml(
                    projectRoot,
                    assetServerPort,
                    hoverInjection,
                    transformInjection,
                    selectionInjection,
                    viewerService.useAxisRotationFormat()
                ),
                "http://localhost/"
            )
            it.openDevtools()
        }

        println("ModelViewerToolWindow CREATED")
    }

    @Language("HTML")
    private fun viewerHtml(
        projectRoot: String,
        assetPort: Int,
        hoverInjection: String,
        transformInjection: String,
        selectionInjection: String,
        useAxisRotationFormat: Boolean
    ) = """
        <html>
        <body style="margin:0; overflow:hidden;">
        <canvas id="c"></canvas>
        
        <script src="https://cdn.jsdelivr.net/npm/three@0.160/build/three.min.js"></script>
        <script src="http://localhost:$assetPort/assets/lib/TransformControls.js"></script>
        
        <script>
        console.log("Viewer JS running")
        const ROTATION_FORMAT = "${if (useAxisRotationFormat) "axis" else "xyz"}"
        window.addEventListener("error", (e)=>console.error("Viewer JS error:", e.message, e.error))
        const sendHover = (msg)=>{
            const hoverElement = msg
            ;$hoverInjection;
        }
        const sendTransformUpdate = (payload)=>{
            const transformUpdate = payload
            ;$transformInjection;
        }
        const sendSelectionUpdate = (msg)=>{
            const selectElement = msg
            ;$selectionInjection;
        }
        console.log("sendHover type:", typeof sendHover)
        let hoverHandshakeSent = false
        const PROJECT_ROOT = "$projectRoot"
        const ASSET_PORT = $assetPort
     
        const canvas = document.getElementById("c")
        const renderer = new THREE.WebGLRenderer({canvas, antialias:false, alpha:true})
        renderer.setSize(Math.floor(window.innerWidth), Math.floor(window.innerHeight))
        renderer.setPixelRatio(1) // Pixel-perfect

        const scene = new THREE.Scene()
        const DEFAULT_ORBIT_TARGET = new THREE.Vector3(0,0,0)
        const DEFAULT_CAMERA_POS = new THREE.Vector3(40,40,40)
        const DEFAULT_ORBIT_RADIUS = DEFAULT_CAMERA_POS.distanceTo(DEFAULT_ORBIT_TARGET)
        const DEFAULT_ORBIT_THETA = Math.atan2(
            DEFAULT_CAMERA_POS.z - DEFAULT_ORBIT_TARGET.z,
            DEFAULT_CAMERA_POS.x - DEFAULT_ORBIT_TARGET.x
        )
        const DEFAULT_ORBIT_PHI = Math.acos(
            (DEFAULT_CAMERA_POS.y - DEFAULT_ORBIT_TARGET.y) / DEFAULT_ORBIT_RADIUS
        )
        const DEFAULT_ORTHO_ZOOM = 1

        const aspect = window.innerWidth / window.innerHeight
        const perspectiveCamera = new THREE.PerspectiveCamera(70, aspect, 0.1, 1000)
        const ORTHO_SIZE = 60
        const orthoCamera = new THREE.OrthographicCamera(
            -ORTHO_SIZE * aspect,
            ORTHO_SIZE * aspect,
            ORTHO_SIZE,
            -ORTHO_SIZE,
            0.1,
            1000
        )
        perspectiveCamera.position.copy(DEFAULT_CAMERA_POS)
        orthoCamera.position.copy(DEFAULT_CAMERA_POS)
        perspectiveCamera.lookAt(DEFAULT_ORBIT_TARGET)
        orthoCamera.lookAt(DEFAULT_ORBIT_TARGET)

        let camera = perspectiveCamera
        let useOrthographic = false
        let orbitTarget = DEFAULT_ORBIT_TARGET.clone()
        let currentModelCenter = DEFAULT_ORBIT_TARGET.clone()
        let orbitRadius = DEFAULT_ORBIT_RADIUS
        let orbitTheta = DEFAULT_ORBIT_THETA
        let orbitPhi = DEFAULT_ORBIT_PHI

        const light = new THREE.DirectionalLight(0xffffff, 1)
        const ambientLight = new THREE.AmbientLight(0x212121)
        light.position.set(50,50,50)
        scene.add(light)
        scene.add(ambientLight)
        let gridMesh = null

        function clearScene(){
            const preserved = new Set([light, ambientLight])
            if(gridMesh){
                preserved.add(gridMesh)
            }
            if(transformControls){
                preserved.add(transformControls)
            }
            for(let i = scene.children.length - 1; i >= 0; i--){
                const child = scene.children[i]
                if(!preserved.has(child)){
                    scene.remove(child)
                }
            }
            pickableMeshes.length = 0
            elementRoots.length = 0
            if(hoverState.object){
                setMeshHighlight(hoverState.object, false)
                hoverState.object = null
            }
            clearSelection()
        }

        function updateOrthoFrustum(){
            const aspect = window.innerWidth / window.innerHeight
            orthoCamera.left = -ORTHO_SIZE * aspect
            orthoCamera.right = ORTHO_SIZE * aspect
            orthoCamera.top = ORTHO_SIZE
            orthoCamera.bottom = -ORTHO_SIZE
            orthoCamera.updateProjectionMatrix()
        }

        function applyOrthographic(enabled){
            const next = !!enabled
            if(next === useOrthographic){
                return
            }
            const fromCamera = useOrthographic ? orthoCamera : perspectiveCamera
            const toCamera = next ? orthoCamera : perspectiveCamera
            toCamera.position.copy(fromCamera.position)
            toCamera.up.copy(fromCamera.up)
            toCamera.lookAt(orbitTarget)
            useOrthographic = next
            camera = toCamera
            if(transformControls){
                transformControls.camera = camera
                if(typeof transformControls.update === "function"){
                    transformControls.update()
                }else if(typeof transformControls.updateMatrixWorld === "function"){
                    transformControls.updateMatrixWorld(true)
                }
            }
            if(useOrthographic){
                updateOrthoFrustum()
                orthoCamera.updateProjectionMatrix()
            }else{
                perspectiveCamera.updateProjectionMatrix()
            }
            updateCameraFromOrbit()
        }

        function resetCameraState(){
            orbitTarget = DEFAULT_ORBIT_TARGET.clone()
            orbitRadius = DEFAULT_ORBIT_RADIUS
            orbitTheta = DEFAULT_ORBIT_THETA
            orbitPhi = DEFAULT_ORBIT_PHI
            orthoCamera.zoom = DEFAULT_ORTHO_ZOOM
            orthoCamera.updateProjectionMatrix()
            updateCameraFromOrbit()
        }

        updateOrthoFrustum()

        function updateCameraFromOrbit(){
            const sinPhi = Math.sin(orbitPhi)
            const x = orbitTarget.x + orbitRadius * sinPhi * Math.cos(orbitTheta)
            const y = orbitTarget.y + orbitRadius * Math.cos(orbitPhi)
            const z = orbitTarget.z + orbitRadius * sinPhi * Math.sin(orbitTheta)
            camera.position.set(x,y,z)
            camera.lookAt(orbitTarget)
        }

        function rotateUVScalar(u, v, rotation) {
            rotation = ((rotation||0)%360 + 360)%360;
            switch(rotation) {
                case 90:
                    return [v, 1 - u];
                case 180:
                    return [1 - u, 1 - v];
                case 270:
                    return [1 - v, u];
                default: // 0
                    return [u, v];
            }
        }

        const loader = new THREE.TextureLoader()
        const TICK_MS = 50
        const DEBUG_UV = true
        const modelCache = {}
        let textures = {}
        let animatedTextures = new Map()
        const raycaster = new THREE.Raycaster()
        const pointer = new THREE.Vector2()
        let hasPointer = false
        const pickableMeshes = []
        const elementRoots = []
        const hiddenElements = new Set()
        let originalMaterialColors = new WeakMap()
        const viewModeDefaults = new WeakMap()
        const hoverState = { object: null }
        const selectedState = { objects: new Set(), outlines: new Map(), active: null }
        let suppressSelectionNotify = false
        let transformControls = null
        let transformMode = "translate"
        let transformSpace = "local"
        let transformAxis = "y"
        let snapStep = 1
        let isShiftDown = false
        let isTransforming = false
        const useAxisRotation = ROTATION_FORMAT === "axis"
        let lastRotationAxis = "y"
        let wasTransforming = false
        let multiTransformState = null
        let sourceModel = null
        let sourceElements = null
        let hoverDebugLogged = false
        let viewMode = "textured"
        let gridEnabled = false

        function initTransformControls(){
            if(transformControls){
                return
            }
            if(!THREE.TransformControls){
                console.warn("TransformControls not available; gizmo disabled")
                return
            }
            transformControls = new THREE.TransformControls(camera, renderer.domElement)
            transformControls.setMode(transformMode)
            transformControls.setSpace(transformSpace)
            updateTransformSnaps()
            updateTransformAxisVisibility()
            transformControls.addEventListener("mouseDown", ()=>{
                if(!useAxisRotation || transformMode !== "rotate"){
                    return
                }
                const axis = String(transformControls.axis || "").toLowerCase()
                if(axis === "x" || axis === "y" || axis === "z"){
                    lastRotationAxis = axis
                }
            })
            transformControls.addEventListener("dragging-changed", (event)=>{
                isTransforming = event.value
                if(event.value){
                    wasTransforming = true
                    beginMultiTransform()
                }else if(wasTransforming){
                    commitTransformUpdate()
                    multiTransformState = null
                    wasTransforming = false
                }
            })
            transformControls.addEventListener("change", ()=>{
                if(isTransforming){
                    applyMultiTransformDelta()
                }
            })
            scene.add(transformControls)
            updateTransformAttachment()
        }

        if(THREE.TransformControls){
            initTransformControls()
        }else{
            console.warn("TransformControls not available; gizmo disabled")
        }

        function resolveTexture(texString){
            texString = texString.replace("#","")
            if(texString.includes(":")){
                const [namespace, path] = texString.split(":")
                return "http://localhost:$assetPort/assets/" + namespace + "/textures/" + path + ".png"
            }
            return "http://localhost:$assetPort/assets/minecraft/textures/" + texString + ".png"
        }

        function resolveModelUrl(parentRef){
            let namespace = "minecraft"
            let path = parentRef
            if(parentRef.includes(":")){
                const parts = parentRef.split(":")
                namespace = parts[0]
                path = parts[1]
            }
            return "http://localhost:$assetPort/assets/" + namespace + "/models/" + path + ".json"
        }

        async function fetchModel(parentRef){
            const url = resolveModelUrl(parentRef)
            if(modelCache[url]) return modelCache[url]
            modelCache[url] = fetch(url).then(r => {
                if(!r.ok){
                    throw new Error("Failed to load model: " + url)
                }
                return r.json()
            })
            return modelCache[url]
        }

        async function resolveModel(model, depth=0){
            if(!model || !model.parent || depth > 10){
                return model
            }
            try{
                let parent = await fetchModel(model.parent)
                parent = await resolveModel(parent, depth + 1)
                return {
                    ...parent,
                    ...model,
                    textures: { ...(parent.textures || {}), ...(model.textures || {}) },
                    elements: model.elements ?? parent.elements
                }
            }catch(e){
                console.warn("Parent model load failed:", model.parent, e)
                return model
            }
        }

        function resolveTextureRef(ref, texMap, depth=0){
            if(!ref || depth > 10) return null
            if(!ref.startsWith("#")) return ref
            const key = ref.slice(1)
            const next = texMap?.[key]
            return resolveTextureRef(next, texMap, depth + 1)
        }

        async function setupAnimatedTexture(tex, texPath){
            const img = tex.image
            if(!img || img.height <= img.width) return

            let frametime = 1
            let interpolate = false
            let frameWidth = null
            let frameHeight = null
            let framesMeta = null
            try{
                const metaRes = await fetch(texPath + ".mcmeta")
                if(metaRes.ok){
                    const metaJson = await metaRes.json()
                    const animation = metaJson?.animation || {}
                    if(Number.isFinite(animation.frametime) && animation.frametime > 0){
                        frametime = animation.frametime
                    }
                    interpolate = animation.interpolate === true
                    if(Number.isFinite(animation.width) && animation.width > 0){
                        frameWidth = animation.width
                    }
                    if(Number.isFinite(animation.height) && animation.height > 0){
                        frameHeight = animation.height
                    }
                    if(Array.isArray(animation.frames)){
                        framesMeta = animation.frames
                    }
                }
            }catch(e){
                console.warn("Animation meta load failed:", texPath, e)
            }

            const inferredSize = Math.min(img.width, img.height)
            frameWidth = frameWidth || inferredSize
            frameHeight = frameHeight || inferredSize

            const columns = Math.floor(img.width / frameWidth)
            const rows = Math.floor(img.height / frameHeight)
            const totalFrames = columns * rows
            if(totalFrames <= 1) return

            const canvas = document.createElement("canvas")
            canvas.width = frameWidth
            canvas.height = frameHeight
            const ctx = canvas.getContext("2d")
            ctx.imageSmoothingEnabled = interpolate

            tex.image = canvas
            tex.needsUpdate = true
            tex.generateMipmaps = false
            tex.wrapS = THREE.ClampToEdgeWrapping
            tex.wrapT = THREE.ClampToEdgeWrapping
            tex.repeat.set(1, 1)
            tex.offset.set(0, 0)
            tex.magFilter = THREE.NearestFilter
            tex.minFilter = THREE.NearestFilter

            const defaultFrameTimeMs = frametime * TICK_MS
            const frames = []
            if(framesMeta && framesMeta.length){
                framesMeta.forEach((entry)=>{
                    if(Number.isFinite(entry)){
                        frames.push({ index: entry, timeMs: defaultFrameTimeMs })
                        return
                    }
                    if(entry && Number.isFinite(entry.index)){
                        const timeMs = Number.isFinite(entry.time) && entry.time > 0
                            ? entry.time * TICK_MS
                            : defaultFrameTimeMs
                        frames.push({ index: entry.index, timeMs })
                    }
                })
            }
            if(!frames.length){
                for(let i=0;i<totalFrames;i++){
                    frames.push({ index: i, timeMs: defaultFrameTimeMs })
                }
            }
            const filteredFrames = frames
                .filter(f => Number.isFinite(f.index) && f.index >= 0 && f.index < totalFrames)
                .map(f => {
                    const row = Math.floor(f.index / columns)
                    const col = f.index % columns
                    return {
                        index: f.index,
                        timeMs: f.timeMs,
                        srcX: col * frameWidth,
                        srcY: row * frameHeight
                    }
                })
            if(!filteredFrames.length) return
            const totalDurationMs = filteredFrames.reduce((sum, f) => sum + f.timeMs, 0)

            animatedTextures.set(texPath, {
                texture: tex,
                image: img,
                ctx,
                frameWidth,
                frameHeight,
                frames: filteredFrames,
                totalDurationMs,
                interpolate,
                lastFrame: -1,
                lastBlend: -1
            })
        }

        function updateAnimatedTextures(nowMs){
            animatedTextures.forEach(entry=>{
                const elapsed = entry.totalDurationMs > 0 ? (nowMs % entry.totalDurationMs) : 0
                let acc = 0
                let framePos = 0
                for(let i=0;i<entry.frames.length;i++){
                    acc += entry.frames[i].timeMs
                    if(elapsed < acc){
                        framePos = i
                        break
                    }
                }
                const frame = entry.frames[framePos]
                const prevAcc = acc - frame.timeMs
                const blend = entry.interpolate ? ((elapsed - prevAcc) / frame.timeMs) : 0

                if(framePos === entry.lastFrame && blend === entry.lastBlend){
                    return
                }

                const ctx = entry.ctx
                ctx.clearRect(0, 0, entry.frameWidth, entry.frameHeight)
                ctx.globalAlpha = 1
                ctx.drawImage(
                    entry.image,
                    frame.srcX, frame.srcY, entry.frameWidth, entry.frameHeight,
                    0, 0, entry.frameWidth, entry.frameHeight
                )
                if(entry.interpolate && blend > 0){
                    const nextPos = (framePos + 1) % entry.frames.length
                    const nextFrame = entry.frames[nextPos]
                    ctx.globalAlpha = blend
                    ctx.drawImage(
                        entry.image,
                        nextFrame.srcX, nextFrame.srcY, entry.frameWidth, entry.frameHeight,
                        0, 0, entry.frameWidth, entry.frameHeight
                    )
                    ctx.globalAlpha = 1
                }

                entry.texture.needsUpdate = true
                entry.lastFrame = framePos
                entry.lastBlend = blend
            })
        }

        function getTextureForRef(texRef){
            if(!texRef) return null
            const cacheKey = texRef
            if(!textures[cacheKey]){
                const texPath = resolveTexture(texRef)
                textures[cacheKey] = loader.load(
                    texPath,
                    (tex)=>{ console.log("Texture loaded:", texPath); setupAnimatedTexture(tex, texPath) },
                    undefined,
                    e=>console.error("Texture failed:", texPath, e)
                )
                textures[cacheKey].magFilter = THREE.NearestFilter
                textures[cacheKey].minFilter = THREE.NearestFilter
            }
            return textures[cacheKey]
        }

        function applyFaceUV(geometry, faceIndex, uv, texW=16, texH=16, rotation=0, faceName="") {
            if (!uv) return;
        
            let [u1,v1,u2,v2] = uv;
            const uMin = Math.min(u1, u2) / texW;
            const uMax = Math.max(u1, u2) / texW;
            const vMin = Math.min(v1, v2) / texH;
            const vMax = Math.max(v1, v2) / texH;
            let uStart = uMin;
            let uEnd = uMax;
            let vTop = 1 - vMin;
            let vBottom = 1 - vMax;
            if (u1 > u2) {
                const t = uStart; uStart = uEnd; uEnd = t;
            }
            if (v1 > v2) {
                const t = vTop; vTop = vBottom; vBottom = t;
            }
        
            const uvAttr = geometry.attributes.uv;
            const indexed = !!geometry.index;
            const idxBase = indexed ? faceIndex * 4 : faceIndex * 6;
            const count = indexed ? 4 : 6;
            if(DEBUG_UV){
                console.log("[UV] face", faceName, "idx", faceIndex, "uv", uv, "tex", texW, texH, "rot", rotation, "indexed", indexed, "uvCount", uvAttr?.count)
                console.log("[UV] ranges", "u", uStart, uEnd, "v", vBottom, vTop)
            }
            for(let i=0;i<count;i++){
                const oldU = uvAttr.getX(idxBase + i);
                const oldV = uvAttr.getY(idxBase + i);
                const rotated = rotateUVScalar(oldU, oldV, rotation);
                const newU = uStart + rotated[0] * (uEnd - uStart);
                const newV = vBottom + rotated[1] * (vTop - vBottom);
                if(DEBUG_UV){
                    console.log("[UV] v", i, "old", oldU, oldV, "rot", rotated[0], rotated[1], "new", newU, newV)
                }
                uvAttr.setXY(idxBase + i, newU, newV);
            }
            uvAttr.needsUpdate = true;
        }

        function ensureColorAttribute(geometry){
            if(!geometry.attributes.color){
                const colorArray = new Float32Array(geometry.attributes.position.count * 3)
                geometry.setAttribute("color", new THREE.BufferAttribute(colorArray, 3))
            }
        }

        function minecraftAoLevel(side1, side2, corner){
            if(side1 && side2) return 0
            return 3 - (side1?1:0) - (side2?1:0) - (corner?1:0)
        }

        function minecraftAoBrightness(level){
            const levels = [0.25, 0.3, 0.6, 0.75]
            return levels[Math.max(0, Math.min(3, level))]
        }

        function computeFaceAOCorners(el, faceName, occupancy){
            const minX = Math.floor(el.from[0])
            const minY = Math.floor(el.from[1])
            const minZ = Math.floor(el.from[2])
            const maxX = Math.ceil(el.to[0]) - 1
            const maxY = Math.ceil(el.to[1]) - 1
            const maxZ = Math.ceil(el.to[2]) - 1

            function isSolid(x,y,z){
                return occupancy.has(x + "," + y + "," + z)
            }

            if(faceName === "north" || faceName === "south"){
                const outsideZ = faceName === "north" ? minZ - 1 : maxZ + 1
                function cornerBrightness(xIsMax, yIsMax){
                    const x = xIsMax ? maxX : minX
                    const y = yIsMax ? maxY : minY
                    const sideX = x + (xIsMax ? 1 : -1)
                    const sideY = y + (yIsMax ? 1 : -1)
                    const side1 = isSolid(sideX, y, outsideZ)
                    const side2 = isSolid(x, sideY, outsideZ)
                    const corner = isSolid(sideX, sideY, outsideZ)
                    return minecraftAoBrightness(minecraftAoLevel(side1, side2, corner))
                }
                return {
                    "min-min": cornerBrightness(false, false),
                    "min-max": cornerBrightness(false, true),
                    "max-min": cornerBrightness(true, false),
                    "max-max": cornerBrightness(true, true)
                }
            }
            if(faceName === "east" || faceName === "west"){
                const outsideX = faceName === "west" ? minX - 1 : maxX + 1
                function cornerBrightness(zIsMax, yIsMax){
                    const z = zIsMax ? maxZ : minZ
                    const y = yIsMax ? maxY : minY
                    const sideZ = z + (zIsMax ? 1 : -1)
                    const sideY = y + (yIsMax ? 1 : -1)
                    const side1 = isSolid(outsideX, y, sideZ)
                    const side2 = isSolid(outsideX, sideY, z)
                    const corner = isSolid(outsideX, sideY, sideZ)
                    return minecraftAoBrightness(minecraftAoLevel(side1, side2, corner))
                }
                return {
                    "min-min": cornerBrightness(false, false),
                    "min-max": cornerBrightness(false, true),
                    "max-min": cornerBrightness(true, false),
                    "max-max": cornerBrightness(true, true)
                }
            }
            const outsideY = faceName === "down" ? minY - 1 : maxY + 1
            function cornerBrightness(xIsMax, zIsMax){
                const x = xIsMax ? maxX : minX
                const z = zIsMax ? maxZ : minZ
                const sideX = x + (xIsMax ? 1 : -1)
                const sideZ = z + (zIsMax ? 1 : -1)
                const side1 = isSolid(sideX, outsideY, z)
                const side2 = isSolid(x, outsideY, sideZ)
                const corner = isSolid(sideX, outsideY, sideZ)
                return minecraftAoBrightness(minecraftAoLevel(side1, side2, corner))
            }
            return {
                "min-min": cornerBrightness(false, false),
                "min-max": cornerBrightness(false, true),
                "max-min": cornerBrightness(true, false),
                "max-max": cornerBrightness(true, true)
            }
        }

        function applyFaceAOColors(geometry, faceIndex, faceName, shade, el, sx, sy, sz, occupancy){
            ensureColorAttribute(geometry)

            const colors = geometry.attributes.color
            const indexed = !!geometry.index
            const idxBase = indexed ? faceIndex * 4 : faceIndex * 6
            const count = indexed ? 4 : 6

            const centerX = el.from[0] + sx / 2
            const centerY = el.from[1] + sy / 2
            const centerZ = el.from[2] + sz / 2

            const axisA = (faceName === "north" || faceName === "south") ? "x" :
                          (faceName === "east" || faceName === "west") ? "z" : "x"
            const axisB = (faceName === "north" || faceName === "south") ? "y" :
                          (faceName === "east" || faceName === "west") ? "y" : "z"

            const aMid = axisA === "x" ? centerX : axisA === "y" ? centerY : centerZ
            const bMid = axisB === "x" ? centerX : axisB === "y" ? centerY : centerZ

            const aoCorners = computeFaceAOCorners(el, faceName, occupancy)

            for(let i=0;i<count;i++){
                const posIdx = idxBase + i
                const vx = geometry.attributes.position.getX(posIdx) + centerX
                const vy = geometry.attributes.position.getY(posIdx) + centerY
                const vz = geometry.attributes.position.getZ(posIdx) + centerZ

                const aVal = axisA === "x" ? vx : axisA === "y" ? vy : vz
                const bVal = axisB === "x" ? vx : axisB === "y" ? vy : vz
                const aIsMax = aVal > aMid
                const bIsMax = bVal > bMid
                const key = (aIsMax ? "max" : "min") + "-" + (bIsMax ? "max" : "min")
                const ao = aoCorners[key] ?? 1.0
                const value = shade * ao
                colors.setXYZ(posIdx, value, value, value)
            }
            colors.needsUpdate = true
        }

        function minecraftFaceShade(faceName){
            switch(faceName){
                case "up": return 0.3
                case "down": return 0.15
                case "north":
                case "south": return 0.2
                case "east":
                case "west": return 0.17
                default: return 0.3
            }
        }

        const HOVER_COLOR_BOOST = 1.45

        function setMeshHighlight(mesh, enabled){
            const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
            mats.forEach(mat=>{
                if(!mat || !mat.color) return
                let base = originalMaterialColors.get(mat)
                if(enabled){
                    if(!base){
                        base = {
                            color: mat.color.clone(),
                            emissive: mat.emissive ? mat.emissive.clone() : null,
                            emissiveIntensity: Number.isFinite(mat.emissiveIntensity) ? mat.emissiveIntensity : null
                        }
                        originalMaterialColors.set(mat, base)
                    }
                    mat.color.copy(base.color).multiplyScalar(HOVER_COLOR_BOOST)
                    if(mat.emissive){
                        if(base.emissive){
                            mat.emissive.copy(base.emissive).multiplyScalar(HOVER_COLOR_BOOST)
                        }else{
                            mat.emissive.setHex(0xffffff)
                        }
                        mat.emissiveIntensity = Math.max(0.8, base.emissiveIntensity ?? 0.8)
                    }
                    mat.needsUpdate = true
                }else{
                    if(base){
                        mat.color.copy(base.color)
                        if(mat.emissive && base.emissive){
                            mat.emissive.copy(base.emissive)
                            if(base.emissiveIntensity !== null){
                                mat.emissiveIntensity = base.emissiveIntensity
                            }
                        }
                    }
                    mat.needsUpdate = true
                }
            })
        }

        function updateTransformAttachment(){
            if(!transformControls) return
            if(selectedState.active){
                const root = selectedState.active?.userData?.root
                const pivot = selectedState.active?.userData?.pivot
                let target = selectedState.active
                if(transformMode === "rotate" && pivot){
                    target = pivot
                }else if(transformMode === "translate" && root){
                    target = root
                }
                transformControls.attach(target)
                transformControls.visible = true
                if(typeof transformControls.update === "function"){
                    transformControls.update()
                }else if(typeof transformControls.updateMatrixWorld === "function"){
                    transformControls.updateMatrixWorld(true)
                }
            }else{
                transformControls.detach()
                transformControls.visible = false
            }
        }

        function resolveTransformTarget(mesh){
            if(!mesh) return null
            const root = mesh.userData?.root
            const pivot = mesh.userData?.pivot
            if(transformMode === "rotate" && pivot){
                return pivot
            }
            if(transformMode !== "rotate" && root){
                return root
            }
            return mesh
        }

        function beginMultiTransform(){
            const meshes = Array.from(selectedState.objects)
            if(meshes.length <= 1){
                multiTransformState = null
                return
            }
            const activeTarget = resolveTransformTarget(selectedState.active)
            if(!activeTarget){
                multiTransformState = null
                return
            }
            activeTarget.updateMatrixWorld(true)
            const entries = meshes
                .map(mesh=>{
                    const target = resolveTransformTarget(mesh)
                    if(!target) return null
                    target.updateMatrixWorld(true)
                    return { target: target, matrix: target.matrixWorld.clone() }
                })
                .filter(entry=>entry !== null)
            if(entries.length <= 1){
                multiTransformState = null
                return
            }
            multiTransformState = {
                active: activeTarget,
                activeMatrix: activeTarget.matrixWorld.clone(),
                entries: entries
            }
        }

        function applyMultiTransformDelta(){
            if(!multiTransformState){
                return
            }
            const active = multiTransformState.active
            const baseMatrix = multiTransformState.activeMatrix
            if(!active || !baseMatrix){
                return
            }
            const currentMatrix = active.matrixWorld.clone()
            const inverseBase = baseMatrix.clone().invert()
            const delta = new THREE.Matrix4().multiplyMatrices(currentMatrix, inverseBase)
            multiTransformState.entries.forEach(entry=>{
                if(!entry || entry.target === active) return
                const nextMatrix = new THREE.Matrix4().multiplyMatrices(delta, entry.matrix)
                const pos = new THREE.Vector3()
                const quat = new THREE.Quaternion()
                const scale = new THREE.Vector3()
                nextMatrix.decompose(pos, quat, scale)
                entry.target.position.copy(pos)
                entry.target.quaternion.copy(quat)
                entry.target.scale.copy(scale)
                entry.target.updateMatrixWorld(true)
            })
        }

        function notifySelectionChanged(){
            if(!sendSelectionUpdate || suppressSelectionNotify) return
            const indices = Array.from(selectedState.objects)
                .map(obj=>obj?.userData?.elementIndex)
                .filter(idx=>Number.isInteger(idx))
            const activeIdx = selectedState.active?.userData?.elementIndex
            if(Number.isInteger(activeIdx)){
                const filtered = indices.filter(idx=>idx !== activeIdx)
                indices.length = 0
                indices.push(activeIdx, ...filtered)
            }
            sendSelectionUpdate(JSON.stringify(indices))
        }

        function isSelected(mesh){
            return selectedState.objects.has(mesh)
        }

        function removeSelection(mesh){
            if(!selectedState.objects.has(mesh)){
                return
            }
            if(mesh !== hoverState.object){
                setMeshHighlight(mesh, false)
            }
            const outline = selectedState.outlines.get(mesh)
            if(outline){
                outline.parent?.remove(outline)
                if(outline.children && outline.children.length){
                    outline.children.forEach(child=>{
                        if(child.geometry && child.geometry.dispose){
                            child.geometry.dispose()
                        }
                        if(child.material && child.material.dispose){
                            child.material.dispose()
                        }
                    })
                }else{
                    if(outline.geometry && outline.geometry.dispose){
                        outline.geometry.dispose()
                    }
                    if(outline.material && outline.material.dispose){
                        outline.material.dispose()
                    }
                }
                selectedState.outlines.delete(mesh)
            }
            selectedState.objects.delete(mesh)
            if(selectedState.active === mesh){
                selectedState.active = selectedState.objects.values().next().value || null
                updateTransformAttachment()
                notifySelectionChanged()
            }
        }

        function clearSelection(){
            const selectedMeshes = Array.from(selectedState.objects)
            selectedMeshes.forEach(mesh=>removeSelection(mesh))
            selectedState.active = null
            updateTransformAttachment()
            notifySelectionChanged()
        }

        function addSelection(mesh){
            if(selectedState.objects.has(mesh)){
                return
            }
            selectedState.objects.add(mesh)
            setMeshHighlight(mesh, true)
            const outlineGroup = new THREE.Group()
            const outlineScales = [1.0025, 1.01, 1.0175]
            outlineScales.forEach((scale, index)=>{
                const outlineGeom = new THREE.EdgesGeometry(mesh.geometry, 30)
                const outlineMat = new THREE.LineBasicMaterial({
                    color: 0x3674c8,
                    transparent: true,
                    opacity: index === 0 ? 0.95 : 0.45
                })
                const outline = new THREE.LineSegments(outlineGeom, outlineMat)
                outline.scale.set(scale, scale, scale)
                outline.renderOrder = 10
                outline.userData.ignoreViewMode = true
                outlineGroup.add(outline)
            })
            outlineGroup.renderOrder = 10
            mesh.add(outlineGroup)
            selectedState.outlines.set(mesh, outlineGroup)
            selectedState.active = mesh
            updateTransformAttachment()
            notifySelectionChanged()
        }

        function updateTransformAxisVisibility(){
            if(!transformControls) return
            transformControls.showX = true
            transformControls.showY = true
            transformControls.showZ = true
        }

        function updateTransformSnaps(){
            if(!transformControls) return
            const snap = isShiftDown ? 0.25 : snapStep
            transformControls.setTranslationSnap(snap)
            transformControls.setScaleSnap(snap)
            transformControls.setRotationSnap(THREE.MathUtils.degToRad(snap))
        }

        function roundValue(value){
            return Math.round(value * 10000) / 10000
        }

        function roundVec(values){
            return values.map(roundValue)
        }

        function pickRotationAxis(mesh, fallback){
            const rx = Math.abs(mesh.rotation.x)
            const ry = Math.abs(mesh.rotation.y)
            const rz = Math.abs(mesh.rotation.z)
            if(rx >= ry && rx >= rz){
                return "x"
            }
            if(ry >= rx && ry >= rz){
                return "y"
            }
            if(rz >= rx && rz >= ry){
                return "z"
            }
            return fallback || "y"
        }

        function commitTransformForMesh(mesh){
            if(!mesh || !sourceElements){
                return null
            }
            const index = mesh.userData?.elementIndex
            if(index === undefined || index === null){
                return null
            }
            const element = sourceElements[index]
            if(!element){
                return null
            }
            const baseSize = mesh.userData?.baseSize
            if(!baseSize){
                return null
            }
            const oldCenter = new THREE.Vector3(
                (element.from?.[0] ?? 0) + ((element.to?.[0] ?? 0) - (element.from?.[0] ?? 0)) / 2,
                (element.from?.[1] ?? 0) + ((element.to?.[1] ?? 0) - (element.from?.[1] ?? 0)) / 2,
                (element.from?.[2] ?? 0) + ((element.to?.[2] ?? 0) - (element.from?.[2] ?? 0)) / 2
            )
            const worldScale = new THREE.Vector3()
            mesh.getWorldScale(worldScale)
            const size = new THREE.Vector3(
                baseSize.x * worldScale.x,
                baseSize.y * worldScale.y,
                baseSize.z * worldScale.z
            )
            const meshWorld = new THREE.Vector3()
            mesh.getWorldPosition(meshWorld)
            const center = meshWorld.clone().add(currentModelCenter)
            const delta = center.clone().sub(oldCenter)
            const computedFrom = [
                center.x - size.x / 2,
                center.y - size.y / 2,
                center.z - size.z / 2
            ]
            const computedTo = [
                center.x + size.x / 2,
                center.y + size.y / 2,
                center.z + size.z / 2
            ]
            const from = (transformMode === "rotate" && Array.isArray(element.from)) ? element.from.slice() : computedFrom
            const to = (transformMode === "rotate" && Array.isArray(element.to)) ? element.to.slice() : computedTo
            const existingRotation = element.rotation ?? null
            const includeRotation = !!existingRotation || transformMode === "rotate"
            let rotation = null
            if(includeRotation){
                let origin = null
                const useDelta = transformMode !== "rotate"
                const pivot = mesh.userData?.pivot
                if(pivot){
                    const originWorld = new THREE.Vector3()
                    pivot.getWorldPosition(originWorld)
                    origin = [
                        originWorld.x + currentModelCenter.x,
                        originWorld.y + currentModelCenter.y,
                        originWorld.z + currentModelCenter.z
                    ]
                }else if(existingRotation?.origin && existingRotation.origin.length >= 3){
                    origin = [
                        existingRotation.origin[0] + (useDelta ? delta.x : 0),
                        existingRotation.origin[1] + (useDelta ? delta.y : 0),
                        existingRotation.origin[2] + (useDelta ? delta.z : 0)
                    ]
                }else{
                    origin = [center.x, center.y, center.z]
                }
                const rotTarget = pivot || mesh
                if(useAxisRotation){
                    const existingAxis = typeof existingRotation?.axis === "string"
                        ? existingRotation.axis.toLowerCase()
                        : null
                    const controlAxis = String(transformControls?.axis || "").toLowerCase()
                    let axis = (controlAxis && ["x","y","z"].includes(controlAxis)) ? controlAxis : null
                    if(!axis && lastRotationAxis){
                        axis = lastRotationAxis
                    }
                    if(!axis && existingAxis && ["x","y","z"].includes(existingAxis)){
                        axis = existingAxis
                    }
                    if(!axis){
                        axis = "y"
                    }
                    if(axis !== lastRotationAxis){
                        lastRotationAxis = axis
                    }
                    const angle = axis === "x"
                        ? THREE.MathUtils.radToDeg(rotTarget.rotation.x)
                        : axis === "y"
                            ? THREE.MathUtils.radToDeg(rotTarget.rotation.y)
                            : THREE.MathUtils.radToDeg(rotTarget.rotation.z)
                    rotation = {
                        origin: origin,
                        axis: axis,
                        angle: angle
                    }
                    const normalized = { x: 0, y: 0, z: 0 }
                    normalized[axis] = THREE.MathUtils.degToRad(angle)
                    rotTarget.rotation.set(normalized.x, normalized.y, normalized.z)
                }else{
                    rotation = {
                        origin: origin,
                        x: THREE.MathUtils.radToDeg(rotTarget.rotation.x),
                        y: THREE.MathUtils.radToDeg(rotTarget.rotation.y),
                        z: THREE.MathUtils.radToDeg(rotTarget.rotation.z)
                    }
                }
            }
            const payload = {
                index: index,
                from: roundVec(from),
                to: roundVec(to),
                rotation: rotation ? {
                    origin: roundVec(rotation.origin),
                    ...(useAxisRotation
                        ? { axis: rotation.axis, angle: roundValue(rotation.angle) }
                        : { x: roundValue(rotation.x), y: roundValue(rotation.y), z: roundValue(rotation.z) })
                } : null
            }
            element.from = payload.from.slice()
            element.to = payload.to.slice()
            if(payload.rotation){
                element.rotation = payload.rotation
            }else if(element.rotation){
                delete element.rotation
            }
            return payload
        }

        function commitTransformUpdate(){
            const meshes = Array.from(selectedState.objects)
            if(meshes.length === 0){
                return
            }
            const payloads = meshes
                .map(mesh=>commitTransformForMesh(mesh))
                .filter(payload=>payload)
            if(sendTransformUpdate && payloads.length){
                sendTransformUpdate(JSON.stringify(payloads))
            }
        }

        function applyViewMode(mode){
            viewMode = (mode === "solid" || mode === "wireframe") ? mode : "textured"
            scene.traverse(obj=>{
                if(!obj.isMesh) return
                if(obj.userData && obj.userData.ignoreViewMode) return
                const mats = Array.isArray(obj.material) ? obj.material : [obj.material]
                mats.forEach(mat=>{
                    if(!mat) return
                    let defaults = viewModeDefaults.get(mat)
                    if(!defaults){
                        defaults = {
                            map: mat.map || null,
                            wireframe: mat.wireframe === true,
                            vertexColors: mat.vertexColors
                        }
                        viewModeDefaults.set(mat, defaults)
                    }
                    if(viewMode === "solid"){
                        mat.map = null
                        mat.wireframe = false
                        mat.vertexColors = true
                        if(mat.color){
                            mat.color.setHex(0xffffff)
                        }
                    }else if(viewMode === "wireframe"){
                        mat.map = null
                        mat.wireframe = true
                        mat.vertexColors = true
                        if(mat.color){
                            mat.color.setHex(0xffffff)
                        }
                    }else{
                        mat.map = defaults.map
                        mat.wireframe = defaults.wireframe
                        mat.vertexColors = defaults.vertexColors
                    }
                    mat.needsUpdate = true
                })
            })
            originalMaterialColors = new WeakMap()
        }

        function loadGridTexture(){
            const texture = loader.load(resolveTexture("grid"), ()=>{
                if(gridMesh && gridMesh.material){
                    gridMesh.material.needsUpdate = true
                }
            })
            texture.magFilter = THREE.NearestFilter
            texture.minFilter = THREE.NearestFilter
            texture.wrapS = THREE.ClampToEdgeWrapping
            texture.wrapT = THREE.ClampToEdgeWrapping
            return texture
        }

        function updateGridTransform(){
            if(!gridMesh) return
            gridMesh.rotation.x = -Math.PI / 2
            gridMesh.position.set(
                8 - currentModelCenter.x,
                -0.01 - currentModelCenter.y,
                8 - currentModelCenter.z
            )
        }

        function ensureGridMesh(){
            if(gridMesh){
                return gridMesh
            }
            const geometry = new THREE.PlaneGeometry(48, 48)
            const material = new THREE.MeshBasicMaterial({
                map: loadGridTexture(),
                color: 0xffffff,
                side: THREE.DoubleSide,
                transparent: true,
                alphaTest: 0.1
            })
            gridMesh = new THREE.Mesh(geometry, material)
            gridMesh.userData.ignoreViewMode = true
            gridMesh.renderOrder = -1
            updateGridTransform()
            scene.add(gridMesh)
            return gridMesh
        }

        function applyGridEnabled(enabled){
            gridEnabled = !!enabled
            if(gridEnabled){
                ensureGridMesh()
                gridMesh.visible = true
            }else if(gridMesh){
                gridMesh.visible = false
            }
        }

        function updateHover(){
            if(!hasPointer) return
            raycaster.setFromCamera(pointer, camera)
            const hits = raycaster.intersectObjects(pickableMeshes, false)
            const next = hits.length ? hits[0].object : null
            if(next === hoverState.object) return
            if(hoverState.object && !isSelected(hoverState.object)){
                setMeshHighlight(hoverState.object, false)
            }
            if(next){
                setMeshHighlight(next, true)
            }
            hoverState.object = next
            if(sendHover){
                const index = next?.userData?.elementIndex
                console.log("Hover element index:", index)
                sendHover(String(Number.isInteger(index) ? index : -1))
            }else if(!hoverDebugLogged){
                console.log("sendHover not available")
                hoverDebugLogged = true
            }
        }
        
        window.pendingModelJson = null

        window.loadModel = async function(model, resetCamera=true){
            clearScene()
            sourceModel = model
            sourceElements = Array.isArray(model?.elements) ? model.elements : null
            textures = {}
            animatedTextures = new Map()

            const resolvedModel = await resolveModel(model)
            const occupancy = new Set()
            let minX = Infinity
            let minY = Infinity
            let minZ = Infinity
            let maxX = -Infinity
            let maxY = -Infinity
            let maxZ = -Infinity

            resolvedModel.elements?.forEach(el=>{
                minX = Math.min(minX, el.from[0])
                minY = Math.min(minY, el.from[1])
                minZ = Math.min(minZ, el.from[2])
                maxX = Math.max(maxX, el.to[0])
                maxY = Math.max(maxY, el.to[1])
                maxZ = Math.max(maxZ, el.to[2])
                const x0 = Math.floor(el.from[0])
                const y0 = Math.floor(el.from[1])
                const z0 = Math.floor(el.from[2])
                const x1 = Math.ceil(el.to[0]) - 1
                const y1 = Math.ceil(el.to[1]) - 1
                const z1 = Math.ceil(el.to[2]) - 1
                for(let x=x0; x<=x1; x++){
                    for(let y=y0; y<=y1; y++){
                        for(let z=z0; z<=z1; z++){
                            occupancy.add(x + "," + y + "," + z)
                        }
                    }
                }
            })

            if(resolvedModel.textures){
                Object.keys(resolvedModel.textures).forEach(key=>{
                    const texRef = resolveTextureRef(resolvedModel.textures[key], resolvedModel.textures)
                    if(texRef){
                        textures[key] = getTextureForRef(texRef)
                    }
                })
            }

            const computedCenter = (isFinite(minX) && isFinite(maxX)) ? new THREE.Vector3(
                (minX + maxX) / 2,
                (minY + maxY) / 2,
                (minZ + maxZ) / 2
            ) : DEFAULT_ORBIT_TARGET
            if(resetCamera){
                currentModelCenter = computedCenter
            }
            const center = currentModelCenter
            if(resetCamera){
                orbitTarget = new THREE.Vector3(0,0,0)
            }

            console.log("Resolved elements:", resolvedModel.elements?.length || 0)
            resolvedModel.elements?.forEach((el, elementIndex)=>{
                const sx = el.to[0]-el.from[0]
                const sy = el.to[1]-el.from[1]
                const sz = el.to[2]-el.from[2]

                const geometry = new THREE.BoxGeometry(sx,sy,sz).toNonIndexed()
                console.log("[Geom] indexed", !!geometry.index, "uvCount", geometry.attributes?.uv?.count, "posCount", geometry.attributes?.position?.count)
                const materials = []
                const faceOrder=["east","west","up","down","south","north"]

                faceOrder.forEach((face,i)=>{
                    const faceDef = el.faces?.[face]
                    const shade = minecraftFaceShade(face)
                    if(faceDef && faceDef.texture){
                        let tex = null
                        if(faceDef.texture.startsWith("#")){
                            const texKey = faceDef.texture.replace("#","")
                            tex = textures[texKey]
                        }else{
                            tex = getTextureForRef(faceDef.texture)
                        }
                        materials.push(new THREE.MeshBasicMaterial({
                            map: tex,
                            color: 0xffffff,
                            vertexColors: true,
                            transparent: true,
                            alphaTest: 0.1
                        }))
                        applyFaceUV(geometry, i, faceDef.uv, tex?.image?.width||16, tex?.image?.height||16, faceDef.rotation, face)
                        applyFaceAOColors(geometry, i, face, shade, el, sx, sy, sz, occupancy)
                    }else{
                        materials.push(new THREE.MeshBasicMaterial({
                            color: 0xffffff,
                            vertexColors: true,
                            transparent: true,
                            alphaTest: 0.1
                        }))
                        applyFaceAOColors(geometry, i, face, shade, el, sx, sy, sz, occupancy)
                    }
                })

                const mesh = new THREE.Mesh(geometry, materials)
                const elementCenter = new THREE.Vector3(
                    el.from[0] + sx / 2,
                    el.from[1] + sy / 2,
                    el.from[2] + sz / 2
                )
                const elementRoot = new THREE.Object3D()
                elementRoot.position.set(
                    elementCenter.x - center.x,
                    elementCenter.y - center.y,
                    elementCenter.z - center.z
                )
                let rotationOrigin = elementCenter.clone()
                if(el.rotation?.origin && el.rotation.origin.length >= 3){
                    rotationOrigin = new THREE.Vector3(
                        el.rotation.origin[0],
                        el.rotation.origin[1],
                        el.rotation.origin[2]
                    )
                }
                const originOffset = rotationOrigin.clone().sub(elementCenter)
                const pivot = new THREE.Object3D()
                pivot.position.copy(originOffset)
                elementRoot.add(pivot)
                mesh.position.set(-originOffset.x, -originOffset.y, -originOffset.z)
                pivot.add(mesh)

                if(el.rotation){
                    if(Number.isFinite(el.rotation.x) || Number.isFinite(el.rotation.y) || Number.isFinite(el.rotation.z)){
                        const rx = THREE.MathUtils.degToRad(el.rotation.x || 0)
                        const ry = THREE.MathUtils.degToRad(el.rotation.y || 0)
                        const rz = THREE.MathUtils.degToRad(el.rotation.z || 0)
                        pivot.rotation.set(rx, ry, rz)
                    }else if(Number.isFinite(el.rotation.angle) && el.rotation.axis){
                        const axisName = el.rotation.axis
                        const axisVec = axisName === "x" ? new THREE.Vector3(1,0,0)
                            : axisName === "y" ? new THREE.Vector3(0,1,0)
                            : new THREE.Vector3(0,0,1)
                        const angleRad = THREE.MathUtils.degToRad(el.rotation.angle)
                        pivot.quaternion.setFromAxisAngle(axisVec, angleRad)
                        pivot.rotation.setFromQuaternion(pivot.quaternion)
                    }
                }
                mesh.userData.elementIndex = elementIndex
                mesh.userData.baseSize = { x: sx, y: sy, z: sz }
                mesh.userData.root = elementRoot
                mesh.userData.pivot = pivot
                elementRoots[elementIndex] = elementRoot
                elementRoot.visible = !hiddenElements.has(elementIndex)
                scene.add(elementRoot)
                pickableMeshes.push(mesh)
            })
            applyViewMode(viewMode)
            if(gridEnabled){
                updateGridTransform()
            }
            console.log("Pickable meshes:", pickableMeshes.length)

            if(resetCamera){
                orbitRadius = Math.max(10, camera.position.distanceTo(orbitTarget))
                orbitTheta = Math.atan2(camera.position.z - orbitTarget.z, camera.position.x - orbitTarget.x)
                orbitPhi = Math.acos((camera.position.y - orbitTarget.y) / orbitRadius)
                updateCameraFromOrbit()
            }
        }

        window.loadModelFromJson = function(jsonText, resetCamera=true){
            try{
                const parsed = JSON.parse(jsonText)
                return window.loadModel(parsed, resetCamera)
            }catch(e){
                console.warn("Model JSON parse failed:", e)
            }
        }

        window.setViewMode = function(mode){
            applyViewMode(mode)
        }

        window.setOrthographic = function(enabled){
            applyOrthographic(enabled)
        }

        window.setGridEnabled = function(enabled){
            applyGridEnabled(enabled)
        }

        window.setTransformMode = function(mode){
            transformMode = (mode === "rotate" || mode === "scale") ? mode : "translate"
            if(transformControls){
                transformControls.setMode(transformMode)
                updateTransformAxisVisibility()
                updateTransformAttachment()
            }
        }

        window.setTransformSpace = function(space){
            transformSpace = (space === "world") ? "world" : "local"
            if(transformControls){
                transformControls.setSpace(transformSpace)
            }
        }

        window.setTransformAxis = function(axis){
            transformAxis = (axis === "x" || axis === "y" || axis === "z") ? axis : "y"
            updateTransformAxisVisibility()
        }

        window.setTransformSnap = function(value){
            if(Number.isFinite(value) && value > 0){
                snapStep = value
                updateTransformSnaps()
            }
        }

        window.setSelectedElements = function(indices){
            suppressSelectionNotify = true
            clearSelection()
            if(Array.isArray(indices)){
                indices.forEach(index=>{
                    if(!Number.isInteger(index) || index < 0) return
                    const target = pickableMeshes.find(mesh=>mesh?.userData?.elementIndex === index)
                    if(target){
                        addSelection(target)
                    }
                })
            }
            suppressSelectionNotify = false
            notifySelectionChanged()
        }

        window.setSelectedElement = function(index){
            if(!Number.isInteger(index) || index < 0){
                window.setSelectedElements([])
                return
            }
            window.setSelectedElements([index])
        }

        window.setElementHidden = function(index, hidden){
            if(!Number.isInteger(index)) return
            const root = elementRoots[index]
            if(!root) return
            const isHidden = !!hidden
            if(isHidden){
                hiddenElements.add(index)
                if(selectedState.active?.userData?.elementIndex === index){
                    clearSelection()
                }
            }else{
                hiddenElements.delete(index)
            }
            root.visible = !isHidden
        }

        window.resetCamera = function(){
            resetCameraState()
        }

        window.addEventListener('DOMContentLoaded', ()=>{
            if(window.pendingModelJson){
                loadModel(window.pendingModelJson)
                window.pendingModelJson = null
            }
            console.log("Viewer HTML ready")
            if(sendHover){
                try{
                    sendHover("ping")
                }catch(e){
                    console.error("Hover ping failed:", e)
                }
                console.log("Hover ping sent")
            }else{
                console.log("Hover ping skipped; sendHover not available")
            }
        })

        window.addEventListener("keydown", (e)=>{
            if(e.key === "Shift"){
                isShiftDown = true
                updateTransformSnaps()
            }
        })

        window.addEventListener("keyup", (e)=>{
            if(e.key === "Shift"){
                isShiftDown = false
                updateTransformSnaps()
            }
        })

        function animate(){
            requestAnimationFrame(animate)
            updateAnimatedTextures(performance.now())
            updateHover()
            renderer.render(scene, camera)
        }
        animate()

        let isDragging = false
        let isPanning = false
        let lastX = 0
        let lastY = 0
        let clickStartX = 0
        let clickStartY = 0
        let clickStartButton = 0

        function updatePointerFromEvent(e){
            const rect = canvas.getBoundingClientRect()
            pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1
            pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1
            hasPointer = true
        }

        canvas.addEventListener("mousedown", (e)=>{
            if(e.button === 0){
                isDragging = true
            }else if(e.button === 2){
                isPanning = true
            }
            lastX = e.clientX
            lastY = e.clientY
            clickStartX = e.clientX
            clickStartY = e.clientY
            clickStartButton = e.button
            updatePointerFromEvent(e)
        })

        window.addEventListener("mouseup", (e)=>{
            isDragging = false
            isPanning = false
            if(wasTransforming){
                wasTransforming = false
                return
            }
            if(clickStartButton === 0){
                const dx = e.clientX - clickStartX
                const dy = e.clientY - clickStartY
                if(Math.hypot(dx, dy) < 4){
                    updatePointerFromEvent(e)
                    raycaster.setFromCamera(pointer, camera)
                    const hits = raycaster.intersectObjects(pickableMeshes, false)
                    const isMultiSelect = e.ctrlKey
                    if(hits.length){
                        const target = hits[0].object
                        if(isMultiSelect){
                            if(isSelected(target)){
                                removeSelection(target)
                            }else{
                                addSelection(target)
                            }
                        }else{
                            clearSelection()
                            addSelection(target)
                        }
                    }else if(!isMultiSelect){
                        clearSelection()
                    }
                }
            }
        })

        window.addEventListener("mousemove", (e)=>{
            const dx = e.clientX - lastX
            const dy = e.clientY - lastY
            lastX = e.clientX
            lastY = e.clientY
            updatePointerFromEvent(e)
            if(sendHover && !hoverHandshakeSent){
                sendHover("-1")
                hoverHandshakeSent = true
                console.log("Hover handshake sent")
            }

            if(isTransforming){
                return
            }

            if(isDragging){
                const ROT_SPEED = 0.005
                orbitTheta += dx * ROT_SPEED
                orbitPhi -= dy * ROT_SPEED
                const EPS = 0.01
                orbitPhi = Math.max(EPS, Math.min(Math.PI - EPS, orbitPhi))
                updateCameraFromOrbit()
            }else if(isPanning){
                const PAN_SPEED = 0.002 * orbitRadius
                const forward = new THREE.Vector3()
                const right = new THREE.Vector3()
                camera.getWorldDirection(forward)
                right.crossVectors(forward, camera.up).normalize()
                const up = camera.up.clone().normalize()
                orbitTarget.addScaledVector(right, -dx * PAN_SPEED)
                orbitTarget.addScaledVector(up, dy * PAN_SPEED)
                updateCameraFromOrbit()
            }
        })

        canvas.addEventListener("mouseleave", ()=>{
            hasPointer = false
            if(hoverState.object){
                if(!isSelected(hoverState.object)){
                    setMeshHighlight(hoverState.object, false)
                }
                hoverState.object = null
            }
            if(sendHover){
                sendHover("-1")
            }
        })

        canvas.addEventListener("wheel", (e)=>{
            e.preventDefault()
            const ZOOM_SPEED = 0.002
            if(useOrthographic){
                const nextZoom = orthoCamera.zoom * (1 - e.deltaY * ZOOM_SPEED)
                orthoCamera.zoom = Math.max(0.2, Math.min(8, nextZoom))
                orthoCamera.updateProjectionMatrix()
            }else{
                orbitRadius = Math.max(2, orbitRadius * (1 + e.deltaY * ZOOM_SPEED))
                updateCameraFromOrbit()
            }
        }, { passive: false })

        canvas.addEventListener("contextmenu", (e)=>e.preventDefault())

        window.addEventListener('resize', ()=>{
            renderer.setSize(Math.floor(window.innerWidth), Math.floor(window.innerHeight))
            const aspect = window.innerWidth / window.innerHeight
            perspectiveCamera.aspect = aspect
            perspectiveCamera.updateProjectionMatrix()
            updateOrthoFrustum()
        })
        </script>
        </body>
        </html>
    """.trimIndent()

    private data class TextureEntry(
        val key: String,
        val value: String,
        val resolvedValue: String,
        val displayName: String,
        val textureFile: File?
    )

    private enum class ElementNodeKind { ROOT, GROUP, ELEMENT }

    private data class ElementTreeItem(
        val kind: ElementNodeKind,
        val name: String,
        val index: Int? = null,
        val groupPath: List<Int>? = null
    )

    private data class HeaderAction(val icon: Icon, val tooltip: String, val handler: (JButton) -> Unit)

    private data class TexturesPanelState(
        val panel: JComponent,
        val model: DefaultListModel<TextureEntry>,
        val searchField: SearchTextField,
        val allEntries: MutableList<TextureEntry>,
        val list: JBList<TextureEntry>,
        val scrollPane: JBScrollPane,
        val previewCache: MutableMap<String, Icon?>,
        val animationCache: MutableMap<String, Boolean>,
        val loadingPreviews: MutableSet<String>
    ) {
        var lastModelPath: String? = null
    }

    private class ElementsPanelState(
        val panel: JComponent,
        val tree: Tree,
        val model: DefaultTreeModel,
        val countLabel: JBLabel,
        val elementNodes: MutableMap<Int, DefaultMutableTreeNode>,
        val hiddenElements: MutableSet<Int>
    ) {
        var suppressSelectionSync = false
        var totalElements = 0
        var lastModelPath: String? = null
    }

    private fun createControlsPanel(project: Project, viewerService: ModelViewerService): JComponent {
        val tabbedPane = createDraggableTabbedPane().apply {
            preferredSize = JBUI.size(360, 420)
        }

        lateinit var texturesState: TexturesPanelState
        lateinit var elementsState: ElementsPanelState
        val refresh = {
            refreshFromActiveJson(viewerService, texturesState, elementsState)
        }

        texturesState = createTexturesPanel(refresh)
        elementsState = createElementsPanel(project, viewerService, refresh)

        tabbedPane.addTab(
            "Textures",
            AllIcons.FileTypes.Image,
            texturesState.panel
        )
        tabbedPane.addTab(
            "Elements",
            AllIcons.Nodes.Folder,
            elementsState.panel
        )

        refresh()

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : EditorDocumentListener {
                override fun documentChanged(event: EditorDocumentEvent) {
                    val activeEditor = viewerService.getActiveModelEditor() ?: return
                    if (activeEditor.isDisposed || event.document != activeEditor.document) {
                        return
                    }
                    refresh()
                }
            },
            project
        )

        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    SwingUtilities.invokeLater { refresh() }
                }
            }
        )

        viewerService.addSelectionListener(project) { indices ->
            SwingUtilities.invokeLater {
                updateSelectionFromViewer(indices, elementsState)
            }
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = BACKGROUND_COLOR
            border = JBUI.Borders.empty()
            add(tabbedPane, BorderLayout.CENTER)
        }
    }

    private fun createTexturesPanel(onRefresh: () -> Unit): TexturesPanelState {
        val model = DefaultListModel<TextureEntry>()
        val allEntries = mutableListOf<TextureEntry>()
        val previewCache = ConcurrentHashMap<String, Icon?>()
        val animationCache = ConcurrentHashMap<String, Boolean>()
        val loadingPreviews = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        val searchField = SearchTextField().apply {
            isVisible = false
            textEditor.emptyText.text = "Filter textures"
            textEditor.background = BACKGROUND_COLOR
        }

        lateinit var state: TexturesPanelState

        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = BACKGROUND_COLOR
        }
        val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.emptyTop(6) }

        fun refreshFilter() {
            updateTextureModel(model, allEntries, searchField.text)
            prefetchTexturePreviews(state, list, scrollPane)
        }

        val header = createCategoryHeader(
            "TEXTURES",
            listOf(
                HeaderAction(AllIcons.Actions.Refresh, "Reload from JSON") { _ ->
                    onRefresh()
                }
            ),
            listOf(
                HeaderAction(AllIcons.Actions.Search, "Search") { _ ->
                    toggleSearchField(searchField)
                    refreshFilter()
                }
            )
        )

        val headerContainer = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(searchField, BorderLayout.SOUTH)
        }

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = BACKGROUND_COLOR
            border = JBUI.Borders.empty(6, 6, 6, 6)
            add(headerContainer, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        state = TexturesPanelState(
            panel = panel,
            model = model,
            searchField = searchField,
            allEntries = allEntries,
            list = list,
            scrollPane = scrollPane,
            previewCache = previewCache,
            animationCache = animationCache,
            loadingPreviews = loadingPreviews
        )
        list.cellRenderer = TextureListRenderer(state, list)

        attachSearchFilter(searchField) { refreshFilter() }
        scrollPane.viewport.addChangeListener {
            prefetchTexturePreviews(state, list, scrollPane)
        }

        return state
    }

    private fun createElementsPanel(
        project: Project,
        viewerService: ModelViewerService,
        onRefresh: () -> Unit
    ): ElementsPanelState {
        val rootNode = DefaultMutableTreeNode(ElementTreeItem(ElementNodeKind.ROOT, "root"))
        val model = DefaultTreeModel(rootNode)
        val tree = Tree(model).apply {
            isRootVisible = false
            showsRootHandles = true
            background = BACKGROUND_COLOR
            selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        }
        val elementNodes = mutableMapOf<Int, DefaultMutableTreeNode>()
        val hiddenElements = mutableSetOf<Int>()

        val countLabel = JBLabel().apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyRight(6)
        }

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = BACKGROUND_COLOR
            border = JBUI.Borders.empty(6, 6, 6, 6)
        }
        val state = ElementsPanelState(
            panel = panel,
            tree = tree,
            model = model,
            countLabel = countLabel,
            elementNodes = elementNodes,
            hiddenElements = hiddenElements
        )

        tree.cellRenderer = ElementsTreeRenderer(state)
        tree.addTreeSelectionListener {
            if (state.suppressSelectionSync) {
                return@addTreeSelectionListener
            }
            val indices = tree.selectionPaths
                ?.mapNotNull { path ->
                    val node = path.lastPathComponent as? DefaultMutableTreeNode
                    val item = node?.userObject as? ElementTreeItem
                    item?.takeIf { it.kind == ElementNodeKind.ELEMENT }?.index
                }
                ?.distinct()
                ?.filterNotNull()
                ?: emptyList()
            if (indices.isNotEmpty()) {
                viewerService.setSelectedElements(indices)
            } else {
                viewerService.setSelectedElement(null)
            }
            updateElementCountLabel(state, selectedCount = indices.size)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val row = tree.getRowForLocation(e.x, e.y)
                if (row < 0) return
                val path = tree.getPathForRow(row) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val item = node.userObject as? ElementTreeItem ?: return
                if (SwingUtilities.isRightMouseButton(e) && item.kind == ElementNodeKind.GROUP) {
                    showGroupContextMenu(project, viewerService, state, item, e.component, e.x, e.y)
                    return
                }
                if (item.kind != ElementNodeKind.ELEMENT || item.index == null) return
                val iconSize = JBUI.scale(16)
                val padding = JBUI.scale(10)
                val iconX = tree.width - iconSize - padding
                if (e.x >= iconX) {
                    val hidden = state.hiddenElements.contains(item.index)
                    if (hidden) {
                        state.hiddenElements.remove(item.index)
                    } else {
                        state.hiddenElements.add(item.index)
                    }
                    viewerService.setElementHidden(item.index, !hidden)
                    tree.getRowBounds(row)?.let { tree.repaint(it) }
                    e.consume()
                }
            }
        })

        val header = createCategoryHeader(
            "ELEMENTS",
            listOf(
                HeaderAction(AllIcons.General.Add, "Add group") { _ ->
                    addEmptyGroup(project, viewerService) {
                        onRefresh()
                    }
                },
                HeaderAction(AllIcons.Actions.Refresh, "Reload from JSON") { _ ->
                    onRefresh()
                }
            ),
            emptyList(),
            countLabel
        )

        panel.add(header, BorderLayout.NORTH)
        panel.add(JBScrollPane(tree).apply { border = JBUI.Borders.emptyTop(6) }, BorderLayout.CENTER)

        return state
    }

    private fun createCategoryHeader(
        title: String,
        leftActions: List<HeaderAction>,
        rightActions: List<HeaderAction>,
        rightPrefix: JComponent? = null
    ): JComponent {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(title).apply {
                foreground = JBColor.namedColor("Label.foreground", JBColor(0xd6d6d6, 0xd6d6d6))
                font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            })
            leftActions.forEach { action ->
                add(createHeaderIconButton(action))
            }
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            if (rightPrefix != null) {
                add(rightPrefix)
            }
            rightActions.forEach { action ->
                add(createHeaderIconButton(action))
            }
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 2)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    private fun createHeaderIconButton(action: HeaderAction): JButton {
        return JButton(action.icon).apply {
            toolTipText = action.tooltip
            isOpaque = false
            border = JBUI.Borders.empty()
            isContentAreaFilled = false
            isFocusable = false
            preferredSize = JBUI.size(20, 20)
            addActionListener { action.handler(this) }
        }
    }

    private fun attachSearchFilter(searchField: SearchTextField, onChange: (String) -> Unit) {
        searchField.textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                onChange(searchField.text)
            }

            override fun removeUpdate(e: DocumentEvent) {
                onChange(searchField.text)
            }

            override fun changedUpdate(e: DocumentEvent) {
                onChange(searchField.text)
            }
        })
    }

    private fun toggleSearchField(searchField: SearchTextField) {
        searchField.isVisible = !searchField.isVisible
        if (searchField.isVisible) {
            searchField.requestFocusInWindow()
        } else {
            searchField.text = ""
        }
        searchField.parent?.revalidate()
        searchField.parent?.repaint()
    }

    private fun refreshFromActiveJson(
        viewerService: ModelViewerService,
        texturesState: TexturesPanelState,
        elementsState: ElementsPanelState
    ) {
        val jsonText = viewerService.getActiveModelText()
        if (jsonText.isNullOrBlank()) {
            clearTextures(texturesState)
            clearElements(elementsState)
            return
        }
        val root = parseRootObject(jsonText) ?: return
        updateTexturesFromJson(root, texturesState, viewerService)
        updateElementsFromJson(root, elementsState, viewerService)
    }

    private fun parseRootObject(jsonText: String?): JsonObject? {
        if (jsonText.isNullOrBlank()) return null
        val element = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return null
        return element.takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun clearTextures(state: TexturesPanelState) {
        state.allEntries.clear()
        state.model.clear()
        state.previewCache.clear()
        state.animationCache.clear()
        state.loadingPreviews.clear()
        state.lastModelPath = null
    }

    private fun clearElements(state: ElementsPanelState) {
        state.elementNodes.clear()
        state.hiddenElements.clear()
        state.totalElements = 0
        state.model.setRoot(DefaultMutableTreeNode(ElementTreeItem(ElementNodeKind.ROOT, "root")))
        state.model.reload()
        updateElementCountLabel(state, selectedCount = 0)
    }

    private fun updateTexturesFromJson(
        root: JsonObject,
        state: TexturesPanelState,
        viewerService: ModelViewerService
    ) {
        state.allEntries.clear()
        val activeFile = viewerService.getActiveModelFile()
        val activePath = activeFile?.path
        if (activePath != state.lastModelPath) {
            state.previewCache.clear()
            state.animationCache.clear()
            state.loadingPreviews.clear()
            state.lastModelPath = activePath
        }
        val assetsRoot = activeFile?.let { AssetRootResolver.findAssetsRoot(it) }
        val defaultNamespace = resolveNamespaceFromModel(activeFile, assetsRoot)

        val textures = root.getAsJsonObject("textures")
        val textureMap = buildTextureMap(textures)
        textures?.entrySet()?.forEach { entry ->
            val rawValue = if (entry.value.isJsonPrimitive) {
                entry.value.asString
            } else {
                entry.value.toString()
            }
            val resolvedValue = resolveTextureValue(rawValue, textureMap)
            val displayName = extractTextureFileName(resolvedValue.removePrefix("#"))
            val textureFile = resolveTextureFile(assetsRoot, defaultNamespace, resolvedValue)
            state.allEntries.add(
                TextureEntry(
                    key = entry.key,
                    value = rawValue,
                    resolvedValue = resolvedValue,
                    displayName = displayName,
                    textureFile = textureFile
                )
            )
        }
        updateTextureModel(state.model, state.allEntries, state.searchField.text)
        prefetchTexturePreviews(state, state.list, state.scrollPane)
    }

    private fun updateTextureModel(
        model: DefaultListModel<TextureEntry>,
        entries: List<TextureEntry>,
        filter: String
    ) {
        model.clear()
        val trimmed = filter.trim()
        val filtered = if (trimmed.isEmpty()) {
            entries
        } else {
            entries.filter {
                it.key.contains(trimmed, ignoreCase = true) ||
                    it.value.contains(trimmed, ignoreCase = true) ||
                    it.resolvedValue.contains(trimmed, ignoreCase = true) ||
                    it.displayName.contains(trimmed, ignoreCase = true)
            }
        }
        filtered.forEach { model.addElement(it) }
    }

    private fun extractTextureFileName(value: String): String {
        var name = value
        val slashIndex = name.lastIndexOf('/')
        if (slashIndex >= 0 && slashIndex + 1 < name.length) {
            name = name.substring(slashIndex + 1)
        }
        val colonIndex = name.lastIndexOf(':')
        if (colonIndex >= 0 && colonIndex + 1 < name.length) {
            name = name.substring(colonIndex + 1)
        }
        if (!name.contains('.')) {
            name += ".png"
        }
        return name
    }

    private fun buildTextureMap(textures: JsonObject?): Map<String, String> {
        if (textures == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        textures.entrySet().forEach { entry ->
            if (entry.value.isJsonPrimitive) {
                result[entry.key] = entry.value.asString
            }
        }
        return result
    }

    private fun resolveTextureValue(
        rawValue: String,
        textureMap: Map<String, String>,
        depth: Int = 0
    ): String {
        if (depth > 10) return rawValue
        if (!rawValue.startsWith("#")) return rawValue
        val key = rawValue.substring(1)
        val next = textureMap[key] ?: return rawValue
        return resolveTextureValue(next, textureMap, depth + 1)
    }

    private fun resolveNamespaceFromModel(file: VirtualFile?, assetsRoot: File?): String? {
        if (file == null || assetsRoot == null) return null
        val assetsPath = assetsRoot.toPath().toAbsolutePath().normalize()
        val filePath = File(file.path).toPath().toAbsolutePath().normalize()
        if (!filePath.startsWith(assetsPath)) return null
        val relative = assetsPath.relativize(filePath).toString().replace("\\", "/")
        return relative.substringBefore("/").ifBlank { null }
    }

    private fun resolveTextureFile(
        assetsRoot: File?,
        defaultNamespace: String?,
        resolvedValue: String
    ): File? {
        if (assetsRoot == null) return null
        var value = resolvedValue.trim()
        if (value.startsWith("#")) return null
        val namespace: String
        var path = value
        if (value.contains(":")) {
            val parts = value.split(":", limit = 2)
            namespace = parts[0]
            path = parts[1]
        } else {
            namespace = defaultNamespace ?: "minecraft"
        }
        path = normalizeTexturePath(path)
        return File(assetsRoot, "$namespace/textures/$path")
    }

    private fun normalizeTexturePath(path: String): String {
        var cleaned = path.replace("\\", "/").removePrefix("/")
        if (cleaned.startsWith("textures/")) {
            cleaned = cleaned.removePrefix("textures/")
        }
        if (!cleaned.endsWith(".png")) {
            cleaned += ".png"
        }
        return cleaned
    }

    private fun loadTexturePreviewIcon(state: TexturesPanelState, file: File?): Icon? {
        if (file == null || !file.isFile) return null
        val key = file.path
        if (state.previewCache.containsKey(key)) {
            return state.previewCache[key]
        }
        val image = runCatching { ImageIO.read(file) }.getOrNull()
        val icon = image?.let { ImageIcon(scaleTexturePreview(it, JBUI.scale(TEXTURE_PREVIEW_SIZE))) }
        state.previewCache[key] = icon
        return icon
    }

    private fun isTextureAnimated(state: TexturesPanelState, file: File): Boolean {
        val key = file.path
        if (state.animationCache.containsKey(key)) {
            return state.animationCache[key] == true
        }
        val animated = File(file.path + ".mcmeta").isFile
        state.animationCache[key] = animated
        return animated
    }

    private fun resolveTexturePreviewIcon(
        entry: TextureEntry,
        state: TexturesPanelState,
        list: JList<out TextureEntry>
    ): Icon? {
        val file = entry.textureFile ?: return AllIcons.FileTypes.Image
        val key = file.path
        if (state.previewCache.containsKey(key)) {
            return state.previewCache[key]
        }
        if (state.loadingPreviews.add(key)) {
            texturePreviewExecutor.execute {
                loadTexturePreviewIcon(state, file)
                state.loadingPreviews.remove(key)
                ApplicationManager.getApplication().invokeLater {
                    list.repaint()
                }
            }
        }
        return AllIcons.FileTypes.Image
    }

    private fun prefetchTexturePreviews(
        state: TexturesPanelState,
        list: JBList<TextureEntry>,
        scrollPane: JBScrollPane
    ) {
        val size = list.model.size
        if (size <= 0) return
        val viewport = scrollPane.viewport
        val viewPos = viewport.viewPosition
        val firstIndex = list.locationToIndex(viewPos)
        if (firstIndex < 0) return
        val bottom = Point(viewPos.x, viewPos.y + viewport.extentSize.height - 1)
        val lastIndex = list.locationToIndex(bottom).takeIf { it >= 0 } ?: firstIndex
        val start = max(0, firstIndex - TEXTURE_PREFETCH_PADDING)
        val end = min(size - 1, lastIndex + TEXTURE_PREFETCH_PADDING)
        if (start > end) return
        for (i in start..end) {
            val entry = list.model.getElementAt(i)
            resolveTexturePreviewIcon(entry, state, list)
        }
    }

    private fun scaleTexturePreview(image: BufferedImage, size: Int): BufferedImage {
        val ratio = min(size.toDouble() / image.width, size.toDouble() / image.height)
        val targetW = max(1, (image.width * ratio).roundToInt())
        val targetH = max(1, (image.height * ratio).roundToInt())
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            val x = (size - targetW) / 2
            val y = (size - targetH) / 2
            g2.drawImage(image, x, y, targetW, targetH, null)
        } finally {
            g2.dispose()
        }
        return canvas
    }

    private fun updateElementsFromJson(
        root: JsonObject,
        state: ElementsPanelState,
        viewerService: ModelViewerService
    ) {
        val filePath = viewerService.getActiveModelFile()?.path
        if (filePath != state.lastModelPath) {
            state.hiddenElements.clear()
            state.lastModelPath = filePath
        }
        val elementNames = mutableMapOf<Int, String>()
        val elements = root.getAsJsonArray("elements")
        val totalElements = elements?.size() ?: 0
        state.totalElements = totalElements
        elements?.forEachIndexed { index, json ->
            val name = if (json.isJsonObject) {
                json.asJsonObject.get("name")?.asString ?: ""
            } else {
                ""
            }
            elementNames[index] = name
        }
        state.hiddenElements.removeAll { it < 0 || it >= totalElements }

        state.elementNodes.clear()
        val rootNode = DefaultMutableTreeNode(ElementTreeItem(ElementNodeKind.ROOT, "root"))
        val used = mutableSetOf<Int>()
        val groups = root.getAsJsonArray("groups")

        if (groups != null) {
            groups.forEachIndexed { index, entry ->
                when {
                    entry.isJsonObject -> {
                        val groupNode = parseGroupNode(entry.asJsonObject, elementNames, used, state, listOf(index))
                        rootNode.add(groupNode)
                    }
                    entry.isJsonPrimitive && entry.asJsonPrimitive.isNumber -> {
                        val index = entry.asInt
                        val node = createElementNode(index, elementNames, used, state)
                        if (node != null) {
                            rootNode.add(node)
                        }
                    }
                }
            }
            for (index in 0 until totalElements) {
                if (!used.contains(index)) {
                    val node = createElementNode(index, elementNames, used, state)
                    if (node != null) {
                        rootNode.add(node)
                    }
                }
            }
        } else {
            for (index in 0 until totalElements) {
                val node = createElementNode(index, elementNames, used, state)
                if (node != null) {
                    rootNode.add(node)
                }
            }
        }

        state.model.setRoot(rootNode)
        state.model.reload()
        updateSelectionFromViewer(viewerService.getSelectedIndices(), state)

        for (index in 0 until totalElements) {
            viewerService.setElementHidden(index, state.hiddenElements.contains(index))
        }
    }

    private fun parseGroupNode(
        obj: JsonObject,
        elementNames: Map<Int, String>,
        used: MutableSet<Int>,
        state: ElementsPanelState,
        path: List<Int>
    ): DefaultMutableTreeNode {
        val name = obj.get("name")?.asString ?: ""
        val groupNode = DefaultMutableTreeNode(ElementTreeItem(ElementNodeKind.GROUP, name, groupPath = path))
        val children = obj.getAsJsonArray("children")
        children?.forEachIndexed { idx, child ->
            when {
                child.isJsonObject -> {
                    groupNode.add(
                        parseGroupNode(child.asJsonObject, elementNames, used, state, path + idx)
                    )
                }
                child.isJsonPrimitive && child.asJsonPrimitive.isNumber -> {
                    val index = child.asInt
                    val node = createElementNode(index, elementNames, used, state)
                    if (node != null) {
                        groupNode.add(node)
                    }
                }
            }
        }
        return groupNode
    }

    private fun createElementNode(
        index: Int,
        elementNames: Map<Int, String>,
        used: MutableSet<Int>,
        state: ElementsPanelState
    ): DefaultMutableTreeNode? {
        if (index < 0 || !elementNames.containsKey(index)) return null
        val name = elementNames[index] ?: ""
        val item = ElementTreeItem(ElementNodeKind.ELEMENT, name, index)
        val node = DefaultMutableTreeNode(item)
        state.elementNodes[index] = node
        used.add(index)
        return node
    }

    private fun updateSelectionFromViewer(indices: List<Int>, state: ElementsPanelState) {
        state.suppressSelectionSync = true
        try {
            val paths = indices.mapNotNull { index ->
                state.elementNodes[index]?.let { TreePath(it.path) }
            }
            state.tree.selectionPaths = if (paths.isEmpty()) null else paths.toTypedArray()
            if (paths.isNotEmpty()) {
                state.tree.scrollPathToVisible(paths.first())
            }
            updateElementCountLabel(state, selectedCount = paths.size)
        } finally {
            state.suppressSelectionSync = false
        }
    }

    private fun updateElementCountLabel(state: ElementsPanelState, selectedCount: Int) {
        val total = state.totalElements
        state.countLabel.text = "${selectedCount} / ${total}"
    }

    private fun showGroupContextMenu(
        project: Project,
        viewerService: ModelViewerService,
        state: ElementsPanelState,
        item: ElementTreeItem,
        component: Component,
        x: Int,
        y: Int
    ) {
        val groupPath = item.groupPath ?: return
        val menu = JPopupMenu()
        val renameItem = JMenuItem("Rename group")
        renameItem.addActionListener {
            renameGroup(project, viewerService, groupPath)
        }
        val assignItem = JMenuItem("Assign selected elements")
        assignItem.addActionListener {
            val selected = collectSelectedElementIndices(state.tree)
                .ifEmpty { viewerService.getSelectedIndices() }
            if (selected.isEmpty()) {
                Messages.showInfoMessage(project, "No selected elements.", "Assign Elements")
                return@addActionListener
            }
            assignSelectedElementsToGroup(project, viewerService, groupPath, selected)
        }
        menu.add(renameItem)
        menu.add(assignItem)
        menu.show(component, x, y)
    }

    private fun collectSelectedElementIndices(tree: Tree): List<Int> {
        return tree.selectionPaths
            ?.mapNotNull { path ->
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val item = node?.userObject as? ElementTreeItem
                item?.takeIf { it.kind == ElementNodeKind.ELEMENT }?.index
            }
            ?.distinct()
            ?: emptyList()
    }

    private fun renameGroup(project: Project, viewerService: ModelViewerService, groupPath: List<Int>) {
        updateJsonDocument(project, viewerService, "Rename Group") { root ->
            val groups = ensureGroupsArray(root)
            val groupObj = findGroupByPath(groups, groupPath) ?: return@updateJsonDocument false
            val currentName = groupObj.get("name")?.asString ?: ""
            val next = Messages.showInputDialog(
                project,
                "Group name",
                "Rename Group",
                null,
                currentName,
                null
            ) ?: return@updateJsonDocument false
            groupObj.addProperty("name", next)
            true
        }
    }

    private fun assignSelectedElementsToGroup(
        project: Project,
        viewerService: ModelViewerService,
        groupPath: List<Int>,
        selected: List<Int>
    ) {
        val indices = selected.filter { it >= 0 }
        if (indices.isEmpty()) return
        updateJsonDocument(project, viewerService, "Assign Elements") { root ->
            val groups = ensureGroupsArray(root)
            val groupObj = findGroupByPath(groups, groupPath) ?: return@updateJsonDocument false
            val indexSet = indices.toSet()
            removeIndicesFromArray(groups, indexSet)
            val children = groupObj.getAsJsonArray("children") ?: JsonArray().also {
                groupObj.add("children", it)
            }
            indices.forEach { idx ->
                val exists = children.any { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asInt == idx }
                if (!exists) {
                    children.add(JsonPrimitive(idx))
                }
            }
            true
        }
    }

    private fun updateJsonDocument(
        project: Project,
        viewerService: ModelViewerService,
        title: String,
        mutator: (JsonObject) -> Boolean
    ) {
        val activeFile = viewerService.getActiveModelFile()
        if (activeFile?.extension != "json") {
            Messages.showInfoMessage(project, "No active JSON document.", title)
            return
        }
        val document = viewerService.getActiveModelEditor()?.document
            ?: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(activeFile)
            ?: run {
                Messages.showInfoMessage(project, "No active JSON document.", title)
                return
            }
        val root = parseRootObject(document.text) ?: run {
            Messages.showInfoMessage(project, "Unable to parse JSON.", title)
            return
        }
        val changed = mutator(root)
        if (!changed) return
        val gson = GsonBuilder().setPrettyPrinting().create()
        val updated = gson.toJson(root)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(updated)
        }
    }

    private fun ensureGroupsArray(root: JsonObject): JsonArray {
        val existing = root.getAsJsonArray("groups")
        if (existing != null) return existing
        val created = JsonArray()
        root.add("groups", created)
        return created
    }

    private fun findGroupByPath(groups: JsonArray, path: List<Int>): JsonObject? {
        var currentArray = groups
        var currentObject: JsonObject? = null
        path.forEachIndexed { idx, value ->
            if (value < 0 || value >= currentArray.size()) {
                return null
            }
            val element = currentArray[value]
            if (!element.isJsonObject) {
                return null
            }
            currentObject = element.asJsonObject
            if (idx < path.lastIndex) {
                currentArray = currentObject?.getAsJsonArray("children") ?: return null
            }
        }
        return currentObject
    }

    private fun removeIndicesFromArray(array: JsonArray, indices: Set<Int>) {
        for (i in array.size() - 1 downTo 0) {
            val element = array[i]
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> {
                    if (indices.contains(element.asInt)) {
                        array.remove(i)
                    }
                }
                element.isJsonObject -> {
                    val children = element.asJsonObject.getAsJsonArray("children")
                    if (children != null) {
                        removeIndicesFromArray(children, indices)
                    }
                }
            }
        }
    }

    private fun addEmptyGroup(
        project: Project,
        viewerService: ModelViewerService,
        onUpdated: () -> Unit
    ) {
        val activeFile = viewerService.getActiveModelFile()
        if (activeFile?.extension != "json") {
            Messages.showInfoMessage(project, "No active JSON document.", "Add Group")
            return
        }
        val editor = viewerService.getActiveModelEditor()
        val document = editor?.document
            ?: activeFile.let {
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)
            }
        if (document == null) {
            Messages.showInfoMessage(project, "No active JSON document.", "Add Group")
            return
        }
        val updated = insertGroupIntoJson(document.text)
        if (updated == null) {
            Messages.showInfoMessage(project, "Unable to insert a groups array.", "Add Group")
            return
        }
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            document.setText(updated)
        }
        onUpdated()
    }

    private fun insertGroupIntoJson(text: String): String? {
        val groupsRange = findJsonArrayRange(text, "groups")
        return if (groupsRange != null) {
            insertIntoArray(text, groupsRange)
        } else {
            insertGroupsProperty(text)
        }
    }

    private fun buildGroupJson(indent: String): String {
        val fieldIndent = indent + "  "
        return "{\n" +
            "$fieldIndent\"name\": \"\",\n" +
            "$fieldIndent\"origin\": [0, 0, 0],\n" +
            "$fieldIndent\"color\": 0,\n" +
            "$fieldIndent\"children\": []\n" +
            "$indent}"
    }

    private fun insertIntoArray(text: String, range: IntRange): String {
        val arrayStart = range.first
        val arrayEnd = range.last
        val content = text.substring(arrayStart + 1, arrayEnd)
        val hasItems = content.any { !it.isWhitespace() }
        val itemIndent = findArrayItemIndent(text, arrayStart, arrayEnd)
        val payload = buildGroupJson(itemIndent)
        val insertText = if (hasItems) {
            ",\n$itemIndent$payload\n"
        } else {
            "\n$itemIndent$payload\n"
        }
        return text.substring(0, arrayEnd) + insertText + text.substring(arrayEnd)
    }

    private fun insertGroupsProperty(text: String): String? {
        val insertPos = text.lastIndexOf('}')
        if (insertPos <= 0) return null
        val baseIndent = findLineIndent(text, insertPos)
        val propertyIndent = baseIndent + "  "
        val payload = buildGroupJson(propertyIndent)
        val trimmed = text.substring(0, insertPos).trimEnd()
        val needsComma = !trimmed.endsWith("{")
        val prefix = if (needsComma) ",\n" else "\n"
        val propertyBlock =
            "\"groups\": [\n$propertyIndent$payload\n$baseIndent]"
        return text.substring(0, insertPos) +
            prefix +
            baseIndent +
            propertyBlock +
            "\n" +
            text.substring(insertPos)
    }

    private fun findJsonArrayRange(text: String, key: String): IntRange? {
        val keyToken = "\"$key\""
        var startIdx = text.indexOf(keyToken)
        while (startIdx >= 0) {
            var i = startIdx + keyToken.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != ':') {
                startIdx = text.indexOf(keyToken, startIdx + 1)
                continue
            }
            i++
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '[') {
                startIdx = text.indexOf(keyToken, startIdx + 1)
                continue
            }
            val arrayStart = i
            var depth = 0
            var inString = false
            var escaped = false
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
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                return arrayStart..i
                            }
                        }
                    }
                }
                i++
            }
            return null
        }
        return null
    }

    private fun findArrayItemIndent(text: String, arrayStart: Int, arrayEnd: Int): String {
        var i = arrayStart + 1
        var lastLineStart = -1
        while (i < arrayEnd) {
            val ch = text[i]
            if (ch == '\n') {
                lastLineStart = i + 1
            }
            if (!ch.isWhitespace()) {
                if (lastLineStart >= 0) {
                    return text.substring(lastLineStart, i)
                }
                break
            }
            i++
        }
        val baseIndent = findLineIndent(text, arrayStart)
        return baseIndent + "  "
    }

    private fun findLineIndent(text: String, index: Int): String {
        val lineStart = text.lastIndexOf('\n', index).let { if (it == -1) 0 else it + 1 }
        var i = lineStart
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
            i++
        }
        return text.substring(lineStart, i)
    }

    private fun createDraggableTabbedPane(): JTabbedPane {
        val tabbedPane = JBTabbedPane()
        tabbedPane.tabPlacement = JTabbedPane.TOP
        tabbedPane.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        tabbedPane.isOpaque = true
        var dragIndex = -1
        tabbedPane.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    dragIndex = tabbedPane.indexAtLocation(e.x, e.y)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                dragIndex = -1
            }
        })
        tabbedPane.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (dragIndex < 0) return
                val targetIndex = tabbedPane.indexAtLocation(e.x, e.y)
                if (targetIndex >= 0 && targetIndex != dragIndex) {
                    moveTab(tabbedPane, dragIndex, targetIndex)
                    dragIndex = targetIndex
                }
            }
        })
        return tabbedPane
    }

    private fun moveTab(tabbedPane: JTabbedPane, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val component = tabbedPane.getComponentAt(fromIndex)
        val title = tabbedPane.getTitleAt(fromIndex)
        val icon = tabbedPane.getIconAt(fromIndex)
        val tip = tabbedPane.getToolTipTextAt(fromIndex)
        val enabled = tabbedPane.isEnabledAt(fromIndex)
        val tabComponent = tabbedPane.getTabComponentAt(fromIndex)

        tabbedPane.removeTabAt(fromIndex)
        tabbedPane.insertTab(title, icon, component, tip, toIndex)
        tabbedPane.setEnabledAt(toIndex, enabled)
        if (tabComponent != null) {
            tabbedPane.setTabComponentAt(toIndex, tabComponent)
        }
        tabbedPane.selectedIndex = toIndex
    }

    private class TexturePreviewLayer(size: Int) : JLayeredPane() {
        private val previewLabel = JBLabel()
        private val animationLabel = JBLabel(AllIcons.Actions.Refresh)
        private val previewSize = size

        init {
            isOpaque = false
            preferredSize = JBUI.size(previewSize, previewSize)
            minimumSize = JBUI.size(previewSize, previewSize)
            maximumSize = JBUI.size(previewSize, previewSize)
            previewLabel.horizontalAlignment = SwingConstants.CENTER
            previewLabel.verticalAlignment = SwingConstants.CENTER
            animationLabel.isVisible = false
            add(previewLabel, JLayeredPane.DEFAULT_LAYER)
            add(animationLabel, JLayeredPane.PALETTE_LAYER)
        }

        fun update(icon: Icon?, animated: Boolean) {
            previewLabel.icon = icon
            animationLabel.isVisible = animated
        }

        override fun doLayout() {
            previewLabel.setBounds(0, 0, width, height)
            val badgeIcon = animationLabel.icon
            val badgeW = badgeIcon?.iconWidth ?: 0
            val badgeH = badgeIcon?.iconHeight ?: 0
            val padding = JBUI.scale(2)
            val x = max(padding, width - badgeW - padding)
            val y = max(padding, height - badgeH - padding)
            animationLabel.setBounds(x, y, badgeW, badgeH)
        }
    }

    private inner class TextureListRenderer(
        private val state: TexturesPanelState,
        private val list: JBList<TextureEntry>
    ) : ListCellRenderer<TextureEntry> {
        private val panel = JPanel(BorderLayout())
        private val previewLayer = TexturePreviewLayer(JBUI.scale(TEXTURE_PREVIEW_SIZE))
        private val keyLabel = JBLabel()
        private val nameLabel = JBLabel()
        private val metaLabel = JBLabel()
        private val textPanel = JPanel()
        private val contentPanel = JPanel(BorderLayout())

        init {
            panel.border = JBUI.Borders.empty(4, 6)
            panel.add(previewLayer, BorderLayout.WEST)
            previewLayer.border = JBUI.Borders.emptyRight(8)

            keyLabel.foreground = JBColor(0x9fa3a8, 0x9fa3a8)
            keyLabel.border = JBUI.Borders.emptyRight(8)
            metaLabel.foreground = JBColor.GRAY

            textPanel.layout = BoxLayout(textPanel, BoxLayout.Y_AXIS)
            textPanel.isOpaque = false
            textPanel.add(nameLabel)
            textPanel.add(metaLabel)

            contentPanel.isOpaque = false
            contentPanel.add(keyLabel, BorderLayout.WEST)
            contentPanel.add(textPanel, BorderLayout.CENTER)
            panel.add(contentPanel, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out TextureEntry>,
            value: TextureEntry?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            panel.background = if (isSelected) JBColor(0x3b3f46, 0x3b3f46) else list.background

            keyLabel.text = value?.key.orEmpty()
            nameLabel.text = value?.displayName.orEmpty()
            metaLabel.text = value?.resolvedValue.orEmpty()

            val icon = value?.let { resolveTexturePreviewIcon(it, state, list) } ?: AllIcons.FileTypes.Image
            val animated = value?.textureFile?.let { isTextureAnimated(state, it) } == true
            previewLayer.update(icon, animated)
            return panel
        }
    }

    private class ElementsTreeRenderer(private val state: ElementsPanelState) : TreeCellRenderer {
        private val panel = JPanel(BorderLayout())
        private val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        private val nameLabel = JBLabel()
        private val metaLabel = JBLabel()
        private val eyeLabel = JBLabel(AllIcons.General.InspectionsEye)

        init {
            panel.border = JBUI.Borders.empty(4, 6)
            leftPanel.isOpaque = false
            metaLabel.foreground = JBColor.GRAY
            leftPanel.add(nameLabel)
            leftPanel.add(metaLabel)
            panel.add(leftPanel, BorderLayout.WEST)
            panel.add(eyeLabel, BorderLayout.EAST)
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val node = value as? DefaultMutableTreeNode
            val item = node?.userObject as? ElementTreeItem
            val isElement = item?.kind == ElementNodeKind.ELEMENT
            val isGroup = item?.kind == ElementNodeKind.GROUP
            val name = item?.name ?: ""

            panel.background = if (selected) JBColor(0x3b3f46, 0x3b3f46) else tree.background
            panel.isOpaque = true
            nameLabel.icon = null
            nameLabel.text = when {
                name.isNotBlank() -> name
                isGroup -> "(unnamed)"
                else -> ""
            }
            metaLabel.text = if (isElement && item?.index != null) "#${item.index}" else ""

            if (isElement && item?.index != null) {
                val hidden = state.hiddenElements.contains(item.index)
                eyeLabel.isVisible = true
                eyeLabel.isEnabled = !hidden
                eyeLabel.icon = AllIcons.General.InspectionsEye
            } else {
                eyeLabel.isVisible = false
            }

            return panel
        }
    }

    private fun dumpUiManagerKeysOnce() {
        if (!uiManagerDumped) {
            uiManagerDumped = true
            val defaults = UIManager.getDefaults()
            val keys = mutableListOf<String>()
            val enumeration = defaults.keys()
            while (enumeration.hasMoreElements()) {
                keys.add(enumeration.nextElement().toString())
            }
            keys.sort()
            keys.forEach { key ->
                val value = defaults[key]
                val rendered = when (value) {
                    is Color ->
                        String.format("#%02X%02X%02X", value.red, value.green, value.blue)
                    else -> value?.toString() ?: "null"
                }
                println("UIManager key: $key = $rendered (${value?.javaClass?.name ?: "null"})")
            }
        }
    }

    companion object {
        @Volatile
        private var uiManagerDumped = false
    }
}
