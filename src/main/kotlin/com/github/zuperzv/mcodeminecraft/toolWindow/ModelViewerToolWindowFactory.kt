package com.github.zuperzv.mcodeminecraft.toolWindow

import com.github.zuperzv.mcodeminecraft.services.AssetServer
import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
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

    private fun viewerHtml(projectRoot: String, assetPort: Int) = """
    <html>
    <body style="margin:0; overflow:hidden;">
    <canvas id="c"></canvas>
    
    <script src="https://cdn.jsdelivr.net/npm/three@0.160/build/three.min.js"></script>
    
    <script>
    document.body.style.background = "#2b2b2b"
    console.log("Viewer JS running")

    const PROJECT_ROOT = "$projectRoot"
    const ASSET_PORT = $assetPort

    const canvas = document.getElementById("c")
    const renderer = new THREE.WebGLRenderer({canvas, antialias:true})
    renderer.setSize(document.body.clientWidth, document.body.clientHeight)

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

    const loader = new THREE.TextureLoader()
    let textures = {}

    function resolveTexture(texString){
        texString = texString.replace("#","")
        if(texString.includes(":")){
            const [namespace, path] = texString.split(":")
            return "http://localhost:6192/assets/" + namespace + "/textures/" + path + ".png"
        }
        return "http://localhost:6192/assets/minecraft/textures/" + texString + ".png"
    }

    function applyFaceUV(geometry, faceIndex, uv, texW, texH) {
        if (!uv) return

        const [u1, v1, u2, v2] = uv
        const x1 = u1 / texW
        const y1 = 1 - v2 / texH
        const x2 = u2 / texW
        const y2 = 1 - v1 / texH

        const uvAttr = geometry.attributes.uv
        const idx = faceIndex * 4

        uvAttr.setXY(idx + 0, x2, y2)
        uvAttr.setXY(idx + 1, x1, y2)
        uvAttr.setXY(idx + 2, x1, y1)
        uvAttr.setXY(idx + 3, x2, y1)

        uvAttr.needsUpdate = true
    }

    // Pending queue if JS not ready yet
    window.pendingModelJson = null

    window.loadModel = function(model){
        clearScene()
        textures = {}

        if(model.textures){
            Object.keys(model.textures).forEach(key=>{
                const texPath = resolveTexture(model.textures[key])
                textures[key] = loader.load(texPath,
                  () => console.log("Texture loaded:", texPath),
                  undefined,
                  e => console.error("Texture failed:", texPath, e)
                )
            })
        }

        const center = new THREE.Vector3(8,8,8)

        model.elements?.forEach(el => {
            const sx = el.to[0] - el.from[0]
            const sy = el.to[1] - el.from[1]
            const sz = el.to[2] - el.from[2]

            const geometry = new THREE.BoxGeometry(sx, sy, sz)
            const materials = []
            const faceOrder = ["east","west","up","down","south","north"]
            
            faceOrder.forEach((face, i)=>{
                const faceDef = el.faces?.[face]
                if(faceDef && faceDef.texture){
                    const texKey = faceDef.texture.replace("#","")
                    const tex = textures[texKey]
                    materials.push(new THREE.MeshLambertMaterial({ map: tex }))
                    applyFaceUV(
                        geometry,
                        i,
                        faceDef.uv,
                        tex?.image?.width || 16,
                        tex?.image?.height || 16
                    )
                } else {
                    materials.push(new THREE.MeshNormalMaterial())
                }
            })
            materials.push(new THREE.MeshNormalMaterial())

            const mesh = new THREE.Mesh(geometry, materials)
            mesh.position.set(
                el.from[0] + sx/2 - center.x,
                el.from[1] + sy/2 - center.y,
                el.from[2] + sz/2 - center.z
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
        renderer.setSize(document.body.clientWidth, document.body.clientHeight)
        camera.aspect = document.body.clientWidth / document.body.clientHeight
        camera.updateProjectionMatrix()
    })
    </script>
    </body>
    </html>
    """.trimIndent()
}