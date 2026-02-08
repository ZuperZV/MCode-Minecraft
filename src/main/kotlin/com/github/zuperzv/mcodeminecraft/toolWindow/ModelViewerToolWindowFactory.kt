package com.github.zuperzv.mcodeminecraft.toolWindow

import com.github.zuperzv.mcodeminecraft.services.AssetServer
import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import org.intellij.lang.annotations.Language
import java.io.File

class ModelViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        project.getService(com.github.zuperzv.mcodeminecraft.preview.ModelAutoPreviewService::class.java)

        val browser = JBCefBrowser()
        project.getService(ModelViewerService::class.java).setBrowser(browser)

        val content = ContentFactory.getInstance()
            .createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)

        val projectRoot = project.basePath!!.replace("\\", "/")

        val assetsFolder = File(project.basePath, "src/main/resources/assets")
        val assetServerPort = 6192
        AssetServer.start(assetsFolder, assetServerPort)

        browser.loadHTML(viewerHtml(projectRoot, assetServerPort), "http://localhost/")
        browser.openDevtools()

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

        const light = new THREE.DirectionalLight(0xffffff, 1)
        light.position.set(50,50,50)
        scene.add(light)
        scene.add(new THREE.AmbientLight(0x404040))

        function clearScene(){
            while(scene.children.length > 2){
                scene.remove(scene.children[2])
            }
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

            const frameSize = img.width
            const frameCount = Math.floor(img.height / frameSize)
            if(frameCount <= 1) return

            let frametime = 1
            let interpolate = false
            try{
                const metaRes = await fetch(texPath + ".mcmeta")
                if(metaRes.ok){
                    const metaJson = await metaRes.json()
                    const animation = metaJson?.animation || {}
                    if(Number.isFinite(animation.frametime) && animation.frametime > 0){
                        frametime = animation.frametime
                    }
                    interpolate = animation.interpolate === true
                }
            }catch(e){
                console.warn("Animation meta load failed:", texPath, e)
            }

            const canvas = document.createElement("canvas")
            canvas.width = frameSize
            canvas.height = frameSize
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

            animatedTextures.set(texPath, {
                texture: tex,
                image: img,
                ctx,
                frameCount,
                frameTimeMs: frametime * TICK_MS,
                interpolate,
                frameSize,
                lastFrame: -1,
                lastBlend: -1
            })
        }

        function updateAnimatedTextures(nowMs){
            animatedTextures.forEach(entry=>{
                const elapsed = nowMs % (entry.frameTimeMs * entry.frameCount)
                const frameProgress = elapsed / entry.frameTimeMs
                const baseIndex = Math.floor(frameProgress)
                const frameIndex = baseIndex % entry.frameCount
                const blend = entry.interpolate ? (frameProgress - baseIndex) : 0

                if(frameIndex === entry.lastFrame && blend === entry.lastBlend){
                    return
                }

                const ctx = entry.ctx
                const srcY = frameIndex * entry.frameSize
                ctx.clearRect(0, 0, entry.frameSize, entry.frameSize)
                ctx.globalAlpha = 1
                ctx.drawImage(
                    entry.image,
                    0, srcY, entry.frameSize, entry.frameSize,
                    0, 0, entry.frameSize, entry.frameSize
                )
                if(entry.interpolate && blend > 0){
                    const nextIndex = (frameIndex + 1) % entry.frameCount
                    const nextY = nextIndex * entry.frameSize
                    ctx.globalAlpha = blend
                    ctx.drawImage(
                        entry.image,
                        0, nextY, entry.frameSize, entry.frameSize,
                        0, 0, entry.frameSize, entry.frameSize
                    )
                    ctx.globalAlpha = 1
                }

                entry.texture.needsUpdate = true
                entry.lastFrame = frameIndex
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
            const levels = [0.35, 0.5, 0.7, 0.95]
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



        window.pendingModelJson = null

        window.loadModel = async function(model){
            clearScene()
            textures = {}
            animatedTextures = new Map()

            const resolvedModel = await resolveModel(model)
            const occupancy = new Set()

            resolvedModel.elements?.forEach(el=>{
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

            const center = new THREE.Vector3(8,8,8)

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
            })
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
            renderer.render(scene, camera)
        }
        animate()

        window.addEventListener('resize', ()=>{
            renderer.setSize(Math.floor(window.innerWidth), Math.floor(window.innerHeight))
            camera.aspect = window.innerWidth / window.innerHeight
            camera.updateProjectionMatrix()
        })
        </script>
        </body>
        </html>
    """.trimIndent()
}
