package com.github.zuperzv.mcodeminecraft.assets

data class ResourceLocation private constructor(
    val namespace: String,
    val path: String
) {
    companion object {
        fun parse(raw: String, defaultNamespace: String = "minecraft"): ResourceLocation {
            val trimmed = raw.trim()
            val parts = trimmed.split(":", limit = 2)
            val namespace = (if (parts.size == 2) parts[0] else defaultNamespace)
                .trim()
                .lowercase()
            val path = (if (parts.size == 2) parts[1] else parts[0])
                .trim()
                .trimStart('/')
                .lowercase()
            return ResourceLocation(namespace, path)
        }

        fun of(namespace: String, path: String): ResourceLocation {
            return ResourceLocation(namespace.trim().lowercase(), path.trim().trimStart('/').lowercase())
        }
    }

    override fun toString(): String {
        return "$namespace:$path"
    }

    fun toModelEntryPath(): String {
        return "assets/$namespace/models/$path.json"
    }

    fun toTextureEntryPath(): String {
        return "assets/$namespace/textures/$path.png"
    }
}
