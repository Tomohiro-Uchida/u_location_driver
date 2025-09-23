package com.jimdo.uchida001tmhr.u_location_driver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class BackgroundLocationWorker(val context: Context, params: WorkerParameters) : Worker(context, params){

  override fun doWork(): Result {
    val uLocationDriverPlugin = ULocationDriverPlugin()
    uLocationDriverPlugin.getLocationInBackground(context)
    return Result.success()
  }

}

class AlarmReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {

    println("AlarmReceiver: onReceive()")
    val myAlarmManager = MyAlarmManager(context)
    myAlarmManager.registerAlarm();

    val request = OneTimeWorkRequest.from(BackgroundLocationWorker::class.java)
    val workManager = WorkManager.getInstance()
    workManager.enqueue(request)

  }

}
