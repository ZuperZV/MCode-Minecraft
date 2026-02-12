package com.github.zuperzv.mcodeminecraft.render

import com.github.zuperzv.mcodeminecraft.assets.AssetCatalogService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JPanel

class ItemPreviewPanel(
    private val project: Project,
    private val size: Int = DEFAULT_SIZE
) : JPanel() {
    @Volatile
    private var currentId: String? = null
    @Volatile
    private var image: BufferedImage? = null
    @Volatile
    private var renderer: InventoryItemRenderer? = null

    private val renderToken = AtomicLong(0)
    private val executor = AppExecutorUtil.getAppExecutorService()

    init {
        isOpaque = false
        preferredSize = Dimension(size, size)
        minimumSize = Dimension(size, size)

        project.getService(AssetCatalogService::class.java)
            .getCatalogAsync()
            .thenAccept { catalog ->
                if (catalog != null) {
                    renderer = InventoryItemRenderer(catalog)
                    currentId?.let { scheduleRender(it) }
                }
            }
    }

    fun setItemId(id: String?) {
        currentId = id?.trim()?.takeIf { it.isNotEmpty() }
        image = null
        repaint()
        val normalized = currentId ?: return
        scheduleRender(normalized)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val rendered = image ?: return
        val x = (width - rendered.width) / 2
        val y = (height - rendered.height) / 2
        g.drawImage(rendered, x, y, null)
    }

    private fun scheduleRender(id: String) {
        val token = renderToken.incrementAndGet()
        executor.execute {
            val renderer = renderer ?: return@execute
            val rendered = renderer.renderItem(id, size)
            if (renderToken.get() != token || currentId != id) {
                return@execute
            }
            image = rendered
            ApplicationManager.getApplication().invokeLater {
                if (renderToken.get() == token && currentId == id) {
                    repaint()
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_SIZE = 32
    }
}
