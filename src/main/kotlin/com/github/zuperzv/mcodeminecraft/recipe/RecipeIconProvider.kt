package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.ResourceLocation
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

class RecipeIconProvider(private val catalog: AssetCatalog) {
    private val iconCache = ConcurrentHashMap<String, IconState>()
    private val callbacks = ConcurrentHashMap<String, MutableList<(Icon?) -> Unit>>()
    private val executor = AppExecutorUtil.getAppExecutorService()

    fun getIcon(id: String, onReady: ((Icon?) -> Unit)? = null): Icon? {
        val normalized = id.lowercase(Locale.US)
        val existing = iconCache[normalized]
        if (existing != null) {
            if (existing.loading && onReady != null) {
                registerCallback(normalized, onReady)
            }
            return existing.icon
        }
        if (onReady != null) {
            registerCallback(normalized, onReady)
        }
        iconCache[normalized] = IconState(placeholder, loading = true)
        executor.execute {
            val icon = resolveIcon(normalized)
            iconCache[normalized] = IconState(icon, loading = false)
            val pending = callbacks.remove(normalized) ?: emptyList()
            if (pending.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    pending.forEach { it(icon) }
                }
            }
        }
        return placeholder
    }

    private fun resolveIcon(id: String): Icon? {
        val model = catalog.resolveModel(id) ?: return null
        val textures = catalog.resolveTextures(model)
        val texture = chooseTexture(textures) ?: return null
        val image = loadTexture(texture) ?: return null
        val scaled = scale(image, ICON_SIZE, ICON_SIZE)
        return ImageIcon(scaled)
    }

    private fun chooseTexture(textures: Map<String, ResourceLocation>): ResourceLocation? {
        return textures["layer0"]
            ?: textures["all"]
            ?: textures.values.firstOrNull()
    }

    private fun loadTexture(texture: ResourceLocation): BufferedImage? {
        return catalog.openTextureStream(texture)?.use { stream ->
            ImageIO.read(stream)
        }
    }

    private fun scale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val resized = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = resized.createGraphics()
        applyQualityHints(g)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return resized
    }

    private fun applyQualityHints(g: Graphics2D) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    }

    companion object {
        private const val ICON_SIZE = 32
        private val placeholder: Icon = AllIcons.FileTypes.Image
    }

    private data class IconState(
        val icon: Icon?,
        val loading: Boolean
    )

    private fun registerCallback(id: String, onReady: (Icon?) -> Unit) {
        callbacks.compute(id) { _, existing ->
            val list = existing ?: mutableListOf()
            list.add(onReady)
            list
        }
    }
}
