package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import com.github.zuperzv.mcodeminecraft.assets.ResourceLocation
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Locale

class InventoryItemRenderer(
    private val catalog: AssetCatalog,
    private val modelResolver: ModelResolver = ModelResolver(catalog),
    private val textureResolver: TextureResolver = TextureResolver(catalog),
    private val blockRenderer: BlockInventoryRenderer = BlockInventoryRenderer(catalog, modelResolver = modelResolver, textureResolver = textureResolver)
) {
    private val cache = LruCache<String, BufferedImage?>(MAX_CACHE_SIZE)
    private val locale = Locale.US
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(InventoryItemRenderer::class.java)

    fun renderItem(id: String, size: Int = DEFAULT_SIZE): BufferedImage? {
        val key = "${id.lowercase(locale)}@$size"
        if (cache.contains(key)) {
            return cache.get(key)
        }
        val model = modelResolver.resolveItemModel(id)
        if (model == null) {
            logger.warn("InventoryItemRenderer: no item model for $id, trying blockstate")
            val block = blockRenderer.renderBlockId(id, size)
            if (block == null) {
                logger.warn("InventoryItemRenderer: blockstate render failed for $id")
            }
            return block
        }
        val image = when {
            model.isGenerated -> renderGenerated(model, size)
            model.blockParent != null -> blockRenderer.renderItem(id, size) ?: renderBlockModel(model.blockParent, model, size)
            else -> renderGenerated(model, size)
        }
        cache.put(key, image)
        if (image == null) {
            logger.warn("InventoryItemRenderer: render null for $id")
        }
        return image
    }

    fun clearCache() {
        cache.clear()
        textureResolver.clearCache()
        blockRenderer.clearCache()
    }

    private fun renderGenerated(model: ResolvedModel, size: Int): BufferedImage? {
        val layers = model.textures.keys
            .filter { it.startsWith("layer") }
            .sortedBy { it.removePrefix("layer").toIntOrNull() ?: 0 }
        if (layers.isEmpty()) return null

        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        applyPixelHints(g2)
        layers.forEach { key ->
            val ref = model.textures[key] ?: return@forEach
            val resolved = modelResolver.resolveTextureRef(ref, model.textures) ?: return@forEach
            val texture = textureResolver.getTexture(resolved, model.id.namespace) ?: return@forEach
            drawLayer(g2, texture, size)
        }
        g2.dispose()

        val transform = model.displayGui
        return if (transform != null) {
            applyGuiTransform(canvas, transform, size)
        } else {
            canvas
        }
    }

    private fun renderBlockModel(
        blockModel: ResourceLocation,
        model: ResolvedModel,
        size: Int
    ): BufferedImage? {
        val resolved = modelResolver.resolveModel(blockModel) ?: return null
        val textureKey = resolved.textures["all"]
            ?: resolved.textures["side"]
            ?: resolved.textures["particle"]
            ?: resolved.textures["layer0"]
            ?: resolved.textures.values.firstOrNull()
            ?: return null
        val texRef = modelResolver.resolveTextureRef(textureKey, resolved.textures) ?: return null
        val texture = textureResolver.getTexture(texRef, resolved.id.namespace) ?: return null
        val cube = renderCube(texture, size)
        val transform = model.displayGui
        return if (transform != null) {
            applyGuiTransform(cube, transform, size)
        } else {
            cube
        }
    }

    private fun drawLayer(g2: Graphics2D, texture: BufferedImage, size: Int) {
        val scaled = scaleNearest(texture, size, size)
        g2.drawImage(scaled, 0, 0, null)
    }

    private fun renderCube(texture: BufferedImage, size: Int): BufferedImage {
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        applyPixelHints(g2)

        val paint = TexturePaint(texture, java.awt.Rectangle(0, 0, texture.width, texture.height))
        val cx = size * 0.5
        val topY = size * 0.15
        val face = size * 0.55
        val depth = size * 0.2

        val top = Polygon(
            intArrayOf((cx).toInt(), (cx + face * 0.5).toInt(), (cx).toInt(), (cx - face * 0.5).toInt()),
            intArrayOf(topY.toInt(), (topY + depth).toInt(), (topY + depth * 2).toInt(), (topY + depth).toInt()),
            4
        )
        val left = Polygon(
            intArrayOf((cx - face * 0.5).toInt(), (cx).toInt(), (cx).toInt(), (cx - face * 0.5).toInt()),
            intArrayOf((topY + depth).toInt(), (topY + depth * 2).toInt(), (topY + depth * 2 + face).toInt(), (topY + depth + face).toInt()),
            4
        )
        val right = Polygon(
            intArrayOf((cx).toInt(), (cx + face * 0.5).toInt(), (cx + face * 0.5).toInt(), (cx).toInt()),
            intArrayOf((topY + depth * 2).toInt(), (topY + depth).toInt(), (topY + depth + face).toInt(), (topY + depth * 2 + face).toInt()),
            4
        )

        g2.paint = paint
        g2.fill(top)
        g2.fill(left)
        g2.fill(right)

        g2.composite = AlphaComposite.SrcOver.derive(0.18f)
        g2.color = Color.BLACK
        g2.fill(left)
        g2.composite = AlphaComposite.SrcOver.derive(0.08f)
        g2.fill(right)

        g2.dispose()
        return canvas
    }

    private fun applyGuiTransform(image: BufferedImage, transform: ModelDisplayTransform, size: Int): BufferedImage {
        val scale = (transform.scale.x + transform.scale.y + transform.scale.z) / 3.0
        val rotationZ = Math.toRadians(transform.rotation.z)
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = out.createGraphics()
        applyPixelHints(g2)
        val tx = AffineTransform()
        tx.translate(size / 2.0, size / 2.0)
        tx.rotate(rotationZ)
        tx.scale(scale, scale)
        tx.translate(-image.width / 2.0, -image.height / 2.0)
        g2.drawImage(image, tx, null)
        g2.dispose()
        return out
    }

    private fun scaleNearest(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = scaled.createGraphics()
        applyPixelHints(g2)
        g2.drawImage(source, 0, 0, width, height, null)
        g2.dispose()
        return scaled
    }

    private fun applyPixelHints(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    }

    companion object {
        private const val DEFAULT_SIZE = 32
        private const val MAX_CACHE_SIZE = 256
    }
}
