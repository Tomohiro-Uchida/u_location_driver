import Flutter
import UIKit
import CoreLocation

@available(iOS 17.0, *)
public class ULocationDriverPlugin: NSObject, FlutterPlugin, CLLocationManagerDelegate, @unchecked Sendable {
 
  private let locationPermissionState_Initial = 0
  private let locationPermissionState_Permitting = 1
  private let locationPermissionState_Permitted = 2
  private var locationPermissionState: Int = 0 // locationPermissionState_Initial

  public let mainState_Stopped = 10
  public let mainState_ActiveForeground = 20
  public let mainState_ActiveBackground = 30
  public let mainState_ActiveTerminated = 40
  public let mainState_TemporaryExecuteInBackground = 50
  public let mainState_TemporaryExecuteInTerminated = 60
  public var mainState: Int = 10 // mainState_Stopped

  private var memoryActivate: Bool = false;
  
  public static var shared = ULocationDriverPlugin()
  public let clLocationManager = CLLocationManager() // アクセス可能にする
  
  var channel = FlutterMethodChannel()
  
  static var fromDartChannel = FlutterMethodChannel()
  static var toDartChannel = FlutterMethodChannel()

  var backgroundLocation: CLLocation?

  private override init() {
    super.init()
    self.clLocationManager.delegate = self
    mainState = mainState_Stopped
    
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
    if (mainState == mainState_Stopped) {
      return
    }
    mainState = mainState_ActiveForeground
    debugPrint("mainState is set to activeForeground")
    permissionStateMachine()
  }
  
  @objc func viewDidEnterBackground(_ notification: Notification?) {
    if (mainState == mainState_Stopped) {
      return
    }
    mainState = mainState_ActiveBackground
    debugPrint("mainState is set to activeBackground")
    permissionStateMachine()
  }
  
  @objc func viewWillTerminate(_ notification: Notification?) {
    if (mainState == mainState_Stopped) {
      return
    }
    mainState = mainState_ActiveTerminated
    debugPrint("mainState is set to activeTerminated")
    permissionStateMachine()
  }
    
  public static func register(with registrar: FlutterPluginRegistrar) {
    fromDartChannel = FlutterMethodChannel(name: "com.jimdo.uchida001tmhr.u_location_driver/fromDart", binaryMessenger: registrar.messenger())
    toDartChannel = FlutterMethodChannel(name: "com.jimdo.uchida001tmhr.u_location_driver/toDart", binaryMessenger: registrar.messenger())
    let instance = ULocationDriverPlugin.shared
    registrar.addMethodCallDelegate(instance, channel: fromDartChannel)
  }

  public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    debugPrint("ULocationDriverPlugin() -> locationManagerDidChangeAuthorization()")
    if (clLocationManager.authorizationStatus != .notDetermined) {
      permissionStateMachine()
    }
  }
  
  func permissionStateMachine() {
    debugPrint("ULocationDriverPlugin() -> permissionStateMachine()")
    switch (clLocationManager.authorizationStatus) {
    case .notDetermined:
      debugPrint("ULocationDriverPlugin() -> permissionStateMachine() -> .notDetermined")
      locationPermissionState = locationPermissionState_Initial
      clLocationManager.requestWhenInUseAuthorization()
      break
    case .restricted:
      debugPrint("ULocationDriverPlugin() -> permissionStateMachine() -> .restricted")
      locationPermissionState = locationPermissionState_Initial
      break
    case .denied:
      debugPrint("ULocationDriverPlugin() -> permissionStateMachine() -> .denied")
      locationPermissionState = locationPermissionState_Initial
      break
    case .authorizedWhenInUse:
      debugPrint("ULocationDriverPlugin() -> permissionStateMachine() -> .authorizedWhenInUse")
      locationPermissionState = locationPermissionState_Permitting
      clLocationManager.requestAlwaysAuthorization()
      break
    case .authorizedAlways:
      debugPrint("ULocationDriverPlugin() -> permissionStateMachine() -> .authorizedAlways: mainState = \(mainState)")
      locationPermissionState = locationPermissionState_Permitted
      mainStateMachine()
      ULocationDriverPlugin.toDartChannel.invokeMethod("readyForLocation", arguments: nil)
      break
    @unknown default:
      break
    }
  }
  
  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "initialize":
      if (clLocationManager.authorizationStatus == .notDetermined) {
        permissionStateMachine()
      }
      result("ACK")
    case "activate":
      debugPrint("ULocationDriverPlugin() -> handle() -> activate: mainState = \(mainState)")
      switch (locationPermissionState ) {
      case locationPermissionState_Initial, locationPermissionState_Permitting:
        memoryActivate = true;
        result("ACK")
        break;
      case locationPermissionState_Permitted:
        switch (mainState) {
        case mainState_Stopped:
          debugPrint("ULocationDriverPlugin() -> handle() -> activate -> stopped")
          mainState = mainState_ActiveForeground
          mainStateMachine()
          break;
        case mainState_ActiveForeground:
          debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeForeground")
          mainStateMachine()
          break
        case mainState_ActiveBackground:
          debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeBackground")
          mainState = mainState_TemporaryExecuteInBackground
          mainStateMachine()
          break
        case mainState_ActiveTerminated:
          debugPrint("ULocationDriverPlugin() -> handle() -> activate -> activeTerminated")
          mainState = mainState_TemporaryExecuteInTerminated
          mainStateMachine()
          break
        default:
          break
        }
        result("ACK")
        break
      default:
        result("ACK")
      }
      break
    case "deactivate":
      debugPrint("ULocationDriverPlugin() -> handle() -> deactivate")
      mainState = mainState_Stopped
      mainStateMachine()
      result("ACK")
    default:
      result(FlutterMethodNotImplemented)
    }
  }
  
  public func mainStateMachine() {
    debugPrint("ULocationDriverPlugin() -> mainStateMachine()")
    switch (mainState) {
    case mainState_Stopped:
      debugPrint("ULocationDriverPlugin() -> mainStateMachine() -> stopped")
      clLocationManager.stopUpdatingLocation()
      clLocationManager.stopMonitoringSignificantLocationChanges()
      break
    case mainState_ActiveForeground, mainState_TemporaryExecuteInBackground, mainState_TemporaryExecuteInTerminated:
      debugPrint("ULocationDriverPlugin() -> mainStateMachine() -> activeForegroud etc")
      clLocationManager.delegate = self
      // clLocationManager.distanceFilter = kCLDistanceFilterNone
      clLocationManager.distanceFilter  = 10.0
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyBest
      // clLocationManager.desiredAccuracy = kCLLocationAccuracyReduced
      clLocationManager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
      clLocationManager.startUpdatingLocation()
      debugPrint("ULocationDriverPlugin() -> startUpdatingLocation in activeForeground/temporaryForegroundFromBackground")
      break
    case mainState_ActiveBackground, mainState_ActiveTerminated:
      debugPrint("ULocationDriverPlugin() -> mainStateMachine() -> activeBackground etc")
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
      mainStateMachine()
    }
  }

  public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    // エラーが発生した際に実行したい処理
    mainStateMachine()
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
 
}
