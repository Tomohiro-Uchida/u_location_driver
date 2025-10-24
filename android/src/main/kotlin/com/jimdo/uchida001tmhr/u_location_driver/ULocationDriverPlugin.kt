package com.jimdo.uchida001tmhr.u_location_driver

import android.Manifest
import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
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
import androidx.core.content.edit
import com.google.common.util.concurrent.ListenableFuture
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.ExistingPeriodicWorkPolicy
// import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.ACTIVITY_BACKGROUND
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.ACTIVITY_FOREGROUND
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.activityState
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.backgroundFlutterEngine
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.fusedLocationClients
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.informLocationToDart
import com.jimdo.uchida001tmhr.u_location_driver.ULocationDriverPlugin.Companion.loadFlutterEngine
import java.time.Duration

object FlutterEngineHolder {
  var flutterEngine: FlutterEngine? = null
}

class LocationWorker(val context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
  override fun startWork(): ListenableFuture<Result> {
    return CallbackToFutureAdapter.getFuture { completer ->
      println("LocationWorker fusedLocationClients = $fusedLocationClients")
      val permissionCheckCoarseLocation = ContextCompat.checkSelfPermission(
        context,
        ACCESS_COARSE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      val permissionCheckFineLocation = ContextCompat.checkSelfPermission(
        context,
        ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
      if (permissionCheckCoarseLocation && permissionCheckFineLocation) {
        if (fusedLocationClients.isEmpty()) {
          fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(context))
        }
        println("LocationWorker fusedLocationClients = $fusedLocationClients")
        println("LocationWorker fusedLocationClients.size = ${fusedLocationClients.size}")
        fusedLocationClients.forEach { it ->
          val currentLocationRequestBuilder = CurrentLocationRequest.Builder().apply {
            setPriority(Priority.PRIORITY_HIGH_ACCURACY)
          }.build()
          println("LocationWorker#it.getCurrentLocation()")
          it.getCurrentLocation(currentLocationRequestBuilder, null)
            .addOnSuccessListener { it ->
              println("LocationWorker#getCurrentLocation#OnSuccessListener activityState = $activityState")
              when (activityState) {
                ACTIVITY_FOREGROUND /*, ACTIVITY_BACKGROUND */ -> {
                  // println("LocationWorker#getCurrentLocation#OnSuccessListener ACTIVITY_FOREGROUND/ACTIVITY_BACKGROUND")
                  println("LocationWorker#getCurrentLocation#OnSuccessListener ACTIVITY_FOREGROUND")
                  Handler(Looper.getMainLooper()).postDelayed({
                    informLocationToDart(it)
                    println("LocationWorker#completer.set(Result.success())")
                    completer.set(Result.success())
                  }, 1000)
                }

                else -> {
                  backgroundFlutterEngine = loadFlutterEngine(context)
                  println("LocationWorker#getCurrentLocation#OnSuccessListener else")
                  Handler(Looper.getMainLooper()).postDelayed({
                    informLocationToDart(it)
                    println("LocationWorker#completer.set(Result.success())")
                    completer.set(Result.success())
                  }, 1000)
                }
              }
              // completer.set(Result.success())
            }
            .addOnFailureListener { it ->
              println("LocationWorker#completer.setException()")
              completer.setException(it)
            }
        }
      }
    }
  }
}

class ULocationDriverPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, DefaultLifecycleObserver {
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
    var binaryMessengerToDart: BinaryMessenger? = null
    var myPackageName: String? = ""
    var backgroundFlutterEngine: FlutterEngine? = null
    var fusedLocationClients = mutableListOf<FusedLocationProviderClient>()

    const val ACTIVITY_TERMINATED = 0
    const val ACTIVITY_BACKGROUND = 1
    const val ACTIVITY_FOREGROUND = 2
    var activityState = ACTIVITY_TERMINATED
    const val LOCATION_WORK_TAG = "LocationWorkTag"

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

    fun informLocationToDart(location: Location?) {
      println("ULocationDriverPlugin#informLocationToDart Start location = $location")
      println("ULocationDriverPlugin#informLocationToDart Start toDartChannel = $toDartChannel")
      val locale = Locale.JAPAN
      val dateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withLocale(locale)
      val dateString = dateTimeFormatter.format(LocalDateTime.now())
      val message = "$dateString,${location?.latitude},${location?.longitude}"
      if (location != null) {
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

    fun getCurrentLocation(context: Context) {
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
            setPriority(Priority.PRIORITY_HIGH_ACCURACY)
          }.build()
          it.getCurrentLocation(currentLocationRequestBuilder, null)
            .addOnSuccessListener { it ->
              println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener")
              when (activityState) {
                ACTIVITY_FOREGROUND /*, ACTIVITY_BACKGROUND */ -> {
                  // println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener ACTIVITY_FOREGROUND/ACTIVITY_BACKGROUND")
                  println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener ACTIVITY_FOREGROUND")
                  Handler(Looper.getMainLooper()).postDelayed({
                    informLocationToDart(it)
                  }, 1000)
                }

                else -> {
                  println("ULocationDriverPlugin#getCurrentLocation#OnSuccessListener else")
                  backgroundFlutterEngine = loadFlutterEngine(context)
                  if (backgroundFlutterEngine != null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                      informLocationToDart(it)
                    }, 1000)
                  }
                }
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

    requestPermissionLauncherFineLocation =
      (thisActivity as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          getLocationPermissionBackground()
        }
      }

    requestPermissionLauncherBackgroundLocation =
      (thisActivity as ComponentActivity).registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
          toDartChannel!!.invokeMethod("readyForLocation", null, object : MethodChannel.Result {
            override fun success(result: Any?) {
              println("readyForLocation: result = $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
              println("readyForLocation: errorCode = $errorCode")
              println("readyForLocation: errorMessage = $errorMessage")
              println("readyForLocation: errorDetails = $errorDetails")
            }

            override fun notImplemented() {
              println("readyForLocation: notImplemented")
            }
          })
        }
      }
  }

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    fromDartChannel = MethodChannel(flutterPluginBinding.binaryMessenger, FROM_DART_CHANNEL_NAME)
    fromDartChannel?.setMethodCallHandler(this)
    thisContext = flutterPluginBinding.applicationContext
    binaryMessengerToDart = flutterPluginBinding.binaryMessenger
    toDartChannel = MethodChannel(binaryMessengerToDart!!, TO_DART_CHANNEL_NAME)
    println("ULocationDriverPlugin#onAttachedToEngine toDartChannel=$toDartChannel")
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin#onAttachedToActivity()")

    myPackageName = binding.activity.intent.component?.packageName
    thisActivity = binding.activity

    defineRequestPermissions()
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    println("ULocationDriverPlugin#onDetachedFromEngine()")
    activityState = ACTIVITY_TERMINATED
    stopLocationUpdates()
    backgroundFlutterEngine = loadFlutterEngine(thisContext)
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build();
    val locationWorkerRequest = PeriodicWorkRequestBuilder<LocationWorker>(Duration.ofMinutes(15))
      .setConstraints(constraints)
      .build();
    WorkManager.getInstance(thisContext)
      .enqueueUniquePeriodicWork(LOCATION_WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, locationWorkerRequest);
  }

  override fun onDetachedFromActivity() {
    println("ULocationDriverPlugin#onDetachedFromActivity()")
  }

  override fun onDetachedFromActivityForConfigChanges() {
    println("ULocationDriverPlugin#onDetachedFromActivityForConfigChanges()")
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    println("ULocationDriverPlugin#onReattachedToActivityForConfigChanges()")
  }

  @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
  override fun onResume(owner: LifecycleOwner) {
    println("ULocationDriverPlugin#onResume()")
    super.onResume(owner)
    activityState = ACTIVITY_FOREGROUND
    WorkManager.getInstance(thisContext).cancelAllWork()
    println("ULocationDriverPlugin#onAttachedToEngine toDartChannel=$toDartChannel")
    startLocationUpdates()
  }

  override fun onPause(owner: LifecycleOwner) {
    println("ULocationDriverPlugin#onPause()")
    activityState = ACTIVITY_BACKGROUND
    stopLocationUpdates()
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .build();
    val locationWorkerRequest = PeriodicWorkRequestBuilder<LocationWorker>(Duration.ofMinutes(15))
      .setConstraints(constraints)
      .build();
    WorkManager.getInstance(thisContext)
      .enqueueUniquePeriodicWork(LOCATION_WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, locationWorkerRequest);
    super.onPause(owner)
  }

  @RequiresPermission(ACCESS_COARSE_LOCATION)
  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    println("ULocationDriverPlugin#onMethodCall() -> ${call.method}")
    when (call.method) {

      "initialize" -> {
        println("ULocationDriverPlugin#initialize")
        getLocationPermission()
        stopLocationUpdates()
        WorkManager.getInstance(thisContext).cancelAllWork()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        if (fusedLocationClients.isEmpty()) {
          fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(thisContext.applicationContext))
        }
        println("ULocationDriverPlugin#initialize fusedLocationClients.size = ${fusedLocationClients.size}")
        result.success("success")
      }

      "activate" -> {
        println("ULocationDriverPlugin#activate")
        val prefs = thisContext.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
        if (call.arguments != null) {
          val args = call.arguments as Map<String, Any>
          prefs.edit(commit = true) {
            putLong("callbackHandle", args["callbackHandle"] as Long)
          }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        if (fusedLocationClients.isEmpty()) {
          fusedLocationClients.add(LocationServices.getFusedLocationProviderClient(thisContext.applicationContext))
        }
        println("ULocationDriverPlugin#initialize fusedLocationClients.size = ${fusedLocationClients.size}")
        getCurrentLocation(thisContext.applicationContext) // 初回の位置情報取得
        requestDeviceLocation(thisContext.applicationContext)

        result.success("success")
      }

      "deactivate" -> {
        println("ULocationDriverPlugin#deactivate")
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        activityState = ACTIVITY_TERMINATED
        stopLocationUpdates()
        WorkManager.getInstance(thisContext).cancelAllWork()
        val prefs = thisContext.applicationContext.getSharedPreferences("defaultPreferences", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
          putLong("callbackHandle", 0L)
        }
        result.success("success")
      }

      else ->
        result.notImplemented()
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
      toDartChannel!!.invokeMethod("readyForLocation", null, object : MethodChannel.Result {
        override fun success(result: Any?) {
          println("readyForLocation: result = $result")
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
          println("readyForLocation: errorCode = $errorCode")
          println("readyForLocation: errorMessage = $errorMessage")
          println("readyForLocation: errorDetails = $errorDetails")
        }

        override fun notImplemented() {
          println("readyForLocation: notImplemented")
        }
      })
    } else {
      requestPermissionLauncherBackgroundLocation.launch(ACCESS_BACKGROUND_LOCATION)
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
      WorkManager.getInstance(thisContext).cancelAllWork()
    }
  }

  @RequiresPermission(allOf = [ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION])
  fun startLocationUpdates() {
    fusedLocationClients.forEach { it ->
      it.requestLocationUpdates(
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10 * 1000)
          .setMaxUpdateDelayMillis(30 * 1000) // 最大の更新間隔の設定
          .setMinUpdateIntervalMillis(5 * 1000) // 最小の更新間隔の設定
          .setWaitForAccurateLocation(true) // 高精度の位置情報を待つ
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
        ACTIVITY_FOREGROUND /*, ACTIVITY_BACKGROUND */ -> {
          // println("ULocationDriverPlugin#onLocationResult() ACTIVITY_FOREGROUND/ACTIVITY_BACKGROUND")
          println("ULocationDriverPlugin#onLocationResult() ACTIVITY_FOREGROUND")
          if (locationResult.lastLocation != null) {
            Handler(Looper.getMainLooper()).postDelayed({
              informLocationToDart(locationResult.lastLocation!!)
            }, 1000)
          }
        }

        else -> {
          println("ULocationDriverPlugin#onLocationResult() else")
          backgroundFlutterEngine = loadFlutterEngine(thisContext)
          if (locationResult.lastLocation != null) {
            Handler(Looper.getMainLooper()).postDelayed({
              informLocationToDart(locationResult.lastLocation!!)
            }, 1000)
          }
        }
      }
    }
  }
}
