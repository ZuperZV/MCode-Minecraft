package com.github.zuperzv.mcodeminecraft.toolWindow

import com.github.zuperzv.mcodeminecraft.preview.ModelAutoPreviewService
import com.github.zuperzv.mcodeminecraft.services.AssetServer
import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import org.intellij.lang.annotations.Language
import java.io.File
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

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

    private val logger = Logger.getInstance(ModelViewerToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        dumpUiManagerKeysOnce()

        project.getService(ModelAutoPreviewService::class.java)

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
                    val inset = JBUI.scale(30)
                    val rectWidth = width - inset * 2
                    val rectHeight = height - inset * 2
                    g2.color = SELECTED_BACKGROUND_COLOR
                    g2.fillRoundRect(inset, inset, rectWidth, rectHeight, arc, arc)
                    g2.color = JBColor.border()
                    g2.drawRoundRect(inset, inset, rectWidth - 1, rectHeight - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }
        viewerContainer.isOpaque = false
        viewerContainer.border = JBUI.Borders.empty(52)
        viewerContainer.add(viewerPanel, BorderLayout.CENTER)

        val browser = if (JBCefApp.isSupported()) {
            try {
                JBCefBrowser().also {
                    project.getService(ModelViewerService::class.java).setBrowser(it)
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

        val controlsPanel = JPanel()
        controlsPanel.layout = BoxLayout(controlsPanel, BoxLayout.Y_AXIS)
        controlsPanel.add(JBLabel("Model controls"))
        controlsPanel.add(Box.createVerticalStrut(6))

        val pathRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        pathRow.add(JBLabel("Model path: "))
        pathRow.add(JBTextField().apply { columns = 22 })
        controlsPanel.add(pathRow)
        controlsPanel.add(Box.createVerticalStrut(6))

        val actionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        actionsRow.add(JButton("Load"))
        actionsRow.add(Box.createHorizontalStrut(6))
        actionsRow.add(JButton("Reset camera"))
        controlsPanel.add(actionsRow)
        controlsPanel.add(Box.createVerticalStrut(6))

        controlsPanel.add(JBCheckBox("Auto preview", true))
        controlsPanel.add(Box.createVerticalStrut(8))
        controlsPanel.add(JBLabel("Recent models"))
        val recentModels = JBList(
            listOf(
                "minecraft:block/stone",
                "minecraft:block/oak_log",
                "minecraft:item/diamond_sword"
            )
        )
        controlsPanel.add(
            JBScrollPane(recentModels).apply {
                preferredSize = Dimension(200, 140)
            }
        )

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
                        g.fillRect(0, 0, width, height)
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
            it.loadHTML(viewerHtml(projectRoot, assetServerPort), "http://localhost/")
            it.openDevtools()
        }

        println("ModelViewerToolWindow CREATED")
    }

    @Language("HTML")
    private fun viewerHtml(projectRoot: String, assetPort: Int) = """
        <html>
        <body style="margin:0; overflow:hidden;">
        <canvas id="c"></canvas>
        
        <script src="https://cdn.jsdelivr.net/npm/three@0.160/build/three.min.js"></script>
        
        <script>
        console.log("Viewer JS running")
        const PROJECT_ROOT = "$projectRoot"
        const ASSET_PORT = $assetPort
     
        const canvas = document.getElementById("c")
        const renderer = new THREE.WebGLRenderer({canvas, antialias:false, alpha:true})
        renderer.setSize(Math.floor(window.innerWidth), Math.floor(window.innerHeight))
        renderer.setPixelRatio(1) // Pixel-perfect

        const scene = new THREE.Scene()
        const camera = new THREE.PerspectiveCamera(70, window.innerWidth/window.innerHeight, 0.1, 1000)
        camera.position.set(40,40,40)
        camera.lookAt(0,0,0)
        const DEFAULT_ORBIT_TARGET = new THREE.Vector3(0,0,0)
        let orbitTarget = DEFAULT_ORBIT_TARGET.clone()
        let currentModelCenter = DEFAULT_ORBIT_TARGET.clone()
        let orbitRadius = camera.position.distanceTo(orbitTarget)
        let orbitTheta = Math.atan2(camera.position.z - orbitTarget.z, camera.position.x - orbitTarget.x)
        let orbitPhi = Math.acos((camera.position.y - orbitTarget.y) / orbitRadius)

        const light = new THREE.DirectionalLight(0xffffff, 1)
        light.position.set(50,50,50)
        scene.add(light)
        scene.add(new THREE.AmbientLight(0x212121))

        function clearScene(){
            while(scene.children.length > 2){
                scene.remove(scene.children[2])
            }
            pickableMeshes.length = 0
            if(hoverState.object){
                setMeshHighlight(hoverState.object, false)
                hoverState.object = null
            }
        }

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
        const originalMaterialColors = new WeakMap()
        const hoverState = { object: null }

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

        function setMeshHighlight(mesh, enabled){
            const mats = Array.isArray(mesh.material) ? mesh.material : [mesh.material]
            mats.forEach(mat=>{
                if(!mat || !mat.color) return
                let base = originalMaterialColors.get(mat)
                if(!base){
                    base = mat.color.clone()
                    originalMaterialColors.set(mat, base)
                }
                if(enabled){
                    mat.color.setHex(0xfff2a6)
                }else{
                    mat.color.copy(base)
                }
            })
        }

        function updateHover(){
            if(!hasPointer) return
            raycaster.setFromCamera(pointer, camera)
            const hits = raycaster.intersectObjects(pickableMeshes, false)
            const next = hits.length ? hits[0].object : null
            if(next === hoverState.object) return
            if(hoverState.object){
                setMeshHighlight(hoverState.object, false)
            }
            if(next){
                setMeshHighlight(next, true)
            }
            hoverState.object = next
        }
        
        window.pendingModelJson = null

        window.loadModel = async function(model, resetCamera=true){
            clearScene()
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

            resolvedModel.elements?.forEach(el=>{
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
                        materials.push(new THREE.MeshBasicMaterial({ map: tex, color: 0xffffff, vertexColors: true }))
                        applyFaceUV(geometry, i, faceDef.uv, tex?.image?.width||16, tex?.image?.height||16, faceDef.rotation, face)
                        applyFaceAOColors(geometry, i, face, shade, el, sx, sy, sz, occupancy)
                    }else{
                        materials.push(new THREE.MeshBasicMaterial({ color: 0xffffff, vertexColors: true }))
                        applyFaceAOColors(geometry, i, face, shade, el, sx, sy, sz, occupancy)
                    }
                })

                const mesh = new THREE.Mesh(geometry, materials)
                mesh.position.set(
                    el.from[0]+sx/2 - center.x,
                    el.from[1]+sy/2 - center.y,
                    el.from[2]+sz/2 - center.z
                )
                scene.add(mesh)
                pickableMeshes.push(mesh)
            })

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

        window.addEventListener('DOMContentLoaded', ()=>{
            if(window.pendingModelJson){
                loadModel(window.pendingModelJson)
                window.pendingModelJson = null
            }
            console.log("Viewer HTML ready")
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

        canvas.addEventListener("mousedown", (e)=>{
            if(e.button === 0){
                isDragging = true
            }else if(e.button === 2){
                isPanning = true
            }
            lastX = e.clientX
            lastY = e.clientY
        })

        window.addEventListener("mouseup", ()=>{
            isDragging = false
            isPanning = false
        })

        window.addEventListener("mousemove", (e)=>{
            const dx = e.clientX - lastX
            const dy = e.clientY - lastY
            lastX = e.clientX
            lastY = e.clientY
            const rect = canvas.getBoundingClientRect()
            pointer.x = ((e.clientX - rect.left) / rect.width) * 2 - 1
            pointer.y = -((e.clientY - rect.top) / rect.height) * 2 + 1
            hasPointer = true

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
                setMeshHighlight(hoverState.object, false)
                hoverState.object = null
            }
        })

        canvas.addEventListener("wheel", (e)=>{
            e.preventDefault()
            const ZOOM_SPEED = 0.002
            orbitRadius = Math.max(2, orbitRadius * (1 + e.deltaY * ZOOM_SPEED))
            updateCameraFromOrbit()
        }, { passive: false })

        canvas.addEventListener("contextmenu", (e)=>e.preventDefault())

        window.addEventListener('resize', ()=>{
            renderer.setSize(Math.floor(window.innerWidth), Math.floor(window.innerHeight))
            camera.aspect = window.innerWidth / window.innerHeight
            camera.updateProjectionMatrix()
        })
        </script>
        </body>
        </html>
    """.trimIndent()

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
