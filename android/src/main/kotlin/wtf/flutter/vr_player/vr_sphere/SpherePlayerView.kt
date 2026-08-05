package wtf.flutter.vr_player.vr_sphere

import android.content.Context
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

class SpherePlayerView(
    private val context: Context,
    viewId: Int,
    args: Any?,
    messenger: BinaryMessenger
) : PlatformView, SensorEventListener {

    private val interactionMode = (args as? HashMap<*, *>)?.get("interactionMode") as? String ?: "both"
    private val videoUrl = (args as? HashMap<*, *>)?.get("videoUrl") as? String
    private val shape = (args as? HashMap<*, *>)?.get("shape") as? String ?: "sbs"
    private val mediaType = (args as? HashMap<*, *>)?.get("mediaType") as? String ?: "video"

    private val glView = GLSurfaceView(context).apply {
        setEGLConfigChooser(8, 8, 8, 0, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
    }
    private val renderer: SphereRenderer
    private var player: ExoPlayer? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val channel = MethodChannel(messenger, "sphere_player_$viewId")
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchYawOffset = 0f
    private var touchPitchOffset = 0f

    init {
        glView.setEGLContextClientVersion(2)
        renderer = SphereRenderer { surfaceTexture ->
    renderer.shape = shape
    renderer.mediaType = mediaType
    if (mediaType == "image") {
        decodeAndUploadImage()
    } else {
        // FIX: set a generous default buffer size BEFORE creating the Surface,
        // so MediaCodec.configure() sees a properly-sized target from the start.
        surfaceTexture.setDefaultBufferSize(3840, 2160)

        (context as android.app.Activity).runOnUiThread {
         val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
    .setEnableDecoderFallback(true)
player = ExoPlayer.Builder(context, renderersFactory).build().apply {
                setVideoSurface(Surface(surfaceTexture))
                videoUrl?.let { setMediaItem(MediaItem.fromUri(it)) }
                prepare()
                playWhenReady = true
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        // Still update to the *actual* size once known, for non-4K videos
                        surfaceTexture.setDefaultBufferSize(videoSize.width, videoSize.height)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == androidx.media3.common.Player.STATE_READY) {
                            channel.invokeMethod("onReady", mapOf("duration" to duration))
                        } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                            channel.invokeMethod("onFinished", null)
                        }
                    }
                })
            }
        }
    }
}
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (interactionMode == "touch" || interactionMode == "both") {
            glView.setOnTouchListener { _, event -> handleTouch(event); true }
        }
        if (interactionMode == "motion" || interactionMode == "both") {
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "play" -> { player?.play(); result.success(null) }
                "pause" -> { player?.pause(); result.success(null) }
                "seekTo" -> {
                    val ms = (call.arguments as? Int) ?: 0
                    player?.seekTo(ms.toLong())
                    result.success(null)
                }
                "getPosition" -> result.success(player?.currentPosition?.toInt() ?: 0)
                else -> result.notImplemented()
            }
        }
    }

    /** Decodes the photo off the GL/main thread, then uploads via queueEvent. */
    private fun decodeAndUploadImage() {
        Thread {
            val bitmap = videoUrl?.let { BitmapFactory.decodeFile(it) }
            if (bitmap != null) {
                glView.queueEvent { renderer.setStaticBitmap(bitmap) }
            }
            mainHandler.post { channel.invokeMethod("onReady", mapOf("duration" to 0)) }
        }.start()
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                touchYawOffset -= dx * 0.3f
                touchPitchOffset = (touchPitchOffset - dy * 0.3f).coerceIn(-89f, 89f)
                renderer.yaw = smoothedYaw + touchYawOffset
                renderer.pitch = (smoothedPitch + touchPitchOffset).coerceIn(-89f, 89f)
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
    }

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val remappedRotationMatrix = FloatArray(9)
    private var smoothedYaw = 0f
    private var smoothedPitch = 0f
    private var yawInitialized = false
    private val motionSmoothingFactor = 0.35f

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_X, SensorManager.AXIS_Z,
            remappedRotationMatrix
        )
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        val rawYaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val rawPitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()

        if (!yawInitialized) {
            smoothedYaw = rawYaw
            smoothedPitch = rawPitch
            yawInitialized = true
        } else {
            smoothedYaw += (rawYaw - smoothedYaw) * motionSmoothingFactor
            smoothedPitch += (rawPitch - smoothedPitch) * motionSmoothingFactor
        }

        renderer.yaw = smoothedYaw + touchYawOffset
        renderer.pitch = (smoothedPitch + touchPitchOffset).coerceIn(-89f, 89f)
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    override fun getView() = glView

    override fun dispose() {
        sensorManager.unregisterListener(this)
        player?.release()
    }
}
