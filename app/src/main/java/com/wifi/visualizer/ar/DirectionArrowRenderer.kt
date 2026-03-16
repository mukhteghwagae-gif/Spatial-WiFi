package com.wifi.visualizer.ar

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a world-space directional arrow (cone + shaft) that always points
 * from the camera toward the nearest strong-signal zone.
 *
 * The arrow floats at eye-level 1.5 m in front of the camera, rotates in the
 * XZ plane, and is colour-coded by the predicted RSSI at the target.
 *
 * It is drawn in clip-space so it is always fully visible regardless of camera
 * orientation, making it a navigation aid rather than a world anchor.
 */
class DirectionArrowRenderer {

    private var program       = 0
    private var posAttrib     = 0
    private var mvpUniform    = 0
    private var colorUniform  = 0
    private var vbo           = IntArray(1)

    // Arrow geometry in local space: shaft along +Z, cone at tip
    // 3 floats per vertex (x, y, z)
    private val vertices = floatArrayOf(
        // Shaft (rectangle in XZ)
        -0.03f, 0f,  0f,
         0.03f, 0f,  0f,
         0.03f, 0f,  0.4f,
        -0.03f, 0f,  0.4f,
        // Arrowhead triangle
        -0.09f, 0f,  0.4f,
         0.09f, 0f,  0.4f,
         0.0f,  0f,  0.7f
    )
    private val indices = shortArrayOf(
        0,1,2, 0,2,3,   // shaft
        4,5,6            // head
    )

    private val vShaderSrc = """
        uniform   mat4 u_MVP;
        attribute vec4 a_Position;
        void main(){ gl_Position = u_MVP * a_Position; }
    """.trimIndent()

    private val fShaderSrc = """
        precision mediump float;
        uniform vec4 u_Color;
        void main(){ gl_FragColor = u_Color; }
    """.trimIndent()

    fun createOnGlThread() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER,   vShaderSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fShaderSrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs); GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        posAttrib   = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform  = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        GLES20.glGenBuffers(1, vbo, 0)
        val buf = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(vertices); position(0) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * @param viewProjectionMatrix VP matrix from camera
     * @param cameraPose           current camera pose
     * @param arrowAngleDeg        horizontal rotation angle in world degrees (0 = +Z)
     * @param predictedRssi        RSSI at target (drives arrow colour)
     */
    fun draw(
        viewProjectionMatrix : FloatArray,
        cameraX: Float, cameraY: Float, cameraZ: Float,
        arrowAngleDeg        : Float,
        predictedRssi        : Int
    ) {
        if (program == 0) return

        // Position arrow 1.5 m in front of camera at eye level
        val modelMatrix = FloatArray(16)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, cameraX, cameraY - 0.3f, cameraZ)
        Matrix.rotateM(modelMatrix, 0, arrowAngleDeg, 0f, 1f, 0f)

        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, viewProjectionMatrix, 0, modelMatrix, 0)

        val quality = com.wifi.visualizer.data.model.SignalQuality.from(predictedRssi)
        val color = android.graphics.Color.parseColor(quality.colorHex)

        val idxBuf = ByteBuffer.allocateDirect(indices.size * 2).order(ByteOrder.nativeOrder())
            .asShortBuffer().apply { put(indices); position(0) }

        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniform4f(colorUniform,
            android.graphics.Color.red(color)   / 255f,
            android.graphics.Color.green(color) / 255f,
            android.graphics.Color.blue(color)  / 255f,
            0.9f)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glVertexAttribPointer(posAttrib, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.size, GLES20.GL_UNSIGNED_SHORT, idxBuf)
        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
    }
}
