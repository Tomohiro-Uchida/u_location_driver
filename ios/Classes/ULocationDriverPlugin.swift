import Flutter
import UIKit
import CoreLocation

@available(iOS 17.0, *)
public class ULocationDriverPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate, @unchecked Sendable {
 
  public let stopped = 0
  public let activeForeground = 1
  public let activeBackground = 2
  public let activeTerminated = 3
  public let temporaryExecuteInBackground = 4
  public let temporaryExecuteInTerminated = 5
  public var locationMonitoringStatus: Int = 0
  
  public static var shared = ULocationDriverPlugin()
  public let clLocationManager = CLLocationManager() // アクセス可能にする
  
  var channel = FlutterMethodChannel()
  
  // private static let backgroundSession = CLBackgroundActivitySession()
  // private let clLocationManager = CLLocationManager()
  static var fromDartChannel = FlutterMethodChannel()
  static var toDartChannel = FlutterMethodChannel()

  var backgroundLocation: CLLocation?
  
  // static var callbackHandler: String = ""
  // static var flutterEngineGroup: FlutterEngineGroup!

  private override init() {
    super.init()
    self.clLocationManager.delegate = self
    locationMonitoringStatus = stopped
    
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(viewWillEnterForeground(_:)),
        name: UIApplication.willEnterForegroundNotification,
        object: nil
    )
    
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(viewDidEnterBackground(_:)),
        name: UIApplication.didEnterBackgroundNotification,
        object: nil
    )
    
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(viewWillTerminate(_:)),
        name: UIApplication.willTerminateNotification,
        object: nil
    )
  }
  
  @objc func viewWillEnterForeground(_ notification: Notification?) {
    if (locationMonitoringStatus == stopped) {
      return
    }
    locationMonitoringStatus = activeForeground
    debugPrint("locationMonitoringStatus is set to activeForeground")
    stateMachine()
  }
  
  @objc func viewDidEnterBackground(_ notification: Notification?) {
    if (locationMonitoringStatus == stopped) {
      return
    }
    locationMonitoringStatus = activeBackground
    debugPrint("locationMonitoringStatus is set to activeBackground")
    stateMachine()
  }
  
  @objc func viewWillTerminate(_ notification: Notification?) {
    if (locationMonitoringStatus == stopped) {
      return
    }
    locationMonitoringStatus = activeTerminated
    debugPrint("locationMonitoringStatus is set to activeTerminated")
    stateMachine()
  }
    
  public static func register(with registrar: FlutterPluginRegistrar) {
    fromDartChannel = FlutterMethodChannel(name: "com.jimdo.uchida001tmhr.u_location_driver/fromDart", binaryMessenger: registrar.messenger())
    toDartChannel = FlutterMethodChannel(name: "com.jimdo.uchida001tmhr.u_location_driver/toDart", binaryMessenger: registrar.messenger())
    let instance = ULocationDriverPlugin.shared
    registrar.addMethodCallDelegate(instance, channel: fromDartChannel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "initialize1":
      locationMonitoringStatus = stopped
      stateMachine(startLocationUpdate: true)
      result("ACK")
    case "initialize2":
      locationMonitoringStatus = stopped
      stateMachine(startLocationUpdate: true)
      result("ACK")
    case "activate":
      debugPrint("ULocationDriverPlugin() -> handle() -> activate: locationMonitoringStatus = \(locationMonitoringStatus)")
      switch (locationMonitoringStatus) {
      case stopped:
        debugPrint("ULocationDriverPlugin() -> handle() -> activate -> stopped")
        locationMonitoringStatus = activeForeground
        stateMachine(startLocationUpdate: true)
        break;
      case activeForeground:
        debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeForeground")
        stateMachine(startLocationUpdate: true)
        break
      case activeBackground:
        debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeBackground")
        locationMonitoringStatus = temporaryExecuteInBackground
        stateMachine(startLocationUpdate: true)
        break
      case activeTerminated:
        debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeTerminated")
        locationMonitoringStatus = temporaryExecuteInTerminated
        stateMachine(startLocationUpdate: true)
        break
      default:
        break
      }
      result("ACK")
    case "deactivate":
      debugPrint("ULocationDriverPlugin() -> handle() -> deactivate")
      locationMonitoringStatus = stopped
      stateMachine()
      result("ACK")
    default:
      result(FlutterMethodNotImplemented)
    }
  }
  
  static func informLocationToDart(location: CLLocation) {
    /// DateFomatterクラスのインスタンス生成
    let dateFormatter = DateFormatter()
     
    /// カレンダー、ロケール、タイムゾーンの設定（未指定時は端末の設定が採用される）
    dateFormatter.calendar = Calendar(identifier: .gregorian)
    dateFormatter.locale = Locale(identifier: "ja_JP")
    dateFormatter.timeZone = TimeZone(identifier:  "Asia/Tokyo")
     
    /// 変換フォーマット定義（未設定の場合は自動フォーマットが採用される）
    dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
     
    /// データ変換（Date→テキスト）
    let dateString = dateFormatter.string(from: Date())
    let message = "\(dateString),\(location.coordinate.latitude),\(location.coordinate .longitude)"
    debugPrint("ULocationDriverPlugin() -> informLocationToDart() -> message -> \(message)")
    toDartChannel.invokeMethod("location", arguments: message)
  }
 
  func stateMachine(startLocationUpdate: Bool = false) {
    debugPrint("ULocationDriverPlugin() -> stateMachine()")
    switch (clLocationManager.authorizationStatus) {
    case .notDetermined:
      debugPrint("ULocationDriverPlugin() -> .notDetermined")
      clLocationManager.requestWhenInUseAuthorization()
      break
    case .restricted:
      debugPrint("ULocationDriverPlugin() -> .restricted")
      break
    case .denied:
      debugPrint("ULocationDriverPlugin() -> .denied")
      break
    case .authorizedAlways:
      debugPrint("ULocationDriverPlugin() -> .authorizedAlways: locationMonitoringStatus = \(locationMonitoringStatus)")
      switch (locationMonitoringStatus) {
      case stopped:
        debugPrint("ULocationDriverPlugin() -> .authorizedAlways -> stopped")
        clLocationManager.stopUpdatingLocation()
        clLocationManager.stopMonitoringSignificantLocationChanges()
        break;
      case activeForeground, activeBackground, activeTerminated, temporaryExecuteInBackground, temporaryExecuteInTerminated:
        debugPrint("ULocationDriverPlugin() -> .authorizedAlways -> actiforeground etc")
        locationMonitoring()
        break
      default:
        break
      }
      break
    case .authorizedWhenInUse:
      debugPrint("ULocationDriverPlugin() -> .authorizedWhenInUse")
      clLocationManager.requestAlwaysAuthorization()
      break
    @unknown default:
      break
    }
  }
  
  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    debugPrint("ULocationDriverPlugin() -> locationManagerDidChangeAuthorization() : locationMonitoringStatus = \(locationMonitoringStatus)")
    stateMachine(startLocationUpdate: true)
  }
  
  public func locationMonitoring() {
    debugPrint("ULocationDriverPlugin() -> locationMonitoring()")
    switch (locationMonitoringStatus) {
    case stopped:
      debugPrint("ULocationDriverPlugin() -> locationMonitoring() -> stopped")
      clLocationManager.stopUpdatingLocation()
      clLocationManager.stopMonitoringSignificantLocationChanges()
      break
    case activeForeground, temporaryExecuteInBackground, temporaryExecuteInTerminated:
      debugPrint("ULocationDriverPlugin() -> locationMonitoring() -> activeForegroud etc")
      clLocationManager.delegate = self
      // clLocationManager.distanceFilter = kCLDistanceFilterNone
      clLocationManager.distanceFilter  = 10.0
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyBest
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyReduced
      clLocationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
      clLocationManager.startUpdatingLocation()
      debugPrint("ULocationDriverPlugin() -> startUpdatingLocation in activeForeground/temporaryForegroundFromBackground")
      break
    case activeBackground, activeTerminated:
      debugPrint("ULocationDriverPlugin() -> locationMonitoring() -> activeBackground etc")
      clLocationManager.delegate = self
      clLocationManager.allowsBackgroundLocationUpdates = true
      clLocationManager.pausesLocationUpdatesAutomatically = false
      // clLocationManager.distanceFilter = kCLDistanceFilterNone
      // clLocationManager.distanceFilter  = 10.0
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyBest
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyReduced
      clLocationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
      if (CLLocationManager.significantLocationChangeMonitoringAvailable()) {
        clLocationManager.startMonitoringSignificantLocationChanges()
        debugPrint("ULocationDriverPlugin() -> startMonitoringSignificantLocationChanges()")
      }
      // clLocationManager.startUpdatingLocation()
      // debugPrint("ULocationDriverPlugin() -> startUpdatingLocation in activeBackground/activeTerminated")
      break
    default:
      break
    }
  }

  public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    debugPrint("ULocationDriverPlugin() -> locationManager()")
    if (locations.last != nil) {
      ULocationDriverPlugin.informLocationToDart(location: locations.last!)
      switch (locationMonitoringStatus) {
      case stopped:
        clLocationManager.stopUpdatingLocation()
        clLocationManager.stopMonitoringSignificantLocationChanges()
        break
      case temporaryExecuteInBackground:
        locationMonitoring()
        locationMonitoringStatus = activeBackground
        break
      case temporaryExecuteInTerminated:
        locationMonitoring()
        locationMonitoringStatus = activeTerminated
        break
      default:
        locationMonitoring()
        break
      }
    }
  }

  public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    // エラーが発生した際に実行したい処理
    switch (locationMonitoringStatus) {
    case stopped:
      clLocationManager.stopUpdatingLocation()
      clLocationManager.stopMonitoringSignificantLocationChanges()
      break
    case temporaryExecuteInBackground:
      locationMonitoring()
      locationMonitoringStatus = activeBackground
      break
    case temporaryExecuteInTerminated:
      locationMonitoring()
      locationMonitoringStatus = activeTerminated
      break
    default:
      locationMonitoring()
      break
    }
  }
  
}
