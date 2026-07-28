package wtf.flutter.vr_player.vr_sphere

import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class SpherePlayerViewFactory(private val messenger: BinaryMessenger) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: android.content.Context?, viewId: Int, args: Any?): PlatformView {
        return SpherePlayerView(context!!, viewId, args, messenger)
    }
}
