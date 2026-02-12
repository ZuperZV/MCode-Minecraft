package com.github.zuperzv.mcodeminecraft.render

class GuiTransformResolver {
    fun resolve(itemTransform: ModelDisplayTransform?, blockTransform: ModelDisplayTransform?): ModelDisplayTransform {
        return itemTransform ?: blockTransform ?: DEFAULT_GUI_TRANSFORM
    }

    companion object {
        val DEFAULT_GUI_TRANSFORM = ModelDisplayTransform(
            rotation = Vec3(30.0, 225.0, 0.0),
            scale = Vec3(0.625, 0.625, 0.625)
        )
    }
}
