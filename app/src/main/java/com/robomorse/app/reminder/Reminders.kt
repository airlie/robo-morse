package com.robomorse.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.robomorse.app.MainActivity
import com.robomorse.app.R
import com.robomorse.app.RoboMorseApp
import com.robomorse.app.data.practiceDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One daily reminder at the learner's chosen time, skipped if they already practiced.
 * Uses an inexact alarm window, so no exact-alarm permission is needed.
 */
object Reminders {
    private const val CHANNEL = "practice"
    private const val REQUEST = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Practice reminder", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST, Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context))
        if (!enabled) return
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(hour, minute)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setWindow(AlarmManager.RTC_WAKEUP, ms, 10 * 60 * 1000L, pendingIntent(context))
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, text: String) {
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time for Morse practice")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(REQUEST, n)
        } catch (_: SecurityException) {
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RoboMorseApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.container.settings.current()
                val sessions = app.container.repository.sessions.first()
                if (LocalDate.now() !in practiceDays(sessions)) {
                    val plan = s.practicePlan.ifBlank { "A short session now beats a long one later." }
                    Reminders.notify(context, plan)
                }
                Reminders.schedule(context, s.reminderEnabled, s.reminderHour, s.reminderMinute)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as RoboMorseApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.container.settings.current()
                Reminders.schedule(context, s.reminderEnabled, s.reminderHour, s.reminderMinute)
            } finally {
                pending.finish()
            }
        }
    }
}
