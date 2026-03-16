package com.wifi.visualizer.ar

import android.opengl.GLES20
import android.util.Log
import com.wifi.visualizer.intelligence.InterpolationGrid
import com.wifi.visualizer.data.model.SignalQuality
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

private const val TAG = "HeatmapMeshRenderer"

/**
 * Renders a colour-interpolated ground-plane mesh in AR world space.
 *
 * The mesh is a grid of quads (two triangles each) where each vertex colour
 * is derived from the IDW-predicted RSSI at that world position. The result
 * is a smooth heatmap "carpet" lying on the floor at Y = 0.02 m (2 cm above
 * to avoid z-fighting with the physical floor).
 *
 * Architecture:
 *   • Rebuild VBO on [updateGrid] — called whenever new data arrives.
 *   • Each frame, [draw] renders the current mesh without re-uploading.
 *
 * Threading: [updateGrid] must be called on the GL thread or the VBO data
 * must be marshalled via [android.opengl.GLSurfaceView.queueEvent].
 */
class HeatmapMeshRenderer {

    private var program      = 0
    private var posAttrib    = 0
    private var colorAttrib  = 0
    private var mvpUniform   = 0

    private var vertexBuffer : FloatBuffer? = null
    private var indexBuffer  : ShortBuffer? = null
    private var indexCount   = 0
    private var vboId        = IntArray(2) { 0 }
    private var vboReady     = false

    private val vertexShaderSrc = """
        uniform   mat4 u_MVP;
        attribute vec4 a_Position;
        attribute vec4 a_Color;
        varying   vec4 v_Color;
        void main(){
            gl_Position = u_MVP * a_Position;
            v_Color     = a_Color;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        precision mediump float;
        varying vec4 v_Color;
        void main(){ gl_FragColor = v_Color; }
    """.trimIndent()

    // ── Initialisation (GL thread) ────────────────────────────────────────────

    fun createOnGlThread() {
        val vs = compile(GLES20.GL_VERTEX_SHADER,   vertexShaderSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        posAttrib   = GLES20.glGetAttribLocation(program,  "a_Position")
        colorAttrib = GLES20.glGetAttribLocation(program,  "a_Color")
        mvpUniform  = GLES20.glGetUniformLocation(program, "u_MVP")
        GLES20.glGenBuffers(2, vboId, 0)
    }

    // ── Data upload (GL thread) ────────────────────────────────────────────────

    /**
     * Rebuild the mesh from a new [InterpolationGrid].
     * Call via [android.opengl.GLSurfaceView.queueEvent] from a background thread.
     */
    fun updateGrid(grid: InterpolationGrid) {
        // Short indices cap at 65 535 vertices. Guard against oversized grids.
        val vertsPerRow = grid.cols + 1
        @Suppress("UNUSED_VARIABLE")
        val vertsPerCol = grid.rows + 1   // kept for readability; vertsPerRow drives loops
        val totalVerts  = vertsPerRow * vertsPerCol
        if (totalVerts > 65_535) {
            // Grid too large for 16-bit indices — subsample by 2× in each axis
            val subsampled = subsampleGrid(grid)
            updateGrid(subsampled)
            return
        }
        val floatsPerVertex = 7  // x, y, z, r, g, b, a
        val vertexData = FloatArray(vertsPerRow * vertsPerCol * floatsPerVertex)
        var vi = 0

        for (row in 0..grid.rows) {
            for (col in 0..grid.cols) {
                val wx = grid.originX + col * grid.cellSize
                val wz = grid.originZ + row * grid.cellSize
                // RSSI at this vertex: average from neighbouring cells
                val r0 = if (row   < grid.rows && col   < grid.cols) grid.values[row][col]          else null
                val r1 = if (row   < grid.rows && col-1 >= 0)        grid.values[row][col-1]        else null
                val r2 = if (row-1 >= 0        && col   < grid.cols) grid.values[row-1][col]        else null
                val r3 = if (row-1 >= 0        && col-1 >= 0)        grid.values[row-1][col-1]      else null
                val available = listOfNotNull(r0, r1, r2, r3)
                val rssi = if (available.isEmpty()) -90f else available.average().toFloat()

                val (red, green, blue) = rssiToRgb(rssi)
                vertexData[vi++] = wx
                vertexData[vi++] = 0.02f   // 2 cm above floor
                vertexData[vi++] = wz
                vertexData[vi++] = red
                vertexData[vi++] = green
                vertexData[vi++] = blue
                vertexData[vi++] = 0.55f   // alpha: semi-transparent
            }
        }

        // Build index list for GL_TRIANGLES (two per quad)
        val idxList = mutableListOf<Short>()
        for (row in 0 until grid.rows) {
            for (col in 0 until grid.cols) {
                val tl = (row       * vertsPerRow + col    ).toShort()
                val tr = (row       * vertsPerRow + col + 1).toShort()
                val bl = ((row + 1) * vertsPerRow + col    ).toShort()
                val br = ((row + 1) * vertsPerRow + col + 1).toShort()
                idxList.addAll(listOf(tl, bl, tr, tr, bl, br))
            }
        }
        indexCount = idxList.size

        val vBuf = ByteBuffer.allocateDirect(vertexData.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertexData); position(0) }
        val iBuf = ByteBuffer.allocateDirect(idxList.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().apply { idxList.forEach { put(it) }; position(0) }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,         vboId[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,         vertexData.size * 4, vBuf, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboId[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, idxList.size * 2,  iBuf, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        vboReady = true
    }

    // ── Per-frame draw ────────────────────────────────────────────────────────

    fun draw(viewProjectionMatrix: FloatArray) {
        if (!vboReady || program == 0 || indexCount == 0) return
        val stride = 7 * 4  // 7 floats × 4 bytes

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProjectionMatrix, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId[0])
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glVertexAttribPointer(posAttrib,   3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(colorAttrib)
        GLES20.glVertexAttribPointer(colorAttrib, 4, GLES20.GL_FLOAT, false, stride, 12)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboId[1])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(colorAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Maps RSSI to a smooth RGB gradient using HSL hue rotation:
     *   -30 dBm → pure green (hue 120°)
     *   -70 dBm → yellow (hue 60°)
     *   -90 dBm → pure red (hue 0°)
     */
    private fun rssiToRgb(rssi: Float): Triple<Float, Float, Float> {
        val t = ((rssi + 90f) / 60f).coerceIn(0f, 1f)   // 0 = poor, 1 = excellent
        val hue = t * 120f  // 0° red → 120° green
        return hslToRgb(hue, 0.85f, 0.45f)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): Triple<Float, Float, Float> {
        val c = (1f - Math.abs(2f * l - 1f)) * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60  -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else    -> Triple(c, 0f, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, GLES20.glGetShaderInfoLog(s))
        return s
    }

    /**
     * Reduce a grid by half in each dimension to stay within 16-bit index limits.
     */
    private fun subsampleGrid(grid: InterpolationGrid): InterpolationGrid {
        val newRows = grid.rows / 2
        val newCols = grid.cols / 2
        val newValues = Array(newRows) { row ->
            FloatArray(newCols) { col -> grid.values[row * 2][col * 2] }
        }
        return InterpolationGrid(
            values   = newValues,
            originX  = grid.originX,
            originZ  = grid.originZ,
            cellSize = grid.cellSize * 2f,
            cols     = newCols,
            rows     = newRows
        )
    }
}
