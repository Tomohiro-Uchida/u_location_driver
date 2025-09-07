
import 'u_location_driver_platform_interface.dart';

class ULocationDriver {

  Future<String?> initialize1() {
    return ULocationDriverPlatform.instance.initialize1();
  }

  Future<String?> initialize2() {
    return ULocationDriverPlatform.instance.initialize2();
  }

  Future<String?> activate() {
    return ULocationDriverPlatform.instance.activate();
  }

  Future<String?> inactivate() {
    return ULocationDriverPlatform.instance.inactivate();
  }

}
