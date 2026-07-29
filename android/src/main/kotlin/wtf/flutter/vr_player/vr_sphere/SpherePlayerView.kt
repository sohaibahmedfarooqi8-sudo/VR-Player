package wtf.flutter.vr_player.vr_sphere

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import kotlin.math.abs

class SpherePlayerView(
    private val context: Context,
    viewId: Int,
    args: Any?,
    messenger: BinaryMessenger
) : PlatformView, SensorEventListener {

   private val interactionMode = (args as? HashMap<*, *>)?.get("interactionMode") as? String ?: "both"
    private val videoUrl = (args as? HashMap<*, *>)?.get("videoUrl") as? String
    private val shape = (args as? HashMap<*, *>)?.get("shape") as? String ?: "sbs"

    private val glView = GLSurfaceView(context)
    private val renderer: SphereRenderer
    private var player: ExoPlayer? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val channel = MethodChannel(messenger, "sphere_player_$viewId")

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
       glView.setEGLContextClientVersion(2)
        renderer = SphereRenderer { surfaceTexture ->
            renderer.shape = shape
            (context as android.app.Activity).runOnUiThread {
                player = ExoPlayer.Builder(context).build().apply {
                    setVideoSurface(Surface(surfaceTexture))
                    videoUrl?.let { setMediaItem(MediaItem.fromUri(it)) }
                    prepare()
                    playWhenReady = true
                    addListener(object : androidx.media3.common.Player.Listener {
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
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (interactionMode == "touch" || interactionMode == "both") {
            glView.setOnTouchListener { _, event -> handleTouch(event); true }
        }
        if (interactionMode == "motion" || interactionMode == "both") {
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
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

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                renderer.yaw -= dx * 0.3f
                renderer.pitch = (renderer.pitch - dy * 0.3f).coerceIn(-89f, 89f)
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val dt = 1f / 60f
        renderer.yaw -= Math.toDegrees((event.values[1] * dt).toDouble()).toFloat()
        renderer.pitch = (renderer.pitch - Math.toDegrees((event.values[0] * dt).toDouble()).toFloat())
            .coerceIn(-89f, 89f)
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    override fun getView() = glView

    override fun dispose() {
        sensorManager.unregisterListener(this)
        player?.release()
    }
}
