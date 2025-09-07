
import 'u_location_driver_platform_interface.dart';

class ULocationDriver {

  Future<String?> initialize() {
    return ULocationDriverPlatform.instance.initialize();
  }

  Future<String?> activate() {
    return ULocationDriverPlatform.instance.activate();
  }

  Future<String?> inactivate() {
    return ULocationDriverPlatform.instance.inactivate();
  }

}
