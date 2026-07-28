package wtf.flutter.vr_player

import VideoViewFactory
import wtf.flutter.vr_player.vr_sphere.SpherePlayerViewFactory
import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.FlutterPlugin.FlutterPluginBinding
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** VrPlayer */
class VrPlayer : FlutterPlugin, MethodCallHandler {
    override fun onAttachedToEngine(flutterPluginBinding: FlutterPluginBinding) {
        val channel = MethodChannel(flutterPluginBinding.binaryMessenger, "vr_player")
        channel.setMethodCallHandler(VrPlayer());
       flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "plugins.vr_player/player_view",
            VideoViewFactory(flutterPluginBinding.binaryMessenger)
        )
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "sphere_player_view",
            SpherePlayerViewFactory(flutterPluginBinding.binaryMessenger)
        )
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPluginBinding) {}
}
