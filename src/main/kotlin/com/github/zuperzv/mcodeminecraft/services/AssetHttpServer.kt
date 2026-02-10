package com.github.zuperzv.mcodeminecraft.services

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

object AssetServer {
    private var server: HttpServer? = null
    private val roots = mutableListOf<File>()
    private val rootsLock = Any()
    private var port: Int = 6192

    fun start(base: File, port: Int = 6192) {
        addRoot(base)
        if (server != null) return
        this.port = port

        server = HttpServer.create(InetSocketAddress(port), 0)
        server!!.createContext("/assets") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/assets/")
            val rootSnapshot = synchronized(rootsLock) { roots.toList() }
            val file = rootSnapshot
                .asSequence()
                .map { File(it, path) }
                .firstOrNull { it.exists() && it.isFile }
            val extension = file?.extension?.lowercase() ?: ""
            val mime = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "json" -> "application/json"
                "js" -> "application/javascript"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }

            exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
            exchange.responseHeaders.add("Content-Type", mime)

            if (file != null) {
                val bytes = Files.readAllBytes(file.toPath())
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val resource = AssetServer::class.java.getResourceAsStream("/assets/$path")
                if (resource != null) {
                    val bytes = resource.readBytes()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
            exchange.close()
        }

        server!!.executor = null
        server!!.start()
        println("AssetServer running at http://localhost:$port/assets/")
    }

    fun addRoot(base: File) {
        val root = try {
            base.canonicalFile
        } catch (_: Exception) {
            base
        }
        if (!root.exists() || !root.isDirectory) {
            return
        }
        synchronized(rootsLock) {
            if (roots.none { it.path.equals(root.path, true) }) {
                roots.add(root)
                println("AssetServer root added: ${root.path}")
            }
        }
    }

    fun mcTextureToUrl(mcPath: String, port: Int = 6192): String {
        val parts = mcPath.replace("#", "").split(":")
        return "http://localhost:$port/assets/${parts[0]}/textures/block/${parts[1]}.png"
    }
}
