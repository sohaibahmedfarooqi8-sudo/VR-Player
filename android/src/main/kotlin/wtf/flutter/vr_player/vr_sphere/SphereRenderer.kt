package wtf.flutter.vr_player.vr_sphere

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders either a live video texture (SurfaceTexture, external OES) or a
 * static photo texture (plain 2D, uploaded once) onto a sphere, using ONE
 * shared yaw/pitch rotation state — same dual-eye-draw trick as before,
 * now shape-aware: "sbs" | "cardboard" (split) or "single" (full frame,
 * used for On Device photo/video).
 */
class SphereRenderer(private val onSurfaceReady: (SurfaceTexture) -> Unit) : GLSurfaceView.Renderer {

    private val sphere = SphereGeometry()

    // Video path — external OES texture fed by ExoPlayer via SurfaceTexture.
    private var videoProgram = 0
    private var videoTextureId = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val stMatrix = FloatArray(16)

    // Image path — plain 2D texture uploaded once from a decoded Bitmap.
    private var imageProgram = 0
    private var imageTextureId = 0
    @Volatile private var pendingBitmap: Bitmap? = null
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    @Volatile var shape = "sbs" // "sbs" | "cardboard" | "single"
    @Volatile var mediaType = "video" // "video" | "image"

    private val gapPx: Int
        get() = if (shape == "cardboard") 40 else 0

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

    private val videoFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uViewportSize;
        uniform float uCornerRadius;
        uniform vec2 uViewportOrigin;
        void main() {
            if (uCornerRadius > 0.0) {
                vec2 pos = gl_FragCoord.xy - uViewportOrigin;
                vec2 halfSize = uViewportSize * 0.5;
                vec2 d = abs(pos - halfSize) - (halfSize - uCornerRadius);
                float dist = length(max(d, 0.0)) - uCornerRadius;
                if (dist > 0.0) discard;
            }
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    private val imageFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture;
        uniform vec2 uViewportSize;
        uniform float uCornerRadius;
        uniform vec2 uViewportOrigin;
        void main() {
            if (uCornerRadius > 0.0) {
                vec2 pos = gl_FragCoord.xy - uViewportOrigin;
                vec2 halfSize = uViewportSize * 0.5;
                vec2 d = abs(pos - halfSize) - (halfSize - uCornerRadius);
                float dist = length(max(d, 0.0)) - uCornerRadius;
                if (dist > 0.0) discard;
            }
          gl_FragColor = texture2D(sTexture, vec2(vTexCoord.x, 1.0 - vTexCoord.y));
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        videoProgram = createProgram(vertexShaderCode, videoFragmentShaderCode)
        imageProgram = createProgram(vertexShaderCode, imageFragmentShaderCode)
        videoTextureId = createExternalTexture()
        surfaceTexture = SurfaceTexture(videoTextureId)
        onSurfaceReady(surfaceTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
    }

    /** Call via GLSurfaceView.queueEvent { } — must run on the GL thread. */
    fun setStaticBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
    }

    override fun onDrawFrame(gl: GL10?) {
        if (mediaType == "image") {
            pendingBitmap?.let { bmp ->
                if (imageTextureId == 0) imageTextureId = createTexture2D()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                pendingBitmap = null
            }
        } else {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (shape == "single") {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
            drawSphere(surfaceWidth, surfaceHeight, 0f, 0f)
            return
        }

        val halfWidth = (surfaceWidth - gapPx) / 2
        val cornerRadius = if (shape == "cardboard") halfWidth * 0.18f else 0f

        GLES20.glViewport(0, 0, halfWidth, surfaceHeight)
        drawSphere(halfWidth, surfaceHeight, cornerRadius, 0f)

        GLES20.glViewport(halfWidth + gapPx, 0, halfWidth, surfaceHeight)
        drawSphere(halfWidth, surfaceHeight, cornerRadius, (halfWidth + gapPx).toFloat())
    }

    private fun drawSphere(vpWidth: Int, vpHeight: Int, cornerRadius: Float, originX: Float) {
        val isImage = mediaType == "image"
        val program = if (isImage) imageProgram else videoProgram
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewportSize"), vpWidth.toFloat(), vpHeight.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCornerRadius"), cornerRadius)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uViewportOrigin"), originX, 0f)

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
        GLES20.glUniformMatrix4fv(stHandle, 1, false, if (isImage) identityMatrix else stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (isImage) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        }

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

    private fun createTexture2D(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(vertexCode: String, fragmentCode: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
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
