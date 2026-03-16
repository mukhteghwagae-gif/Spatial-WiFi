package com.wifi.visualizer.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val TAG = "BackgroundRenderer"

/**
 * Renders the ARCore camera image as a full-screen background quad.
 *
 * Correctly uses [Frame.transformCoordinates2d] so the UV coordinates are
 * always right regardless of device orientation or display rotation.
 *
 * Call [createOnGlThread] once from onSurfaceCreated, then [draw] every frame.
 */
class BackgroundRenderer {

    private var quadProgram       = 0
    private var quadPositionParam = 0
    private var quadTexCoordParam = 0
    private var textureId         = -1

    // NDC positions for the full-screen quad (two triangles as a strip)
    private val quadCoords = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )

    // UV coordinates — updated each frame via transformCoordinates2d
    // Default initialised to identity mapping; updated before first draw
    private var texCoordsDirty = true
    private val texCoordsUntransformed = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )
    private var transformedTexCoords = texCoordsUntransformed.copyOf()

    private val vertexShaderSrc = """
        attribute vec4 a_Position;
        attribute vec2 a_TexCoord;
        varying   vec2 v_TexCoord;
        void main() {
            gl_Position = a_Position;
            v_TexCoord  = a_TexCoord;
        }
    """.trimIndent()

    private val fragmentShaderSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 v_TexCoord;
        uniform samplerExternalOES u_Texture;
        void main() {
            gl_FragColor = texture2D(u_Texture, v_TexCoord);
        }
    """.trimIndent()

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER,   vertexShaderSrc)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)
        quadProgram = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
        }
        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
    }

    fun getTextureId(): Int = textureId

    /**
     * Draw the camera background for the current [frame].
     * Must be called BEFORE any AR content so it renders behind everything.
     */
    fun draw(frame: Frame) {
        // Ask ARCore to give us correctly-oriented UV coords each time the
        // display geometry changes (orientation flip, etc.)
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                texCoordsUntransformed,
                com.google.ar.core.Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoords
            )
            texCoordsDirty = true
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(quadProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        val posBuf = ByteBuffer.allocateDirect(quadCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(quadCoords); it.position(0) }

        val texBuf = ByteBuffer.allocateDirect(transformedTexCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .also { it.put(transformedTexCoords); it.position(0) }

        GLES20.glVertexAttribPointer(quadPositionParam, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

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
