package com.github.zuperzv.mcodeminecraft.services

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

object AssetServer {
    private var server: HttpServer? = null

    fun start(base: File, port: Int = 6192) {
        if (server != null) return

        server = HttpServer.create(InetSocketAddress(port), 0)
        server!!.createContext("/assets") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/assets/")
            val file = File(base, path)

            if (file.exists() && file.isFile) {
                val mime = when (file.extension.lowercase()) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "json" -> "application/json"
                    "txt" -> "text/plain"
                    else -> "application/octet-stream"
                }

                exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
                exchange.responseHeaders.add("Content-Type", mime)

                val bytes = Files.readAllBytes(file.toPath())
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
            exchange.close()
        }

        server!!.executor = null
        server!!.start()
        println("AssetServer running at http://localhost:$port/assets/")
    }

    fun mcTextureToUrl(mcPath: String, port: Int = 6192): String {
        val parts = mcPath.replace("#", "").split(":")
        return "http://localhost:$port/assets/${parts[0]}/textures/block/${parts[1]}.png"
    }
}
