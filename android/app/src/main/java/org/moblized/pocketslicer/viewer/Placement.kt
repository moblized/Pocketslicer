package org.moblized.pocketslicer.viewer

import org.moblized.pocketslicer.data.Bed
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How the user has turned and sized the model. Rotations are in 90° steps. */
data class ModelTransform(
    val rotateX: Int = 0,
    val rotateY: Int = 0,
    val rotateZ: Int = 0,
    val scale: Float = 1f,
)

/**
 * Where the model ends up on the bed: the same maths the server uses
 * (rotate X, then Y, then Z; scale; centre on the bed; lowest point at Z = 0).
 */
data class Placement(
    /** Column-major 4x4 model matrix for OpenGL. */
    val matrix: FloatArray,
    val min: FloatArray,
    val max: FloatArray,
) {
    val sizeX: Float get() = max[0] - min[0]
    val sizeY: Float get() = max[1] - min[1]
    val sizeZ: Float get() = max[2] - min[2]

    fun fits(bed: Bed): Boolean {
        val eps = 0.01f
        return min[0] >= bed.minX - eps && max[0] <= bed.maxX + eps &&
            min[1] >= bed.minY - eps && max[1] <= bed.maxY + eps &&
            max[2] <= bed.height + eps
    }

    companion object {
        /** Row-major 3x3 rotation R = Rz · Ry · Rx. */
        fun rotation(rx: Int, ry: Int, rz: Int): FloatArray {
            fun c(deg: Int) = snap(cos(Math.toRadians(deg.toDouble())).toFloat())
            fun s(deg: Int) = snap(sin(Math.toRadians(deg.toDouble())).toFloat())
            val rxm = floatArrayOf(1f, 0f, 0f, 0f, c(rx), -s(rx), 0f, s(rx), c(rx))
            val rym = floatArrayOf(c(ry), 0f, s(ry), 0f, 1f, 0f, -s(ry), 0f, c(ry))
            val rzm = floatArrayOf(c(rz), -s(rz), 0f, s(rz), c(rz), 0f, 0f, 0f, 1f)
            return mul3(rzm, mul3(rym, rxm))
        }

        private fun snap(v: Float): Float {
            val r = v.roundToInt().toFloat()
            return if (kotlin.math.abs(v - r) < 1e-6f) r else v
        }

        private fun mul3(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(9)
            for (r in 0 until 3) for (c in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) sum += a[r * 3 + k] * b[k * 3 + c]
                out[r * 3 + c] = sum
            }
            return out
        }

        fun compute(meshMin: FloatArray, meshMax: FloatArray, t: ModelTransform, bed: Bed): Placement {
            val r = rotation(t.rotateX, t.rotateY, t.rotateZ)
            val s = t.scale
            val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            // With 90° steps the rotated box corners give the exact new bounds.
            for (i in 0 until 8) {
                val x = if ((i and 1) == 0) meshMin[0] else meshMax[0]
                val y = if ((i and 2) == 0) meshMin[1] else meshMax[1]
                val z = if ((i and 4) == 0) meshMin[2] else meshMax[2]
                for (row in 0 until 3) {
                    val v = s * (r[row * 3] * x + r[row * 3 + 1] * y + r[row * 3 + 2] * z)
                    if (v < lo[row]) lo[row] = v
                    if (v > hi[row]) hi[row] = v
                }
            }
            val tx = bed.centerX - (lo[0] + hi[0]) / 2f
            val ty = bed.centerY - (lo[1] + hi[1]) / 2f
            val tz = -lo[2]
            val m = FloatArray(16)
            // Column-major: m[col * 4 + row]
            for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = s * r[row * 3 + col]
            m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
            val min = floatArrayOf(lo[0] + tx, lo[1] + ty, 0f)
            val max = floatArrayOf(hi[0] + tx, hi[1] + ty, hi[2] + tz)
            return Placement(m, min, max)
        }
    }
}
