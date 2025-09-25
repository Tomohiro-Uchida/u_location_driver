package com.jimdo.uchida001tmhr.u_location_driver

import android.Manifest
import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.Handler
import android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.view.FlutterCallbackInformation
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object FlutterEngineHolder {
  var flutterEngine: FlutterEngine? = null

  fun destroy() {
    flutterEngine?.destroy()
    flutterEngine = null
  }
}

class ULocationDriverPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, DefaultLifecycleObserver {
  private lateinit var requestPermissionLauncherPostNotification: ActivityResultLauncher<String>
  private lateinit var requestPermissionLauncherFineLocation: ActivityResultLauncher<String>
  private lateinit var requestPermissionLauncherBackgroundLocation: ActivityResultLauncher<String>

  companion object {
    @SuppressLint("StaticFieldLeak")
    lateinit var thisContext: Context

    @SuppressLint("StaticFieldLeak")
    lateinit var thisActivity: Activity

    const val FROM_DART_CHANNEL_NAME = "com.jimdo.uchida001tmhr.u_location_driver/fromDart"
    const val TO_DART_CHANNEL_NAME = "com.jimdo.uchida001tmhr.u_location_driver/toDart"
    var fromDartChannel: MethodChannel? = null
    var toDartChannel: MethodChannel? = null
    var binaryMessengerToDart: BinaryMessenger? = null
    var myPackageName: String? = ""
    var backgroundFlutterEngine: FlutterEngine? = null
    var fusedLocationClients = mutableListOf<FusedLocationProviderClient>()
    const val ACTIVITY_STOPPED = 0
    const val ACTIVITY_FOREGROUND = 1
    const val ACTIVITY_BACKGROUND = 2
    const val TEMPORALLY_EXECUTE_IN_BACKGROUND = 3
    var activityState = ACTIVITY_STOPPED
    val currentLocationRequestBuilder = CurrentLocationRequest.Builder().apply {
      setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
    }.build()

    fun loadFlutterEngine(context: Context): FlutterEngine? {
      println("ULocationDriverPlugin: loadFlutterEngin #1")
      if (FlutterEngineHolder.flutterEngine == null) {
        println("ULocationDriverPlugin: loadFlutterEngin #2")
        val prefs = context.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
        val callbackHandle = prefs.getLong("callbackHandle", 0L)
        println("loadFlutterEngine: callbackHandle = ${callbackHandle}")
        if (callbackHandle == 0L) {
          println("ULocationDriverPlugin: loadFlutterEngin #3")
          return null
        }
        println("ULocationDriverPlugin: loadFlutterEngin #4")
        val flutterLoader = FlutterLoader()
        if (!flutterLoader.initialized()) {
          println("ULocationDriverPlugin: loadFlutterEngin #5")
          flutterLoader.startInitialization(context)
          flutterLoader.ensureInitializationComplete(context, null)
        }
        println("ULocationDriverPlugin: loadFlutterEngin #6")
        val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
        println("loadFlutterEngine: callbackInfo = ${callbackInfo}")
        if (callbackInfo == null) {
          println("ULocationDriverPlugin: loadFlutterEngin #7")
          return null;
        }
        println("ULocationDriverPlugin: loadFlutterEngin #8")
        val args = DartExecutor.DartCallback(
          context.getAssets(),
          flutterLoader.findAppBundlePath(),
          callbackInfo
        )
        println("ULocationDriverPlugin: loadFlutterEngin #9")
        val flutterEngine = FlutterEngine(context.applicationContext)
        flutterEngine.getDartExecutor().executeDartCallback(args)
        FlutterEngineHolder.flutterEngine = flutterEngine
        return flutterEngine
      } else {
        println("ULocationDriverPlugin: loadFlutterEngin #10")
        return FlutterEngineHolder.flutterEngine
      }
    }

    fun informLocationToDart(location: Location?) {
      println("ULocationDriverPlugin: informLocationToDart() : Start")
      if (location != null && toDartChannel != null) {
        val locale = Locale.JAPAN
        val dateTimeFormatter =
          DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(locale)
        val dateString = dateTimeFormatter.format(LocalDateTime.now())
        val message = "$dateString,${location?.latitude},${location?.longitude}"
        Handler(Looper.getMainLooper()).postDelayed({
          println("ULocationDriverPlugin: invokeMethod(location)")
          toDartChannel?.invokeMethod("location", message, object : MethodChannel.Result {
            override fun success(result: Any?) {
              println("informLocationToDart: result = $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
              println("informLocationToDart: errorCode = $errorCode")
              println("informLocationToDart: errorMessage = $errorMessage")
              println("informLocationToDart: errorDetails = $errorDetails")
            }

            override fun notImplemented() {
              println("informLocationToDart: notImplemented")
            }
          })
        }, 1000)
      } else {
      }
    }

    fun getCurrentLocation(context: Context) {
      if (fusedLocationClients.isEmpty()) {
        fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
      }
      println("ULocationDriverPlugin: getCurrentLocation() : fusedLocationClients = $fusedLocationClients")
      val permissionCheckCoarseLocation = ContextCompat.checkSelfPermission(
        thisContext,
        ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      val permissionCheckFineLocation = ContextCompat.checkSelfPermission(
        thisContext,
        ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      if (permissionCheckCoarseLocation && permissionCheckFineLocation) {
        fusedLocationClients.forEach { it ->
          it.getCurrentLocation(currentLocationRequestBuilder, null)
            .addOnSuccessListener { it ->
              println("ULocationDriverPlugin: getCurrentLocation() -> OnSuccessListener ")
              informLocationToDart(it)
            }
            .addOnFailureListener { it ->
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
      }
  }

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    fromDartChannel = MethodChannel(flutterPluginBinding.binaryMessenger, FROM_DART_CHANNEL_NAME)
    fromDartChannel?.setMethodCallHandler(this)
    thisContext = flutterPluginBinding.applicationContext
    binaryMessengerToDart = flutterPluginBinding.binaryMessenger
    toDartChannel = MethodChannel(
      binaryMessengerToDart!!,
      TO_DART_CHANNEL_NAME
    )
    FlutterEngineHolder.destroy()
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin: onAttachedToActivity()")

    // backgroundFlutterEngine?.destroy()
    activityState = ACTIVITY_FOREGROUND
    myPackageName = binding.activity.intent.component?.packageName
    thisActivity = binding.activity

    defineRequestPermissions()
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    println("ULocationDriverPlugin: onDetachedFromEngine()")
  }

  override fun onDetachedFromActivity() {
    println("ULocationDriverPlugin: onDetachedFromActivity()")
  }

  override fun onDetachedFromActivityForConfigChanges() {
    println("ULocationDriverPlugin: onDetachedFromActivityForConfigChanges()")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin: onReattachedToActivityForConfigChanges()")
  }

  override fun onResume(owner: LifecycleOwner) {
    println("ULocationDriverPlugin: onResume()")
    super.onResume(owner)
    val myAlarmManager = MyAlarmManager(thisContext)
    myAlarmManager.cancelAlarm()
  }

  override fun onPause(owner: LifecycleOwner) {
    println("ULocationDriverPlugin: onPause()")
    if (activityState != ACTIVITY_STOPPED) {
      activityState = ACTIVITY_BACKGROUND
      stopLocationUpdates()
      val myAlarmManager = MyAlarmManager(thisContext)
      myAlarmManager.cancelAlarm()
      myAlarmManager.registerAlarm()
    }
    super.onPause(owner)
  }

  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    println("ULocationDriverPlugin: onMethodCall() -> ${call.method}")
    when (call.method) {
      "initialize1" -> {
        println("ULocationDriverPlugin: initialize1")
        val alarmManager = thisContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
          val intent = Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
          intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
          thisContext.startActivity(intent)
        }
        result.success("success")
      }

      "initialize2" -> {
        println("ULocationDriverPlugin: initialize2")
        getNotificationPermission()
        result.success("success")
      }

      "activate" -> {
        println("ULocationDriverPlugin: activate")
        if (call.arguments != null) {
          val args = call.arguments as Map<String, Any>
          val prefs = thisContext.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
          val editor = prefs.edit()
          editor.putLong("callbackHandle", args["callbackHandle"] as Long)
          editor.commit()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        activityState = if (activityState == ACTIVITY_BACKGROUND) {
          TEMPORALLY_EXECUTE_IN_BACKGROUND
        } else {
          ACTIVITY_FOREGROUND
        }
        startRetrieveLocation(thisContext);
        result.success("success")
      }

      "deactivate" -> {
        println("ULocationDriverPlugin: deactivate")
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        activityState = ACTIVITY_STOPPED
        stopLocationUpdates()
        val myAlarmManager = MyAlarmManager(thisContext)
        myAlarmManager.cancelAlarm()
        val prefs = thisContext.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putLong("callbackHandle", 0L)
        result.success("success")
      }

      else ->
        result.notImplemented()
    }
  }

  fun getNotificationPermission() {
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
    if (permissionBackgroundLocation != PackageManager.PERMISSION_GRANTED) {
      requestPermissionLauncherBackgroundLocation.launch(ACCESS_BACKGROUND_LOCATION)
    }
  }

  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  fun startRetrieveLocation(context: Context) {
    when (activityState) {
      ACTIVITY_FOREGROUND -> {
        requestDeviceLocation(context)
        println("ULocationDriverPlugin: requestDeviceLocation()")
      }

      ACTIVITY_BACKGROUND -> {
        getCurrentLocation(thisContext)
      }

      TEMPORALLY_EXECUTE_IN_BACKGROUND -> {
        getCurrentLocation(thisContext)
        activityState = ACTIVITY_BACKGROUND
      }
    }
  }

  @RequiresPermission(ACCESS_COARSE_LOCATION)
  fun requestDeviceLocation(context: Context) {
    val permissionFineLocation = ContextCompat.checkSelfPermission(
      context.applicationContext,
      ACCESS_FINE_LOCATION
    )
    val permissionBackgroundLocation = ContextCompat.checkSelfPermission(
      context.applicationContext,
      ACCESS_BACKGROUND_LOCATION
    )
    if (permissionFineLocation == PackageManager.PERMISSION_GRANTED &&
      permissionBackgroundLocation == PackageManager.PERMISSION_GRANTED
    ) {
      stopLocationUpdates()
      fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
      startLocationUpdates()
    }
  }

  @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION])
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
      informLocationToDart(locationResult.lastLocation!!)
    }
  }

  fun getLocationInBackground(context: Context) {
    println("ULocationDriverPlugin: getLocationInBackground #1")
    runBlocking {
      if (fusedLocationClients.isEmpty()) {
        println("ULocationDriverPlugin: getLocationInBackground #2")
        fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
        println("ULocationDriverPlugin: getLocationInBackground #3")
      }
      println("ULocationDriverPlugin: getLocationInBackground #4")
      withContext(Dispatchers.Main) {
        println("ULocationDriverPlugin: getLocationInBackground #5")
        backgroundFlutterEngine = loadFlutterEngine(context)
        println("ULocationDriverPlugin: getLocationInBackground #6")
        if (backgroundFlutterEngine != null) {
          println("ULocationDriverPlugin: getLocationInBackground #7")
          toDartChannel = MethodChannel(
            backgroundFlutterEngine!!.dartExecutor.binaryMessenger,
            TO_DART_CHANNEL_NAME
          )
          println("ULocationDriverPlugin: fusedLocationClients = $fusedLocationClients")
          getCurrentLocation(context)
        }
      }
    }
  }
}
