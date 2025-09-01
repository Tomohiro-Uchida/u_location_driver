import Flutter
import u_location_driver
import UIKit
import CoreLocation

@main
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    GeneratedPluginRegistrant.register(with: self)
    
    if launchOptions?[.location] != nil {
      print("App relaunched due to location event")
      // locationManager を適切に再セットアップ
      //  Pluginインスタンスにアクセス
      let plugin = ULocationDriverPlugin.shared
      plugin.clLocationManager.delegate = plugin
      plugin.locationMonitoringStatus = plugin.activeTerminated
      plugin.locationMonitoring()
    }
    
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
