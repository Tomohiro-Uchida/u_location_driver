package com.jimdo.uchida001tmhr.u_location_driver

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jimdo.uchida001tmhr.stepcounter.MyAlarmManager

class AlarmReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {

    println("AlarmReceiver: onReceive()")
    val uLocationDriverPlugin = ULocationDriverPlugin()
    uLocationDriverPlugin.getLocationInBackground(context)

    val myAlarmManager = MyAlarmManager(context)
    myAlarmManager.registerAlarm();

  }

}