package com.github.zuperzv.mcodeminecraft.services

import com.intellij.openapi.components.Service
import com.intellij.ui.jcef.JBCefBrowser

@Service(Service.Level.PROJECT)
class ModelViewerService {

    private var browser: JBCefBrowser? = null
    private var pendingJson: String? = null

    fun setBrowser(b: JBCefBrowser) {
        println("Browser registered")
        browser = b

        pendingJson?.let {
            loadModel(it)
            pendingJson = null
        }
    }

    fun loadModel(json: String) {

        println("loadModel called")

        if (browser == null) {
            println("Browser not ready -> queue")
            pendingJson = json
            return
        }

        val jsCode = "loadModel(JSON.parse(`$json`));"

        browser!!.cefBrowser.executeJavaScript(
            jsCode,
            browser!!.cefBrowser.url,
            0
        )
    }
}