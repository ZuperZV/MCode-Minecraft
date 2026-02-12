package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalog
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.awt.image.RasterFormatException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class BlockInventoryRenderer(
    private val catalog: AssetCatalog,
    private val modelResolver: ModelResolver = ModelResolver(catalog),
    private val blockModelResolver: BlockModelResolver = BlockModelResolver(catalog, modelResolver),
    private val textureResolver: TextureResolver = TextureResolver(catalog),
    private val meshBuilder: BlockMeshBuilder = BlockMeshBuilder(),
    private val transformResolver: GuiTransformResolver = GuiTransformResolver()
) {
    private val cache = LruCache<String, BufferedImage?>(MAX_CACHE_SIZE)
    private val meshCache = LruCache<String, List<BlockFaceMesh>>(MAX_MESH_CACHE_SIZE)
    private val locale = Locale.US
    private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(BlockInventoryRenderer::class.java)

    fun renderItem(id: String, size: Int = DEFAULT_SIZE): BufferedImage? {
        val key = "${id.lowercase(locale)}@$size"
        if (cache.contains(key)) {
            return cache.get(key)
        }

        val itemModel = modelResolver.resolveItemModel(id) ?: return renderBlockId(id, size)
        var blockModel = blockModelResolver.resolveItemToBlockModel(id) ?: return renderBlockId(id, size)
        logger.warn("BlockInventoryRenderer: item $id block model ${blockModel.id}")
        var modelKey = blockModel.id.toString().lowercase(locale)
        var faces = if (meshCache.contains(modelKey)) {
            meshCache.get(modelKey).orEmpty()
        } else {
            val built = meshBuilder.build(blockModel)
            meshCache.put(modelKey, built)
            built
        }
        if (faces.isEmpty()) {
            logger.warn("BlockInventoryRenderer: no faces for $id model ${blockModel.id}, trying blockstate fallback")
            val fallbackModel = blockModelResolver.resolveBlockModelForId(id)
            if (fallbackModel != null) {
                blockModel = fallbackModel
                modelKey = blockModel.id.toString().lowercase(locale)
                faces = if (meshCache.contains(modelKey)) {
                    meshCache.get(modelKey).orEmpty()
                } else {
                    val built = meshBuilder.build(blockModel)
                    meshCache.put(modelKey, built)
                    built
                }
            }
        }
        if (faces.isEmpty()) {
            logger.warn("BlockInventoryRenderer: no faces for $id after fallback")
            return null
        }

        val transform = transformResolver.resolve(itemModel.displayGui, blockModel.displayGui)
        val image = renderFaces(faces, blockModel, transform, size)
        cache.put(key, image)
        return image
    }

    fun renderBlockId(id: String, size: Int = DEFAULT_SIZE): BufferedImage? {
        val key = "block:${id.lowercase(locale)}@$size"
        if (cache.contains(key)) {
            return cache.get(key)
        }
        val blockModel = blockModelResolver.resolveBlockModelForId(id) ?: return null
        logger.warn("BlockInventoryRenderer: rendering block $id with model ${blockModel.id}")
        val modelKey = blockModel.id.toString().lowercase(locale)
        val faces = if (meshCache.contains(modelKey)) {
            meshCache.get(modelKey).orEmpty()
        } else {
            val built = meshBuilder.build(blockModel)
            meshCache.put(modelKey, built)
            built
        }
        if (faces.isEmpty()) return null
        val transform = transformResolver.resolve(null, blockModel.displayGui)
        val image = renderFaces(faces, blockModel, transform, size)
        cache.put(key, image)
        return image
    }

    fun clearCache() {
        cache.clear()
        meshCache.clear()
        textureResolver.clearCache()
        blockModelResolver.clearCache()
    }

    private fun renderFaces(
        faces: List<BlockFaceMesh>,
        model: ResolvedBlockModel,
        transform: ModelDisplayTransform,
        size: Int
    ): BufferedImage {
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = canvas.createGraphics()
        applyPixelHints(g2)

        val baseScale = size * 0.9
        val transformedFaces = faces.mapNotNull { face ->
            val vertices = face.vertices.map { applyTransform(it, transform, baseScale) }
            val avgZ = vertices.map { it.z }.average()
            RenderFace(face, vertices, avgZ)
        }.sortedBy { it.depth }

        transformedFaces.forEach { renderFace ->
            val textureRef = modelResolver.resolveTextureRef(
                renderFace.face.textureRef,
                model.textures
            ) ?: return@forEach
            val texture = textureResolver.getTexture(textureRef, model.id.namespace) ?: return@forEach
            val faceTexture = cropTexture(texture, renderFace.face.uv)
            val polygon = toPolygon(renderFace.vertices, size)
            if (!drawTexturedQuad(g2, faceTexture, renderFace.vertices, size)) {
                val bounds = polygon.bounds
                val anchor = if (bounds.width > 0 && bounds.height > 0) {
                    Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
                } else {
                    Rectangle(0, 0, faceTexture.width, faceTexture.height)
                }
                g2.paint = TexturePaint(faceTexture, anchor)
                g2.fillPolygon(polygon)
            }

            val shade = computeShade(renderFace.vertices)
            if (shade > 0.0) {
                g2.composite = AlphaComposite.SrcOver.derive(shade.toFloat())
                g2.color = Color.BLACK
                g2.fillPolygon(polygon)
                g2.composite = AlphaComposite.SrcOver
            }
        }

        g2.dispose()
        return canvas
    }

    private fun applyTransform(source: Vec3, transform: ModelDisplayTransform, scale: Double): Vec3 {
        val sx = source.x * transform.scale.x
        val sy = source.y * transform.scale.y
        val sz = source.z * transform.scale.z

        val rx = Math.toRadians(transform.rotation.x)
        val ry = Math.toRadians(transform.rotation.y)
        val rz = Math.toRadians(transform.rotation.z)

        var x = sx
        var y = sy
        var z = sz

        var tx: Double
        var ty: Double
        var tz: Double

        val cosZ = kotlin.math.cos(rz)
        val sinZ = kotlin.math.sin(rz)
        tx = x * cosZ - y * sinZ
        ty = x * sinZ + y * cosZ
        x = tx
        y = ty

        val cosY = kotlin.math.cos(ry)
        val sinY = kotlin.math.sin(ry)
        tx = x * cosY + z * sinY
        tz = -x * sinY + z * cosY
        x = tx
        z = tz

        val cosX = kotlin.math.cos(rx)
        val sinX = kotlin.math.sin(rx)
        ty = y * cosX - z * sinX
        tz = y * sinX + z * cosX
        y = ty
        z = tz

        val adjustedScale = scale * 0.85
        return Vec3(x * adjustedScale, y * adjustedScale, z * adjustedScale)
    }

    private fun toPolygon(vertices: List<Vec3>, size: Int): Polygon {
        val xs = IntArray(vertices.size)
        val ys = IntArray(vertices.size)
        val center = size / 2.0
        for (i in vertices.indices) {
            xs[i] = (center + vertices[i].x).toInt()
            ys[i] = (center - vertices[i].y).toInt()
        }
        return Polygon(xs, ys, vertices.size)
    }

    private fun drawTexturedQuad(
        g2: Graphics2D,
        texture: BufferedImage,
        vertices: List<Vec3>,
        size: Int
    ): Boolean {
        if (vertices.size != 4) return false
        val p0 = toScreen(vertices[0], size)
        val p1 = toScreen(vertices[1], size)
        val p3 = toScreen(vertices[3], size)

        val w = texture.width.toDouble()
        val h = texture.height.toDouble()
        if (w <= 0.0 || h <= 0.0) return false

        val transform = AffineTransform(
            (p1.x - p0.x) / w,
            (p1.y - p0.y) / w,
            (p3.x - p0.x) / h,
            (p3.y - p0.y) / h,
            p0.x,
            p0.y
        )
        val polygon = toPolygon(vertices, size)
        val oldClip = g2.clip
        g2.clip = polygon
        g2.drawImage(texture, transform, null)
        g2.clip = oldClip
        return true
    }

    private fun toScreen(vertex: Vec3, size: Int): Point2D.Double {
        val center = size / 2.0
        return Point2D.Double(center + vertex.x, center - vertex.y)
    }

    private fun cropTexture(texture: BufferedImage, uv: DoubleArray?): BufferedImage {
        if (uv == null || uv.size < 4) return texture
        val x1 = ((uv[0] / 16.0) * texture.width).toInt()
        val y1 = ((uv[1] / 16.0) * texture.height).toInt()
        val x2 = ((uv[2] / 16.0) * texture.width).toInt()
        val y2 = ((uv[3] / 16.0) * texture.height).toInt()

        val ix1 = max(0, min(texture.width, min(x1, x2)))
        val ix2 = max(0, min(texture.width, max(x1, x2)))
        val iy1 = max(0, min(texture.height, min(y1, y2)))
        val iy2 = max(0, min(texture.height, max(y1, y2)))

        val w = max(1, ix2 - ix1)
        val h = max(1, iy2 - iy1)
        return try {
            texture.getSubimage(ix1, iy1, w, h)
        } catch (_: RasterFormatException) {
            texture
        }
    }

    private fun computeShade(vertices: List<Vec3>): Double {
        if (vertices.size < 3) return 0.0
        val v0 = vertices[0]
        val v1 = vertices[1]
        val v2 = vertices[2]
        val ax = v1.x - v0.x
        val ay = v1.y - v0.y
        val az = v1.z - v0.z
        val bx = v2.x - v0.x
        val by = v2.y - v0.y
        val bz = v2.z - v0.z
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len == 0.0) return 0.0
        val lx = 0.2
        val ly = 0.7
        val lz = 1.0
        val lLen = sqrt(lx * lx + ly * ly + lz * lz)
        val dot = (nx / len) * (lx / lLen) + (ny / len) * (ly / lLen) + (nz / len) * (lz / lLen)
        val intensity = max(0.0, min(1.0, 1.0 - dot))
        return intensity * 0.35
    }

    private fun applyPixelHints(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    }

    private data class RenderFace(
        val face: BlockFaceMesh,
        val vertices: List<Vec3>,
        val depth: Double
    )

    companion object {
        private const val DEFAULT_SIZE = 32
        private const val MAX_CACHE_SIZE = 256
        private const val MAX_MESH_CACHE_SIZE = 128
    }
}
