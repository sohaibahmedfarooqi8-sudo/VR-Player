import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

typedef SpherePlayerCreatedCallback = void Function(SpherePlayerController controller);

class SpherePlayer extends StatefulWidget {
  final double width;
  final double height;
  final String videoUrl;
final String interactionMode; // 'touch' | 'motion' | 'both'
  final String shape; // 'sbs' | 'cardboard'
  final SpherePlayerCreatedCallback onCreated;

  const SpherePlayer({
    required this.width,
    required this.height,
    required this.videoUrl,
    required this.onCreated,
    this.interactionMode = 'both',
   this.shape = 'sbs',
    super.key,
  });

  @override
  State<SpherePlayer> createState() => _SpherePlayerState();
}

class _SpherePlayerState extends State<SpherePlayer> {
  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: widget.width,
      height: widget.height,
      child: PlatformViewLink(
        viewType: 'sphere_player_view',
      surfaceFactory: (context, controller) {
  return AndroidViewSurface(
    controller: controller as AndroidViewController,
    gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
    hitTestBehavior: PlatformViewHitTestBehavior.opaque,
  );
},
        onCreatePlatformView: (params) {
          return PlatformViewsService.initExpensiveAndroidView(
            id: params.id,
            viewType: 'sphere_player_view',
            layoutDirection: TextDirection.ltr,
            creationParams: {
              'videoUrl': widget.videoUrl,
              'interactionMode': widget.interactionMode,
              'shape': widget.shape,
            },
            creationParamsCodec: const StandardMessageCodec(),
            onFocus: () {},
          )
            ..addOnPlatformViewCreatedListener((id) {
              params.onPlatformViewCreated(id);
              widget.onCreated(SpherePlayerController(id));
            })
            ..create();
        },
      ),
    );
  }
}

class SpherePlayerController {
  final int viewId;
  late final MethodChannel _channel;
  final _readyCompleter = Completer<int>(); // resolves with duration(ms)
  VoidCallback? onFinished;

  SpherePlayerController(this.viewId) {
    _channel = MethodChannel('sphere_player_$viewId');
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onReady':
          if (!_readyCompleter.isCompleted) {
            _readyCompleter.complete((call.arguments as Map)['duration'] as int? ?? 0);
          }
          break;
        case 'onFinished':
          onFinished?.call();
          break;
      }
    });
  }

  Future<int> get durationMs => _readyCompleter.future;
  Future<void> play() => _channel.invokeMethod('play');
  Future<void> pause() => _channel.invokeMethod('pause');
  Future<void> seekTo(int ms) => _channel.invokeMethod('seekTo', ms);
  Future<int> getPosition() async => await _channel.invokeMethod('getPosition') ?? 0;
}
