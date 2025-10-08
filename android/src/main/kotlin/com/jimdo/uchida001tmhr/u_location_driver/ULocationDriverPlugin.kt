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
    println("FlutterEngineHolder#destroy")
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
    const val TO_DART_CHANNEL_NAME_BACKGROUND = "com.jimdo.uchida001tmhr.u_location_driver/toDart/background"
    var fromDartChannel: MethodChannel? = null
    var toDartChannel: MethodChannel? = null
    var toDartChannelBackground: MethodChannel? = null
    var binaryMessengerToDart: BinaryMessenger? = null
    var myPackageName: String? = ""
    var backgroundFlutterEngine: FlutterEngine? = null
    var fusedLocationClients = mutableListOf<FusedLocationProviderClient>()
    const val ACTIVITY_STOPPED = 0
    const val ACTIVITY_FOREGROUND = 1
    const val ACTIVITY_BACKGROUND = 2
    const val TEMPORALLY_EXECUTE_IN_BACKGROUND = 3
    var activityState = ACTIVITY_STOPPED

    fun loadFlutterEngineDelegate(context: Context): FlutterEngine? {
      val appContext = context.applicationContext
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #1 appContext = $appContext")
      val prefs = context.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
      val callbackHandle = prefs.getLong("callbackHandle", 0L)
      println("loadFlutterEngine: callbackHandle = $callbackHandle")
      if (callbackHandle == 0L) {
        println("ULocationDriverPlugin#loadFlutterEngineDelegate #2")
        return null
      }
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #3")
      val flutterLoader = FlutterLoader()
      if (!flutterLoader.initialized()) {
        println("ULocationDriverPlugin#loadFlutterEngineDelegate #4")
        flutterLoader.startInitialization(appContext)
        flutterLoader.ensureInitializationComplete(appContext, null)
      }
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #5")
      val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
      println("loadFlutterEngine: callbackInfo = $callbackInfo")
      if (callbackInfo == null) {
        println("ULocationDriverPlugin#loadFlutterEngineDelegate #6")
        return null;
      }
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #7")
      val args = DartExecutor.DartCallback(
        context.getAssets(),
        flutterLoader.findAppBundlePath(),
        callbackInfo
      )
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #8 appContext = $appContext")
      val flutterEngine = FlutterEngine(appContext)
      flutterEngine.getDartExecutor().executeDartCallback(args)
      println("ULocationDriverPlugin#loadFlutterEngineDelegate #9 flutterEngine = $flutterEngine")
      return flutterEngine
    }

    fun loadFlutterEngine(context: Context): FlutterEngine? {
      println("ULocationDriverPlugin#loadFlutterEngine #1 flutterEngine = ${FlutterEngineHolder.flutterEngine}")
      if (FlutterEngineHolder.flutterEngine == null) {
        FlutterEngineHolder.flutterEngine = loadFlutterEngineDelegate(context)
      }
      println("ULocationDriverPlugin#loadFlutterEngine #2 flutterEngine = ${FlutterEngineHolder.flutterEngine}")
      return FlutterEngineHolder.flutterEngine
    }

    fun informLocationToDart(location: Location?, toBackground: Boolean) {
      println("ULocationDriverPlugin#informLocationToDart Start")
      val locale = Locale.JAPAN
      val dateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(locale)
      val dateString = dateTimeFormatter.format(LocalDateTime.now())
      val message = "$dateString,${location?.latitude},${location?.longitude}"
      if (location != null) {
        if (toBackground && toDartChannelBackground != null) {
          println("ULocationDriverPlugin#invokeMethod(location) toDartChannelBackground = $toDartChannelBackground")
          toDartChannelBackground!!.invokeMethod("location", message, object : MethodChannel.Result {
            override fun success(result: Any?) {
              println("informLocationToDart: result = $result")
              // FlutterEngineHolder.destroy()
            }
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
              println("informLocationToDart: errorCode = $errorCode")
              println("informLocationToDart: errorMessage = $errorMessage")
              println("informLocationToDart: errorDetails = $errorDetails")
              // FlutterEngineHolder.destroy()
            }
            override fun notImplemented() {
              println("informLocationToDart: notImplemented")
              // FlutterEngineHolder.destroy()
            }
          })
        } else if (!toBackground  && toDartChannel != null) {
          println("ULocationDriverPlugin#invokeMethod(location) toDartChannel = $toDartChannel")
          toDartChannel!!.invokeMethod("location", message, object : MethodChannel.Result {
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
        }
      }
    }

    fun getCurrentLocation(context: Context) {
      if (fusedLocationClients.isEmpty()) {
        fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
      }
      println("ULocationDriverPlugin#getCurrentLocation fusedLocationClients = $fusedLocationClients")
      val permissionCheckCoarseLocation = ContextCompat.checkSelfPermission(
        context,
        ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      val permissionCheckFineLocation = ContextCompat.checkSelfPermission(
        context,
        ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      if (permissionCheckCoarseLocation && permissionCheckFineLocation) {
        fusedLocationClients.forEach { it ->
          val currentLocationRequestBuilder = CurrentLocationRequest.Builder().apply {
            setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
          }.build()
          it.getCurrentLocation(currentLocationRequestBuilder, null)
            .addOnSuccessListener { it ->
              println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener")
              when (activityState) {
                ACTIVITY_FOREGROUND -> {
                  println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener toDartChannel = $toDartChannel")
                  Handler(Looper.getMainLooper()).postDelayed({
                    informLocationToDart(it, false)
                  }, 1000)
                }
                ACTIVITY_BACKGROUND, TEMPORALLY_EXECUTE_IN_BACKGROUND -> {
                  backgroundFlutterEngine = loadFlutterEngine(context)
                  if (backgroundFlutterEngine != null) {
                    toDartChannelBackground = MethodChannel(
                      backgroundFlutterEngine!!.dartExecutor.binaryMessenger,
                      TO_DART_CHANNEL_NAME_BACKGROUND
                    )
                  }
                  println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener toDartChannelBackground = $toDartChannelBackground")
                  Handler(Looper.getMainLooper()).postDelayed({
                    informLocationToDart(it, true)
                  }, 1000)
                }
                else -> {}
              }
            }
            .addOnFailureListener { it ->
              println("ULocationDriverPlugin#getCurrentLocation()#addOnFailureListener()")
            }
        }
      }
    }
  }

  fun defineRequestPermissions() {
    println("ULocationDriverPlugin#defineRequestPermissions()")
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
    when (activityState) {
      ACTIVITY_STOPPED, ACTIVITY_FOREGROUND -> {
        toDartChannel = MethodChannel(binaryMessengerToDart!!, TO_DART_CHANNEL_NAME)
        println("ULocationDriverPlugin#onAttachedToEngine toDartChannel=$toDartChannel")
      }
      ACTIVITY_BACKGROUND -> {
        toDartChannelBackground = MethodChannel(binaryMessengerToDart!!, TO_DART_CHANNEL_NAME_BACKGROUND)
        println("ULocationDriverPlugin#onAttachedToEngine toDartChannelBackground=$toDartChannelBackground")
      }
      TEMPORALLY_EXECUTE_IN_BACKGROUND -> {
        toDartChannelBackground = MethodChannel(binaryMessengerToDart!!, TO_DART_CHANNEL_NAME_BACKGROUND)
        println("ULocationDriverPlugin#onAttachedToEngine toDartChannelBackground=$toDartChannelBackground")
      }
      else -> {}
    }
    println("ULocationDriverPlugin#onAttachedToEngine toDartChannel=$toDartChannel")
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin#onAttachedToActivity()")

    // activityState = ACTIVITY_FOREGROUND
    myPackageName = binding.activity.intent.component?.packageName
    thisActivity = binding.activity

    defineRequestPermissions()
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    println("ULocationDriverPlugin#onDetachedFromEngine()")
  }

  override fun onDetachedFromActivity() {
    println("ULocationDriverPlugin#onDetachedFromActivity()")
    if (activityState != ACTIVITY_STOPPED) {
      activityState = ACTIVITY_BACKGROUND
      stopLocationUpdates()
      startRetrieveLocation(thisContext)
      val myAlarmManager = MyAlarmManager(thisContext)
      myAlarmManager.cancelAlarm()
      myAlarmManager.registerAlarm()
    }
  }

  override fun onDetachedFromActivityForConfigChanges() {
    println("ULocationDriverPlugin#onDetachedFromActivityForConfigChanges()")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin#onReattachedToActivityForConfigChanges()")
  }

  override fun onResume(owner: LifecycleOwner) {
    println("ULocationDriverPlugin#onResume()")
    super.onResume(owner)
    activityState = ACTIVITY_FOREGROUND
    startRetrieveLocation(thisContext)
    val myAlarmManager = MyAlarmManager(thisContext)
    myAlarmManager.cancelAlarm()
    FlutterEngineHolder.destroy()
  }

  override fun onPause(owner: LifecycleOwner) {
    println("ULocationDriverPlugin#onPause()")
    if (activityState != ACTIVITY_STOPPED) {
      activityState = ACTIVITY_BACKGROUND
      stopLocationUpdates()
      startRetrieveLocation(thisContext)
      val myAlarmManager = MyAlarmManager(thisContext)
      myAlarmManager.cancelAlarm()
      myAlarmManager.registerAlarm()
      backgroundFlutterEngine = loadFlutterEngine(thisContext)
      if (backgroundFlutterEngine != null) {
        toDartChannelBackground = MethodChannel(
          backgroundFlutterEngine!!.dartExecutor.binaryMessenger,
          TO_DART_CHANNEL_NAME_BACKGROUND
        )
      }
    }
    super.onPause(owner)
  }

  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    println("ULocationDriverPlugin#onMethodCall() -> ${call.method}")
    when (call.method) {
      "initialize1" -> {
        println("ULocationDriverPlugin#initialize1")
        val alarmManager = thisContext.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
          val intent = Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
          intent.setFlags(FLAG_ACTIVITY_NEW_TASK)
          thisContext.applicationContext.startActivity(intent)
        }
        result.success("success")
      }

      "initialize2" -> {
        println("ULocationDriverPlugin#initialize2")
        getNotificationPermission()
        result.success("success")
      }

      "activate" -> {
        println("ULocationDriverPlugin#activate")
        if (call.arguments != null) {
          val args = call.arguments as Map<String, Any>
          val prefs = thisContext.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
          val editor = prefs.edit()
          editor.putLong("callbackHandle", args["callbackHandle"] as Long)
          editor.commit()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        activityState = when(activityState) {
          ACTIVITY_STOPPED -> TEMPORALLY_EXECUTE_IN_BACKGROUND
          ACTIVITY_BACKGROUND -> TEMPORALLY_EXECUTE_IN_BACKGROUND
          else -> activityState
        }
        startRetrieveLocation(thisContext.applicationContext);
        result.success("success")
      }

      "deactivate" -> {
        println("ULocationDriverPlugin#deactivate")
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        activityState = ACTIVITY_STOPPED
        stopLocationUpdates()
        val myAlarmManager = MyAlarmManager(thisContext.applicationContext)
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
        println("ULocationDriverPlugin#requestDeviceLocation()")
      }

      ACTIVITY_BACKGROUND -> {
        println("ULocationDriverPlugin#startRetrieveLocation() #1")
        getCurrentLocation(context)
      }

      TEMPORALLY_EXECUTE_IN_BACKGROUND -> {
        println("ULocationDriverPlugin#startRetrieveLocation() #2")
        getCurrentLocation(context)
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
      println("ULocationDriverPlugin#onLocationResult() activityState = $activityState")
      when (activityState) {
        ACTIVITY_FOREGROUND -> informLocationToDart(locationResult.lastLocation!!, false)
        ACTIVITY_BACKGROUND -> informLocationToDart(locationResult.lastLocation!!, true)
        TEMPORALLY_EXECUTE_IN_BACKGROUND -> informLocationToDart(locationResult.lastLocation!!, true)
        else -> {}
      }
    }
  }

  fun getLocationInBackground(context: Context) {
    println("ULocationDriverPlugin#getLocationInBackground #1")
    activityState = ACTIVITY_BACKGROUND
    runBlocking {
      if (fusedLocationClients.isEmpty()) {
        println("ULocationDriverPlugin#getLocationInBackground #2")
        fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
        println("ULocationDriverPlugin#getLocationInBackground #3")
      }
      println("ULocationDriverPlugin#getLocationInBackground #4")
      withContext(Dispatchers.Main) {
        println("ULocationDriverPlugin#getLocationInBackground #5")
        getCurrentLocation(context)
      }
    }
  }
}
