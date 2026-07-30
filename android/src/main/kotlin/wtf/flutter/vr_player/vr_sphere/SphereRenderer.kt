package wtf.flutter.vr_player.vr_sphere

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders ONE video texture onto a sphere, using ONE shared rotation state,
 * drawn TWICE (left/right viewport) per frame — this is what guarantees both
 * eyes always show the identical rotated frame, since there's only one
 * rotation number and one draw call per eye, not two separate players.
 */
class SphereRenderer(private val onSurfaceReady: (SurfaceTexture) -> Unit) : GLSurfaceView.Renderer {

    private val sphere = SphereGeometry()
    private var program = 0
    private var textureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    // Shared rotation state — updated externally by touch drag or gyro.
   // Shared rotation state — updated externally by touch drag or gyro.
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var shape = "sbs" // "sbs" | "cardboard"
    private val gapPx = 0.1

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uViewportSize;
        uniform float uCornerRadius;
        void main() {
            if (uCornerRadius > 0.0) {
                vec2 pos = gl_FragCoord.xy;
                vec2 halfSize = uViewportSize * 0.5;
                vec2 center = halfSize;
                vec2 d = abs(pos - center) - (halfSize - uCornerRadius);
                float dist = length(max(d, 0.0)) - uCornerRadius;
                if (dist > 0.0) discard;
            }
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = createProgram()
        textureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(textureId)
        onSurfaceReady(surfaceTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(stMatrix)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

       val halfWidth = (surfaceWidth - gapPx) / 2
        val cornerRadius = if (shape == "cardboard") halfWidth * 0.18f else 0f
        // Left eye
        GLES20.glViewport(0, 0, halfWidth, surfaceHeight)
        drawSphere(halfWidth, surfaceHeight, cornerRadius)
        // Right eye — same rotation, same texture, drawn again
        GLES20.glViewport(halfWidth + gapPx, 0, halfWidth, surfaceHeight)
        drawSphere(halfWidth, surfaceHeight, cornerRadius)
    }

   private fun drawSphere(vpWidth: Int, vpHeight: Int, cornerRadius: Float = 0f) {
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewportSize"), vpWidth.toFloat(), vpHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCornerRadius"), cornerRadius)

        Matrix.setIdentityM(viewMatrix, 0)
        Matrix.rotateM(viewMatrix, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, yaw, 0f, 1f, 0f)

        val aspect = vpWidth.toFloat() / vpHeight.toFloat()
       Matrix.perspectiveM(projMatrix, 0, 90f, aspect, 0.1f, 10f)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val stHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, sphere.vertexBuffer)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, sphere.texCoordBuffer)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indexBuffer)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
    }
}
