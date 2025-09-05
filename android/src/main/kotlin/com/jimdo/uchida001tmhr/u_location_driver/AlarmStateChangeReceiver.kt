package com.jimdo.uchida001tmhr.u_location_driver

import android.app.AlarmManager
import android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmStateChangeReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    println("ExectAlarmStateChangeReceiver: onReceive()")
    if (intent.action == ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
      val alarmRegister = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      if (alarmRegister.canScheduleExactAlarms()) {
        val uLocationDriverPlugin = ULocationDriverPlugin()
        uLocationDriverPlugin.defineRequestPermissions()
        uLocationDriverPlugin.getNotificationPermission()
      }
    }
  }

}