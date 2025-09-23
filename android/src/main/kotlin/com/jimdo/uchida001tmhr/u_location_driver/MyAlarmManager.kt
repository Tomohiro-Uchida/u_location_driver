package com.jimdo.uchida001tmhr.u_location_driver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.*


@Suppress("PrivatePropertyName")
class MyAlarmManager(private val context: Context) {

    private val NOTIFICATION_ID = 1000

    fun registerAlarm() {
        println("MyAlarmManager: registerAlarm()")
        val bootIntentRegister = Intent(context, AlarmReceiver::class.java)
        val calendar = Calendar.getInstance()
            calendar.add(Calendar.MINUTE,15)
        val alarmStartTime = calendar.timeInMillis

        val alarmRegister = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val alarmIntentRegister = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            bootIntentRegister,
            PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE
        )
        if (alarmRegister.canScheduleExactAlarms()) {
            println("MyAlarmManager: registerAlarm() : setExactAndAllowWhileIdle()")
            alarmRegister.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmStartTime,
                alarmIntentRegister
            )
        } else {
            println("MyAlarmManager: registerAlarm() : setAndAllowWhileIdle()")
            alarmRegister.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmStartTime,
                alarmIntentRegister
            )
        }
    }

    fun cancelAlarm() {
        println("MyAlarmManager: cancelAlarm()")
        val bootIntentCancel = Intent(context, AlarmReceiver::class.java)
        val alarmCancel = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmCancel.cancelAll()
    }
}