
import 'u_location_driver_platform_interface.dart';

class ULocationDriver {

  Future<String?> initialize() {
    return ULocationDriverPlatform.instance.initialize();
  }

  Future<String?> activate({int callbackHandle = 0}) {
    return ULocationDriverPlatform.instance.activate(callbackHandle: callbackHandle);
  }

  Future<String?> deactivate() {
    return ULocationDriverPlatform.instance.deactivate();
  }

}
