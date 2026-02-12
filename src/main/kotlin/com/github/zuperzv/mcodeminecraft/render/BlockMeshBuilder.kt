package com.github.zuperzv.mcodeminecraft.render

import kotlin.math.max
import kotlin.math.min

data class BlockFaceMesh(
    val vertices: List<Vec3>,
    val textureRef: String,
    val uv: DoubleArray?
)

class BlockMeshBuilder {
    fun build(model: ResolvedBlockModel): List<BlockFaceMesh> {
        if (model.elements.isEmpty()) return emptyList()
        val faces = ArrayList<BlockFaceMesh>()
        model.elements.forEach { element ->
            val x1 = element.from.x / 16.0 - 0.5
            val y1 = element.from.y / 16.0 - 0.5
            val z1 = element.from.z / 16.0 - 0.5
            val x2 = element.to.x / 16.0 - 0.5
            val y2 = element.to.y / 16.0 - 0.5
            val z2 = element.to.z / 16.0 - 0.5

            val nx1 = min(x1, x2)
            val nx2 = max(x1, x2)
            val ny1 = min(y1, y2)
            val ny2 = max(y1, y2)
            val nz1 = min(z1, z2)
            val nz2 = max(z1, z2)

            element.faces.forEach { (side, face) ->
                val verts = when (side) {
                    "north" -> listOf(
                        Vec3(nx1, ny2, nz1),
                        Vec3(nx2, ny2, nz1),
                        Vec3(nx2, ny1, nz1),
                        Vec3(nx1, ny1, nz1)
                    )
                    "south" -> listOf(
                        Vec3(nx2, ny2, nz2),
                        Vec3(nx1, ny2, nz2),
                        Vec3(nx1, ny1, nz2),
                        Vec3(nx2, ny1, nz2)
                    )
                    "west" -> listOf(
                        Vec3(nx1, ny2, nz1),
                        Vec3(nx1, ny2, nz2),
                        Vec3(nx1, ny1, nz2),
                        Vec3(nx1, ny1, nz1)
                    )
                    "east" -> listOf(
                        Vec3(nx2, ny2, nz2),
                        Vec3(nx2, ny2, nz1),
                        Vec3(nx2, ny1, nz1),
                        Vec3(nx2, ny1, nz2)
                    )
                    "up" -> listOf(
                        Vec3(nx1, ny2, nz1),
                        Vec3(nx2, ny2, nz1),
                        Vec3(nx2, ny2, nz2),
                        Vec3(nx1, ny2, nz2)
                    )
                    "down" -> listOf(
                        Vec3(nx1, ny1, nz2),
                        Vec3(nx2, ny1, nz2),
                        Vec3(nx2, ny1, nz1),
                        Vec3(nx1, ny1, nz1)
                    )
                    else -> emptyList()
                }
                if (verts.isNotEmpty()) {
                    val rotated = element.rotation?.let { applyRotation(verts, it) } ?: verts
                    faces.add(BlockFaceMesh(rotated, face.texture, face.uv))
                }
            }
        }
        return faces
    }

    private fun applyRotation(vertices: List<Vec3>, rotation: ElementRotation): List<Vec3> {
        val origin = Vec3(
            rotation.origin.x / 16.0 - 0.5,
            rotation.origin.y / 16.0 - 0.5,
            rotation.origin.z / 16.0 - 0.5
        )
        val angle = Math.toRadians(rotation.angle)
        val cosA = kotlin.math.cos(angle)
        val sinA = kotlin.math.sin(angle)
        return vertices.map { vertex ->
            val x = vertex.x - origin.x
            val y = vertex.y - origin.y
            val z = vertex.z - origin.z
            val (rx, ry, rz) = when (rotation.axis) {
                "x" -> {
                    val ny = y * cosA - z * sinA
                    val nz = y * sinA + z * cosA
                    Triple(x, ny, nz)
                }
                "y" -> {
                    val nx = x * cosA + z * sinA
                    val nz = -x * sinA + z * cosA
                    Triple(nx, y, nz)
                }
                "z" -> {
                    val nx = x * cosA - y * sinA
                    val ny = x * sinA + y * cosA
                    Triple(nx, ny, z)
                }
                else -> Triple(x, y, z)
            }
            Vec3(rx + origin.x, ry + origin.y, rz + origin.z)
        }
    }
}
