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
        const DEBUG_UV = true
        const modelCache = {}
        let textures = {}

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

        function getTextureForRef(texRef){
            if(!texRef) return null
            const cacheKey = texRef
            if(!textures[cacheKey]){
                const texPath = resolveTexture(texRef)
                textures[cacheKey] = loader.load(
                    texPath,
                    ()=>console.log("Texture loaded:", texPath),
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



        window.pendingModelJson = null

        window.loadModel = async function(model){
            clearScene()
            textures = {}

            const resolvedModel = await resolveModel(model)

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

                const geometry = new THREE.BoxGeometry(sx,sy,sz)
                console.log("[Geom] indexed", !!geometry.index, "uvCount", geometry.attributes?.uv?.count, "posCount", geometry.attributes?.position?.count)
                const materials = []
                const faceOrder=["east","west","up","down","south","north"]

                faceOrder.forEach((face,i)=>{
                    const faceDef = el.faces?.[face]
                    if(faceDef && faceDef.texture){
                        let tex = null
                        if(faceDef.texture.startsWith("#")){
                            const texKey = faceDef.texture.replace("#","")
                            tex = textures[texKey]
                        }else{
                            tex = getTextureForRef(faceDef.texture)
                        }
                        materials.push(new THREE.MeshLambertMaterial({ map: tex }))
                        applyFaceUV(geometry, i, faceDef.uv, tex?.image?.width||16, tex?.image?.height||16, faceDef.rotation, face)
                    }else{
                        materials.push(new THREE.MeshNormalMaterial())
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
