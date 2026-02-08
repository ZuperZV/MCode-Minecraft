package com.github.zuperzv.mcodeminecraft.services

import com.intellij.openapi.components.Service
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

@Service(Service.Level.PROJECT)
class ModelViewerService {

    private var browser: JBCefBrowser? = null
    private var pendingJson: String? = null
    private var pageReady = false

    fun setBrowser(b: JBCefBrowser) {
        println("Browser registered")
        browser = b

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
}
