package com.jimdo.uchida001tmhr.u_location_driver

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.jimdo.uchida001tmhr.stepcounter.MyAlarmManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.BinaryMessenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ULocationDriverPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
  private lateinit var requestPermissionLauncherPostNotification: ActivityResultLauncher<String>
  private lateinit var requestPermissionLauncherFineLocation: ActivityResultLauncher<String>
  private lateinit var requestPermissionLauncherBackgroundLocation: ActivityResultLauncher<String>

  companion object {
    @SuppressLint("StaticFieldLeak")
    lateinit var thisContext: Context

    @SuppressLint("StaticFieldLeak")
    lateinit var thisActivity: Activity
    val fromDartChannelName = "com.jimdo.uchida001tmhr.u_location_driver/fromDart"
    val toDartChannelName = "com.jimdo.uchida001tmhr.u_location_driver/toDart"
    var fromDartChannel: MethodChannel? = null
    var toDartChannel: MethodChannel? = null
    var binaryMessengerToDart: BinaryMessenger? = null
    var myPackageName: String? = ""
    var backgroundFlutterEngine: FlutterEngine? = null
    var backgroundDartExecutor: DartExecutor? = null
    var fusedLocationClients = mutableListOf<FusedLocationProviderClient>()
    val activityStopped = 0
    val activityForeground = 1
    val activityBackground = 2
    val temporaryExecuteInBackground = 3
    var activityState = activityStopped
    val currentLocationRequestBuilder = CurrentLocationRequest.Builder().apply {
      setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    }.build()

    fun getProcessInfo(context: Context): ActivityManager.RunningAppProcessInfo? {
      val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val runningAppProcessInfoList = activityManager.runningAppProcesses
      for (processInfo in runningAppProcessInfoList) {
        if (processInfo.processName == myPackageName) {
          return processInfo
        }
      }
      return null
    }

    fun loadFlutterEngine(context: Context): FlutterEngine? {
      val processInfo = getProcessInfo(context)
      if (processInfo?.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
        println("ULocationDriverPlugin: loadFlutterEngine()")
        val flutterEngine = FlutterEngine(context)
        val dartEntrypoint = DartExecutor.DartEntrypoint.createDefault()
        flutterEngine.dartExecutor.executeDartEntrypoint(dartEntrypoint)
        return flutterEngine
      }
      return null
    }

    suspend fun informLocationToDart(location: Location?): Result<String> {
      println("ULocationDriverPlugin: informLocationToDart() : Start")
      return suspendCoroutine { continuation ->
        if (/* location != null && */ toDartChannel != null) {
          val locale = Locale.JAPAN
          val dateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
          val dateString = dateTimeFormatter.format(LocalDateTime.now())
          val message = "$dateString,${location?.latitude},${location?.longitude}"
          toDartChannel?.invokeMethod("location", message, object : MethodChannel.Result {
            override fun success(result: Any?) {
              continuation.resume(Result.success("OK"))
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
              continuation.resume(Result.failure(Exception(errorCode)))
            }

            override fun notImplemented() {
              continuation.resume(Result.failure(Exception("notImplemented")))
            }
          })
        } else {
          continuation.resume(Result.failure(Exception("location or toDartChannel == null")))
        }
      }
    }

    suspend fun getCurrentLocation(): Result<String> {
      println("ULocationDriverPlugin: getCurrentLocation() : fusedLocationClients = $fusedLocationClients")
      return suspendCoroutine { continuation ->
        fusedLocationClients.forEach { it ->
          it.getCurrentLocation(currentLocationRequestBuilder, null)
            .addOnSuccessListener { it ->
              println("ULocationDriverPlugin: getCurrentLocation() -> OnSuccessListener ")
              CoroutineScope(Dispatchers.Main).launch {
                informLocationToDart(it)
              }
              continuation.resume(Result.success("OK"))
            }
            .addOnFailureListener { it ->
              continuation.resume(Result.failure(Exception("NG")))
            }
        }
      }
    }
  }

  fun defineRequestPermissions() {
    println("ULocationDriverPlugin: defineRequestPermissions()")
    requestPermissionLauncherPostNotification =
      (thisActivity as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          getLocationPermission()
        }
      }

    requestPermissionLauncherFineLocation =
      (thisActivity as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          getLocationPermissionBackground()
        }
      }

    requestPermissionLauncherBackgroundLocation =
      (thisActivity as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          println("ULocationDriverPlugin: onAttachedToActivity() : activityState = $activityState")
          when (activityState) {
            activityForeground -> {
              requestDeviceLocation()
              println("ULocationDriverPlugin: requestDeviceLocation()")
            }

            activityBackground -> {
              CoroutineScope(Dispatchers.Main).launch {
                val wakeLock: PowerManager.WakeLock =
                  (thisContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ULocationDriverPlugin::WakelockTag").apply {
                      acquire()
                    }
                  }
                val result = getCurrentLocation();
                if (result.isSuccess) {
                  println("ULocationDriverPlugin: getCurrentLocation() -> Success")
                } else {
                  println("ULocationDriverPlugin: getCurrentLocation() -> Failure")
                }
                wakeLock.release()
              }
            }

            temporaryExecuteInBackground -> {
              CoroutineScope(Dispatchers.Main).launch {
                val wakeLock: PowerManager.WakeLock =
                  (thisContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ULocationDriverPlugin::WakelockTag").apply {
                      acquire()
                    }
                  }
                val result = getCurrentLocation();
                if (result.isSuccess) {
                  println("ULocationDriverPlugin: getCurrentLocation() -> Success")
                } else {
                  println("ULocationDriverPlugin: getCurrentLocation() -> Failure")
                }
                activityState = activityBackground
                wakeLock.release()
              }
            }
          }
        }
      }
  }

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    fromDartChannel = MethodChannel(flutterPluginBinding.binaryMessenger, fromDartChannelName)
    fromDartChannel?.setMethodCallHandler(this)
    thisContext = flutterPluginBinding.applicationContext
    binaryMessengerToDart = flutterPluginBinding.binaryMessenger
    toDartChannel = MethodChannel(
      binaryMessengerToDart!!,
      toDartChannelName
    )
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin: onAttachedToActivity()")

    // backgroundFlutterEngine?.destroy()
    activityState = activityForeground
    myPackageName = binding.activity.intent.getComponent()?.getPackageName()
    thisActivity = binding.activity

    defineRequestPermissions()
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
  }

  override fun onDetachedFromActivity() {
    println("ULocationDriverPlugin: onDetachedFromActivity()")
    if (activityState != activityStopped) {
      activityState = activityBackground
      stopLocationUpdates()
      val myAlarmManager = MyAlarmManager(thisContext)
      myAlarmManager.cancelAlarm()
      myAlarmManager.registerAlarm()
    }
  }

  override fun onDetachedFromActivityForConfigChanges() {
    println("ULocationDriverPlugin: onDetachedFromActivityForConfigChanges()")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin: onReattachedToActivityForConfigChanges()")
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    println("ULocationDriverPlugin: onMethodCall() -> ${call.method}")
    when (call.method) {
      "activate" -> {
        println("ULocationDriverPlugin: activate")
        if (activityState == activityBackground) {
          activityState = temporaryExecuteInBackground
        } else {
          activityState = activityForeground
        }
        val alarmManager = thisContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
          val intent = Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
          intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
          thisContext.startActivity(intent)
        } else {
          getNotficationPermission()
        }
        result.success("success")
      }

      "inactivate" -> {
        println("ULocationDriverPlugin: inactivate")
        activityState = activityStopped
        stopLocationUpdates()
        val myAlarmManager = MyAlarmManager(thisContext)
        myAlarmManager.cancelAlarm()
        result.success("success")
      }

      else ->
        result.notImplemented()
    }
  }

  fun getNotficationPermission() {
    val permissionPostNotification =
      ContextCompat.checkSelfPermission(thisContext, POST_NOTIFICATIONS)
    if (permissionPostNotification == PackageManager.PERMISSION_GRANTED) {
      getLocationPermission()
    } else {
      requestPermissionLauncherPostNotification.launch(POST_NOTIFICATIONS)
    }
  }

  fun getLocationPermission() {
    val permissionFineLocation =
      ContextCompat.checkSelfPermission(thisContext, ACCESS_FINE_LOCATION)
    if (permissionFineLocation == PackageManager.PERMISSION_GRANTED) {
      getLocationPermissionBackground()
    } else {
      requestPermissionLauncherFineLocation.launch(ACCESS_FINE_LOCATION)
    }
  }

  fun getLocationPermissionBackground() {
    val permissionBackgroundLocation =
      ContextCompat.checkSelfPermission(thisContext, ACCESS_BACKGROUND_LOCATION)
    if (permissionBackgroundLocation == PackageManager.PERMISSION_GRANTED) {
      println("ULocationDriverPlugin: getLocationPermissionBackground(): activityState = $activityState")
      when (activityState) {
        activityForeground -> {
          requestDeviceLocation()
          println("ULocationDriverPlugin: requestDeviceLocation()")
        }

        activityBackground -> {
          CoroutineScope(Dispatchers.Main).launch {
            val wakeLock: PowerManager.WakeLock =
              (thisContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ULocationDriverPlugin::WakelockTag").apply {
                  acquire()
                }
              }
            val result = getCurrentLocation();
            if (result.isSuccess) {
              println("ULocationDriverPlugin: getCurrentLocation() -> Success")
            } else {
              println("ULocationDriverPlugin: getCurrentLocation() -> Failure")
            }
            wakeLock.release()
          }
        }

        temporaryExecuteInBackground -> {
          CoroutineScope(Dispatchers.IO).launch {
            val wakeLock: PowerManager.WakeLock =
              (thisContext.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ULocationDriverPlugin::WakelockTag").apply {
                  acquire()
                }
              }
            val result = getCurrentLocation();
            if (result.isSuccess) {
              println("ULocationDriverPlugin: getCurrentLocation() -> Success")
            } else {
              println("ULocationDriverPlugin: getCurrentLocation() -> Failure")
            }
            activityState = activityBackground
            wakeLock.release()
          }
        }
      }
    } else {
      requestPermissionLauncherBackgroundLocation.launch(ACCESS_BACKGROUND_LOCATION)
    }
  }

  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  fun requestDeviceLocation() {
    val permissionFineLocation = ContextCompat.checkSelfPermission(
      thisContext.applicationContext,
      Manifest.permission.ACCESS_FINE_LOCATION
    )
    val permissionBackgroundLocation = ContextCompat.checkSelfPermission(
      thisContext.applicationContext,
      Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )
    if (permissionFineLocation == PackageManager.PERMISSION_GRANTED &&
      permissionBackgroundLocation == PackageManager.PERMISSION_GRANTED
    ) {
      stopLocationUpdates()
      fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(thisActivity))
      startLocationUpdates()
    }
  }


  @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
  fun startLocationUpdates() {
    fusedLocationClients.forEach { it ->
      it.requestLocationUpdates(
        LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10 * 1000 /*10秒*/)
          .setMinUpdateIntervalMillis(5 * 1000 /*5秒*/)
          .build(), locationCallback, Looper.getMainLooper()
      )
    }
  }

  fun stopLocationUpdates() {
    fusedLocationClients.forEach { it ->
      it.removeLocationUpdates(locationCallback)
    }
    fusedLocationClients.clear()
  }

  val locationCallback: LocationCallback = object : LocationCallback() {
    override fun onLocationResult(locationResult: LocationResult) {
      println("ULocationDriverPlugin: onLocationResult()")
      CoroutineScope(Dispatchers.Main).launch {
        informLocationToDart(locationResult.lastLocation!!)
      }
    }
  }

  fun getLocationInBackground(context: Context) {
    CoroutineScope(Dispatchers.Main).launch {
      val wakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
          newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ULocationDriverPlugin::WakelockTag").apply {
            acquire()
          }
        }
      if (fusedLocationClients.size <= 0) {
        fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
      }
      backgroundFlutterEngine = loadFlutterEngine(context)
      if (backgroundFlutterEngine != null) {
        toDartChannel = MethodChannel(
          backgroundFlutterEngine!!.dartExecutor.binaryMessenger,
          toDartChannelName
        )
        println("ULocationDriverPlugin: fusedLocationClients = $fusedLocationClients")
        val result = getCurrentLocation()
        if (result.isSuccess) {
          println("ULocationDriverPlugin: getCurrentLocation() -> Success")
        } else {
          println("ULocationDriverPlugin: getCurrentLocation() -> Failure")
        }
      }
      wakeLock.release()
    }
  }
}
