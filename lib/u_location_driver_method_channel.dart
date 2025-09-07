import 'package:flutter/services.dart';
import 'u_location_driver_platform_interface.dart';

/// An implementation of [ULocationDriverPlatform] that uses method channels.
class MethodChannelULocationDriver extends ULocationDriverPlatform {
  /// The method channel used to interact with the native platform.
  final methodChannel = const MethodChannel("com.jimdo.uchida001tmhr.u_location_driver/fromDart");

  @override
  Future<String?> initialize() async {
    return await methodChannel.invokeMethod<String>("initialize");
  }

  @override
  Future<String?> activate() async {
    return await methodChannel.invokeMethod<String>("activate");
  }

  @override
  Future<String?> inactivate() async {
    return await methodChannel.invokeMethod<String>("inactivate");
  }

}
