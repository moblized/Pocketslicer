package org.moblized.pocketslicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Triangle mesh for display: interleaved x,y,z,nx,ny,nz per vertex, plus its bounds.
 */
class StlMesh(
    val data: FloatArray,
    val vertexCount: Int,
    val min: FloatArray,
    val max: FloatArray,
) {
    companion object {
        /** Parse a binary STL (the server always sends binary). */
        fun parse(bytes: ByteArray): StlMesh {
            require(bytes.size >= 84) { "File is too short to be an STL" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val declared = buf.getInt(80).toLong() and 0xFFFFFFFFL
            val fitting = (bytes.size - 84) / 50
            val count = minOf(declared, fitting.toLong()).toInt()
            require(count > 0) { "The model has no triangles" }

            val data = FloatArray(count * 18)
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            val v = FloatArray(9)
            var o = 0
            for (t in 0 until count) {
                val base = 84 + t * 50 + 12 // skip the stored normal; recompute it
                for (i in 0 until 9) v[i] = buf.getFloat(base + i * 4)
                val ux = v[3] - v[0]; val uy = v[4] - v[1]; val uz = v[5] - v[2]
                val wx = v[6] - v[0]; val wy = v[7] - v[1]; val wz = v[8] - v[2]
                var nx = uy * wz - uz * wy
                var ny = uz * wx - ux * wz
                var nz = ux * wy - uy * wx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-12f) {
                    nx /= len; ny /= len; nz /= len
                } else {
                    nx = 0f; ny = 0f; nz = 1f
                }
                for (k in 0 until 3) {
                    val x = v[k * 3]; val y = v[k * 3 + 1]; val z = v[k * 3 + 2]
                    data[o++] = x; data[o++] = y; data[o++] = z
                    data[o++] = nx; data[o++] = ny; data[o++] = nz
                    if (x < min[0]) min[0] = x; if (x > max[0]) max[0] = x
                    if (y < min[1]) min[1] = y; if (y > max[1]) max[1] = y
                    if (z < min[2]) min[2] = z; if (z > max[2]) max[2] = z
                }
            }
            return StlMesh(data, count * 3, min, max)
        }
    }
}
