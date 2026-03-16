package com.wifi.visualizer.ar

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Anchor
import com.wifi.visualizer.data.model.SignalQuality
import com.wifi.visualizer.data.model.WifiScanResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

private const val TAG = "PillarRenderer"

/**
 * OpenGL ES 2.0 renderer that draws colour-coded rectangular pillars for
 * each Wi-Fi scan result anchored in AR space.
 *
 * Coordinate convention: Y-up, right-hand, metres.
 *
 * The pillar is a simple box (cuboid) centred at the anchor's XZ position,
 * rising from Y = 0 to Y = pillarHeight.  A colour uniform is driven by the
 * signal quality so the user immediately reads signal strength from colour
 * and from height.
 *
 * Call [createOnGlThread] once from [ArMappingActivity]'s onSurfaceCreated,
 * then [draw] once per frame inside onDrawFrame.
 */
class PillarRenderer {

    // OpenGL handles
    private var program       = 0
    private var posAttrib     = 0
    private var mvpUniform    = 0
    private var colorUniform  = 0
    private var vertexBuffer: FloatBuffer? = null
    private var indexBuffer: java.nio.ShortBuffer? = null

    // ── Shader sources ────────────────────────────────────────────────────────

    private val vertexShaderSrc = """
        uniform   mat4  u_MVP;
        attribute vec4  a_Position;
        void main() {
            gl_Position = u_MVP * a_Position;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        precision mediump float;
        uniform vec4 u_Color;
        void main() {
            gl_FragColor = u_Color;
        }
    """.trimIndent()

    // ── Unit-cube geometry (before scaling) ──────────────────────────────────
    // 8 vertices of a 1×1×1 cube centred at origin
    private val cubeVertices = floatArrayOf(
        // X      Y      Z
        -0.5f,  0.0f, -0.5f,   // 0 bottom-back-left
         0.5f,  0.0f, -0.5f,   // 1 bottom-back-right
         0.5f,  0.0f,  0.5f,   // 2 bottom-front-right
        -0.5f,  0.0f,  0.5f,   // 3 bottom-front-left
        -0.5f,  1.0f, -0.5f,   // 4 top-back-left
         0.5f,  1.0f, -0.5f,   // 5 top-back-right
         0.5f,  1.0f,  0.5f,   // 6 top-front-right
        -0.5f,  1.0f,  0.5f    // 7 top-front-left
    )

    // 12 triangles (36 indices)
    private val cubeIndices = shortArrayOf(
        0, 1, 2,  0, 2, 3,   // bottom
        4, 5, 6,  4, 6, 7,   // top
        0, 1, 5,  0, 5, 4,   // back
        3, 2, 6,  3, 6, 7,   // front
        0, 3, 7,  0, 7, 4,   // left
        1, 2, 6,  1, 6, 5    // right
    )

    // ── Initialisation ────────────────────────────────────────────────────────

    fun createOnGlThread() {
        val vShader = compileShader(GLES20.GL_VERTEX_SHADER,   vertexShaderSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)

        program = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
        }

        posAttrib    = GLES20.glGetAttribLocation(program,  "a_Position")
        mvpUniform   = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")

        vertexBuffer = ByteBuffer
            .allocateDirect(cubeVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(cubeVertices); position(0) }

        indexBuffer = ByteBuffer
            .allocateDirect(cubeIndices.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .apply { put(cubeIndices); position(0) }
    }

    // ── Per-frame draw ────────────────────────────────────────────────────────

    /**
     * Draw all pillars for the current frame.
     *
     * @param viewProjectionMatrix column-major 4×4 float array (VP matrix from ARCore camera).
     * @param pillars              list of active [PillarData] objects.
     */
    fun draw(viewProjectionMatrix: FloatArray, pillars: List<PillarData>) {
        if (program == 0) return

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        for (pillar in pillars) {
            if (pillar.anchor.trackingState != com.google.ar.core.TrackingState.TRACKING) continue

            val pose = pillar.anchor.pose
            val modelMatrix = FloatArray(16)
            pose.toMatrix(modelMatrix, 0)

            // Scale: width 0.15 m, height proportional to RSSI, depth 0.15 m
            val sx = 0.15f
            val sy = pillar.heightMetres
            val sz = 0.15f
            val scaleMatrix = floatArrayOf(
                sx,  0f,  0f, 0f,
                0f, sy,   0f, 0f,
                0f,  0f,  sz, 0f,
                0f,  0f,  0f, 1f
            )
            val scaledModel = FloatArray(16)
            android.opengl.Matrix.multiplyMM(scaledModel, 0, modelMatrix, 0, scaleMatrix, 0)

            val mvp = FloatArray(16)
            android.opengl.Matrix.multiplyMM(mvp, 0, viewProjectionMatrix, 0, scaledModel, 0)

            GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)

            val (r, g, b) = pillar.rgbColor
            GLES20.glUniform4f(colorUniform, r, g, b, 0.85f)

            vertexBuffer?.let { buf ->
                GLES20.glEnableVertexAttribArray(posAttrib)
                GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, 12, buf)
            }

            indexBuffer?.let { buf ->
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, cubeIndices.size, GLES20.GL_UNSIGNED_SHORT, buf)
            }

            GLES20.glDisableVertexAttribArray(posAttrib)
        }

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }
}

/**
 * Everything needed to render a single pillar for one scan result.
 */
data class PillarData(
    val anchor       : Anchor,
    val scanResult   : WifiScanResult,
    val heightMetres : Float,
    val rgbColor     : Triple<Float, Float, Float>
) {
    companion object {
        /**
         * Map an RSSI value (typically -30 to -100 dBm) to a pillar height
         * between [minH] and [maxH] metres using linear interpolation.
         */
        fun rssiToHeight(rssi: Int, minH: Float = 0.10f, maxH: Float = 1.0f): Float {
            val clamped = rssi.coerceIn(-100, -30)
            // rssi = -30 → maxH, rssi = -100 → minH
            val t = (clamped - (-100f)) / (-30f - (-100f))
            return minH + t * (maxH - minH)
        }

        /**
         * Map a [SignalQuality] to an OpenGL-normalised RGB triple.
         */
        fun qualityToRgb(quality: SignalQuality): Triple<Float, Float, Float> {
            val color = Color.parseColor(quality.colorHex)
            return Triple(
                Color.red(color)   / 255f,
                Color.green(color) / 255f,
                Color.blue(color)  / 255f
            )
        }
    }
}
