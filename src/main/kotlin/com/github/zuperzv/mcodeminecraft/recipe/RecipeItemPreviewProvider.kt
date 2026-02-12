package com.github.zuperzv.mcodeminecraft.recipe

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.render.InventoryItemRenderer
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.github.zuperzv.mcodeminecraft.render.LruCache
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.ImageIcon

class RecipeItemPreviewProvider(private val catalog: AssetCatalog) {
    private val iconCache = LruCache<String, IconState>(MAX_CACHE_SIZE)
    private val callbacks = ConcurrentHashMap<String, MutableList<(Icon?) -> Unit>>()
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("McItemPreview", 2)
    private val renderer = InventoryItemRenderer(catalog)
    private val fallbackProvider = RecipeIconProvider(catalog)
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(RecipeItemPreviewProvider::class.java)

    fun getIcon(id: String, onReady: ((Icon?) -> Unit)? = null): Icon? {
        val normalized = id.lowercase(Locale.US)
        val existing = if (iconCache.contains(normalized)) iconCache.get(normalized) else null
        if (existing != null) {
            if (existing.loading && onReady != null) {
                registerCallback(normalized, onReady)
            }
            return existing.icon
        }
        if (onReady != null) {
            registerCallback(normalized, onReady)
        }
        iconCache.put(normalized, IconState(placeholder, loading = true))
        executor.execute {
            val icon = resolveIcon(normalized)
            iconCache.put(normalized, IconState(icon, loading = false))
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
        logger.warn("RecipeItemPreviewProvider: render request for $id")
        val image = renderer.renderItem(id, ICON_SIZE)
        if (image != null) {
            logger.warn("RecipeItemPreviewProvider: render ok for $id")
            return ImageIcon(image)
        }
        logger.warn("RecipeItemPreviewProvider: render missing for $id, falling back to texture")
        return fallbackProvider.getIcon(id)
    }

    private fun registerCallback(id: String, onReady: (Icon?) -> Unit) {
        callbacks.compute(id) { _, existing ->
            val list = existing ?: mutableListOf()
            list.add(onReady)
            list
        }
    }

    companion object {
        private const val ICON_SIZE = 32
        private const val MAX_CACHE_SIZE = 512
        private val placeholder: Icon = AllIcons.FileTypes.Image
    }

    private data class IconState(
        val icon: Icon?,
        val loading: Boolean
    )
}
