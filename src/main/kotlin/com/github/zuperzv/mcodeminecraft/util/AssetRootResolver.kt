package com.github.zuperzv.mcodeminecraft.util

import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

object AssetRootResolver {
    fun findAssetsRoot(file: VirtualFile): File? {
        var current = Paths.get(file.path).parent
        while (current != null) {
            if (current.fileName?.toString()?.equals("assets", true) == true) {
                val dir = current.toFile()
                return if (dir.isDirectory) dir else null
            }
            current = current.parent
        }
        return null
    }
}
