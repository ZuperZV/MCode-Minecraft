package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.ResourceLocation
import java.awt.image.BufferedImage
import java.util.Locale
import javax.imageio.ImageIO

class TextureResolver(private val catalog: AssetCatalog) {
    private val cache = LruCache<String, BufferedImage?>(MAX_CACHE_SIZE)
    private val locale = Locale.US

    fun getTexture(ref: String, defaultNamespace: String = "minecraft"): BufferedImage? {
        val location = ResourceLocation.parse(ref, defaultNamespace)
        return getTexture(location)
    }

    fun getTexture(location: ResourceLocation): BufferedImage? {
        val key = location.toString().lowercase(locale)
        if (cache.contains(key)) {
            return cache.get(key)
        }
        val image = catalog.openTextureStream(location)?.use { stream ->
            ImageIO.read(stream)
        }
        cache.put(key, image)
        return image
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        private const val MAX_CACHE_SIZE = 256
    }
}
