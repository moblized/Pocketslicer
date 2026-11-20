package org.moblized.pocketslicer.viewer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import org.moblized.pocketslicer.data.Bed
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Draws the build plate, the build volume and the model. Z is up, units are mm. */
class PlateRenderer : GLSurfaceView.Renderer {

    // ---- state shared with the UI thread (guarded by `this`) ----
    private var mesh: StlMesh? = null
    private var meshDirty = false
    private var bed: Bed = Bed()
    private var bedDirty = true
    private var placement: Placement? = null
    private var dark = false

    @Volatile var yaw = -90f
    @Volatile var pitch = 28f
    @Volatile var distance = 520f
    @Volatile var panX = 0f
    @Volatile var panY = 0f

    // ---- GL objects (GL thread only) ----
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uLit = 0
    private var meshVbo = 0
    private var meshVertices = 0
    private var gridVbo = 0
    private var gridVertices = 0
    private var majorVbo = 0
    private var majorVertices = 0
    private var plateVbo = 0
    private var volumeVbo = 0
    private var volumeVertices = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var aspect = 1f

    @Synchronized fun setMesh(newMesh: StlMesh?) {
        if (newMesh !== mesh) {
            mesh = newMesh
            meshDirty = true
        }
    }

    @Synchronized fun setBed(newBed: Bed) {
        if (newBed != bed) {
            bed = newBed
            bedDirty = true
        }
    }

    @Synchronized fun setPlacement(p: Placement?) {
        placement = p
    }

    @Synchronized fun setDark(value: Boolean) {
        dark = value
    }

    fun resetView() {
        val b = synchronized(this) { bed }
        yaw = -90f
        pitch = 28f
        distance = max(b.width, b.depth) * 2.0f
        panX = 0f
        panY = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLit = GLES20.glGetUniformLocation(program, "uLit")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        // A fresh context has no buffers: upload everything again.
        meshVbo = 0; gridVbo = 0; majorVbo = 0; plateVbo = 0; volumeVbo = 0
        synchronized(this) {
            meshDirty = true
            bedDirty = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        val snapshot = synchronized(this) {
            val s = Snapshot(mesh, meshDirty, bed, bedDirty, placement, dark)
            meshDirty = false
            bedDirty = false
            s
        }
        if (snapshot.bedDirty) uploadBed(snapshot.bed)
        if (snapshot.meshDirty) uploadMesh(snapshot.mesh)

        val bg = if (snapshot.dark) floatArrayOf(0.09f, 0.10f, 0.11f) else floatArrayOf(0.93f, 0.95f, 0.95f)
        GLES20.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val b = snapshot.bed
        val targetZ = (snapshot.placement?.sizeZ ?: 20f) / 3f
        val tx = b.centerX + panX
        val ty = b.centerY + panY
        val yr = Math.toRadians(yaw.toDouble())
        val pr = Math.toRadians(pitch.toDouble())
        val ex = tx + (distance * cos(pr) * cos(yr)).toFloat()
        val ey = ty + (distance * cos(pr) * sin(yr)).toFloat()
        val ez = targetZ + (distance * sin(pr)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, tx, ty, targetZ, 0f, 0f, 1f)
        Matrix.perspectiveM(proj, 0, 35f, aspect, 2f, max(4000f, distance * 4f))
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        // Plate surface
        val plateColor = if (snapshot.dark) floatArrayOf(0.17f, 0.19f, 0.20f, 1f) else floatArrayOf(0.80f, 0.83f, 0.84f, 1f)
        drawUnlit(plateVbo, 6, GLES20.GL_TRIANGLES, plateColor)
        GLES20.glDepthMask(false)
        val minor = if (snapshot.dark) floatArrayOf(1f, 1f, 1f, 0.07f) else floatArrayOf(0f, 0f, 0f, 0.08f)
        val major = if (snapshot.dark) floatArrayOf(1f, 1f, 1f, 0.18f) else floatArrayOf(0f, 0f, 0f, 0.20f)
        drawUnlit(gridVbo, gridVertices, GLES20.GL_LINES, minor)
        drawUnlit(majorVbo, majorVertices, GLES20.GL_LINES, major)
        val volume = if (snapshot.dark) floatArrayOf(0.18f, 0.83f, 0.70f, 0.30f) else floatArrayOf(0f, 0.55f, 0.47f, 0.35f)
        drawUnlit(volumeVbo, volumeVertices, GLES20.GL_LINES, volume)
        GLES20.glDepthMask(true)

        // Model
        val p = snapshot.placement
        if (meshVbo != 0 && p != null) {
            val fits = p.fits(b)
            val color = when {
                !fits -> floatArrayOf(1.0f, 0.55f, 0.24f, 1f)
                snapshot.dark -> floatArrayOf(0.18f, 0.83f, 0.70f, 1f)
                else -> floatArrayOf(0.0f, 0.62f, 0.53f, 1f)
            }
            Matrix.multiplyMM(mvp, 0, vp, 0, p.matrix, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, p.matrix, 0)
            GLES20.glUniform4fv(uColor, 1, color, 0)
            GLES20.glUniform1f(uLit, 1f)
            bindAttributes(meshVbo, withNormals = true)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, meshVertices)
            GLES20.glDisableVertexAttribArray(aNormal)
        }
    }

    private data class Snapshot(
        val mesh: StlMesh?,
        val meshDirty: Boolean,
        val bed: Bed,
        val bedDirty: Boolean,
        val placement: Placement?,
        val dark: Boolean,
    )

    private fun drawUnlit(vbo: Int, count: Int, mode: Int, color: FloatArray) {
        if (vbo == 0 || count == 0) return
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, identity, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glUniform1f(uLit, 0f)
        bindAttributes(vbo, withNormals = false)
        GLES20.glDrawArrays(mode, 0, count)
    }

    private fun bindAttributes(vbo: Int, withNormals: Boolean) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val stride = if (withNormals) 24 else 12
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        if (withNormals) {
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, 12)
        } else {
            GLES20.glDisableVertexAttribArray(aNormal)
            GLES20.glVertexAttrib3f(aNormal, 0f, 0f, 1f)
        }
    }

    private fun uploadMesh(m: StlMesh?) {
        if (meshVbo != 0) {
            GLES20.glDeleteBuffers(1, intArrayOf(meshVbo), 0)
            meshVbo = 0
        }
        meshVertices = 0
        if (m == null) return
        meshVbo = upload(m.data)
        meshVertices = m.vertexCount
    }

    private fun uploadBed(b: Bed) {
        val buffers = intArrayOf(gridVbo, majorVbo, plateVbo, volumeVbo).filter { it != 0 }.toIntArray()
        if (buffers.isNotEmpty()) GLES20.glDeleteBuffers(buffers.size, buffers, 0)

        val minor = ArrayList<Float>()
        val major = ArrayList<Float>()
        val z = 0.02f
        var x = b.minX
        while (x <= b.maxX + 0.01f) {
            val target = if (((x - b.minX).toInt()) % 50 == 0) major else minor
            target.addAll(listOf(x, b.minY, z, x, b.maxY, z))
            x += 10f
        }
        var y = b.minY
        while (y <= b.maxY + 0.01f) {
            val target = if (((y - b.minY).toInt()) % 50 == 0) major else minor
            target.addAll(listOf(b.minX, y, z, b.maxX, y, z))
            y += 10f
        }
        // Outline is always "major"
        major.addAll(listOf(b.minX, b.minY, z, b.maxX, b.minY, z, b.maxX, b.minY, z, b.maxX, b.maxY, z,
            b.maxX, b.maxY, z, b.minX, b.maxY, z, b.minX, b.maxY, z, b.minX, b.minY, z))
        gridVbo = upload(minor.toFloatArray()); gridVertices = minor.size / 3
        majorVbo = upload(major.toFloatArray()); majorVertices = major.size / 3

        val pz = -0.05f
        plateVbo = upload(floatArrayOf(
            b.minX, b.minY, pz, b.maxX, b.minY, pz, b.maxX, b.maxY, pz,
            b.minX, b.minY, pz, b.maxX, b.maxY, pz, b.minX, b.maxY, pz,
        ))

        val h = b.height
        val corners = listOf(b.minX to b.minY, b.maxX to b.minY, b.maxX to b.maxY, b.minX to b.maxY)
        val vol = ArrayList<Float>()
        for (i in corners.indices) {
            val (ax, ay) = corners[i]
            val (bx, by) = corners[(i + 1) % 4]
            vol.addAll(listOf(ax, ay, h, bx, by, h)) // top edges
            vol.addAll(listOf(ax, ay, 0f, ax, ay, h)) // verticals
        }
        volumeVbo = upload(vol.toFloatArray()); volumeVertices = vol.size / 3
    }

    private fun upload(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return ids[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader error: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            void main() {
                vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uLit;
            varying vec3 vNormal;
            void main() {
                if (uLit < 0.5) {
                    gl_FragColor = uColor;
                    return;
                }
                vec3 n = normalize(vNormal);
                float key = abs(dot(n, normalize(vec3(0.35, -0.55, 0.75))));
                float fill = abs(dot(n, normalize(vec3(-0.6, 0.4, 0.2))));
                float light = 0.30 + 0.55 * key + 0.20 * fill;
                gl_FragColor = vec4(uColor.rgb * min(light, 1.1), uColor.a);
            }
        """
    }
}
