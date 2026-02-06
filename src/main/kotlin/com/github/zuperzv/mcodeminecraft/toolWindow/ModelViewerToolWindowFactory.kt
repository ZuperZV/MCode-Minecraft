package com.github.zuperzv.mcodeminecraft.toolWindow

import com.github.zuperzv.mcodeminecraft.services.ModelViewerService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser

class ModelViewerToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        project.getService(com.github.zuperzv.mcodeminecraft.preview.ModelAutoPreviewService::class.java)

        val browser = JBCefBrowser()
        project.getService(ModelViewerService::class.java).setBrowser(browser)

        val content = ContentFactory.getInstance()
            .createContent(browser.component, "", false)
        toolWindow.contentManager.addContent(content)

        browser.loadHTML(viewerHtml())

        println("ModelViewerToolWindow CREATED")
    }


    private fun viewerHtml() = """
    <html>
    <body style="margin:0; overflow:hidden;">
    <canvas id="c"></canvas>
    
    <script src="https://cdn.jsdelivr.net/npm/three@0.160/build/three.min.js"></script>
    
    <script>
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

    const ambient = new THREE.AmbientLight(0x404040)
    scene.add(ambient)

    function clearScene(){
        while(scene.children.length > 2){ // behold lys
            scene.remove(scene.children[2])
        }
    }

    function loadModel(model){
        clearScene()
        
        const center = new THREE.Vector3(8,8,8) // centrer modellen

        model.elements.forEach(el => {
            const sx = el.to[0] - el.from[0]
            const sy = el.to[1] - el.from[1]
            const sz = el.to[2] - el.from[2]

            const geometry = new THREE.BoxGeometry(sx, sy, sz)
            const material = new THREE.MeshNormalMaterial()
            const mesh = new THREE.Mesh(geometry, material)

            mesh.position.set(
                el.from[0] + sx/2 - center.x,
                el.from[1] + sy/2 - center.y,
                el.from[2] + sz/2 - center.z
            )

            scene.add(mesh)
        })
    }

    window.loadModel = loadModel

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
