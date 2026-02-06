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

        function rotateUV(coords, rotation) {
            rotation = ((rotation||0)%360 + 360)%360;
            switch(rotation) {
                case 90:
                    return [coords[3], coords[0], coords[1], coords[2]];
                case 180:
                    return [coords[2], coords[3], coords[0], coords[1]];
                case 270:
                    return [coords[1], coords[2], coords[3], coords[0]];
                default: // 0
                    return coords;
            }
        }

        const loader = new THREE.TextureLoader()
        let textures = {}

        function resolveTexture(texString){
            texString = texString.replace("#","")
            if(texString.includes(":")){
                const [namespace, path] = texString.split(":")
                return "http://localhost:$assetPort/assets/" + namespace + "/textures/" + path + ".png"
            }
            return "http://localhost:$assetPort/assets/minecraft/textures/" + texString + ".png"
        }

        function applyFaceUV(geometry, faceIndex, uv, texW=16, texH=16, rotation=0, faceName="") {
            if (!uv) return;
        
            let [u1,v1,u2,v2] = uv;
            u1/=texW; v1/=texH; u2/=texW; v2/=texH;
        
            // Lav standard coords
            let coords = [[u2,1-v2],[u1,1-v2],[u1,1-v1],[u2,1-v1]];
        
            // Roter coords efter rotation
            coords = rotateUV(coords, rotation);
        
            // Spejling for visse faces
            if(faceName === "west" || faceName === "north"){
                coords = coords.map(c => [1-c[0], c[1]]);
            }
        
            const uvAttr = geometry.attributes.uv;
            const idx = faceIndex*4;
            uvAttr.setXY(idx+0, coords[0][0], coords[0][1]);
            uvAttr.setXY(idx+1, coords[1][0], coords[1][1]);
            uvAttr.setXY(idx+2, coords[2][0], coords[2][1]);
            uvAttr.setXY(idx+3, coords[3][0], coords[3][1]);
            uvAttr.needsUpdate = true;
        }



        window.pendingModelJson = null

        window.loadModel = function(model){
            clearScene()
            textures = {}

            if(model.textures){
                Object.keys(model.textures).forEach(key=>{
                    const texPath = resolveTexture(model.textures[key])
                    textures[key] = loader.load(texPath,
                      ()=>console.log("Texture loaded:", texPath),
                      undefined,
                      e=>console.error("Texture failed:", texPath,e)
                    )
                    textures[key].magFilter = THREE.NearestFilter
                    textures[key].minFilter = THREE.NearestFilter
                })
            }

            const center = new THREE.Vector3(8,8,8)

            model.elements?.forEach(el=>{
                const sx = el.to[0]-el.from[0]
                const sy = el.to[1]-el.from[1]
                const sz = el.to[2]-el.from[2]

                const geometry = new THREE.BoxGeometry(sx,sy,sz)
                const materials = []
                const faceOrder=["east","west","up","down","south","north"]

                faceOrder.forEach((face,i)=>{
                    const faceDef = el.faces?.[face]
                    if(faceDef && faceDef.texture){
                        const texKey = faceDef.texture.replace("#","")
                        const tex = textures[texKey]
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