import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'u_location_driver_method_channel.dart';

abstract class ULocationDriverPlatform extends PlatformInterface {
  /// Constructs a ULocationDriverPlatform.
  ULocationDriverPlatform() : super(token: _token);

  static final Object _token = Object();

  static ULocationDriverPlatform _instance = MethodChannelULocationDriver();

  /// The default instance of [ULocationDriverPlatform] to use.
  ///
  /// Defaults to [MethodChannelULocationDriver].
  static ULocationDriverPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [ULocationDriverPlatform] when
  /// they register themselves.
  static set instance(ULocationDriverPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> initialize() async {
    throw UnimplementedError('activate() has not been implemented.');
  }

  Future<String?> activate() async {
    throw UnimplementedError('activate() has not been implemented.');
  }

  Future<String?> inactivate() async {
    throw UnimplementedError('inactivate() has not been implemented.');
  }

}
