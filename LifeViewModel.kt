package com.lifetrack.data

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.lifetrack.MainActivity
import com.lifetrack.R
import com.lifetrack.domain.HabitStatisticsCalculator
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

object ReminderScheduler {
    fun sync(context: Context, habits: List<HabitEntity>, enabled: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("reminder_ids", Context.MODE_PRIVATE)
        prefs.getStringSet("ids", emptySet())!!.forEach { id ->
            val p =
                PendingIntent.getBroadcast(
                    context,
                    id.toInt(),
                    Intent(context, ReminderReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
            if (p != null) {
                alarms.cancel(p)
                p.cancel()
            }
        }
        prefs.edit().putStringSet("ids", habits.map { it.id.toString() }.toSet()).apply()
        habits.forEach { h ->
            val i =
                Intent(context, ReminderReceiver::class.java)
                    .putExtra("name", h.name)
                    .putExtra("id", h.id.toInt())
                    .putExtra("time", h.reminder)
            val p =
                PendingIntent.getBroadcast(
                    context,
                    h.id.toInt(),
                    i,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            alarms.cancel(p)
            if (enabled && !h.archived && h.reminder.isNotBlank()) schedule(context, i, p)
        }
    }

    fun schedule(context: Context, i: Intent, p: PendingIntent) {
        val time = runCatching { LocalTime.parse(i.getStringExtra("time")) }.getOrNull() ?: return
        val now = ZonedDateTime.now()
        var next = now.with(time)
        if (next <= now) next = next.plusDays(1)
        context
            .getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), p)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db =
                LifeStore.get(context)
            try {
                val enabled = SettingsRepository(context).flow.first().notifications
                val h =
                    db.dao().habits().first().firstOrNull {
                        it.id.toInt() == intent.getIntExtra("id", 0)
                    }
                if (!enabled || h == null || h.archived || h.reminder.isBlank()) return@launch
                val today = LocalDate.now()
                val done =
                    db.dao().completions().first().any {
                        it.habitId == h.id && it.day == today.toEpochDay() && it.count >= h.target
                    }
                if (HabitStatisticsCalculator.due(h, today) && !done) {
                    val nm = context.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(
                        NotificationChannel(
                            "habits",
                            "Привычки",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    )
                    val launch =
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                    if (
                        Build.VERSION.SDK_INT < 33 ||
                            context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                        nm.notify(
                            h.id.toInt(),
                            NotificationCompat.Builder(context, "habits")
                                .setSmallIcon(R.drawable.ic_launcher)
                                .setContentTitle("Время для привычки")
                                .setContentText(h.name)
                                .setContentIntent(launch)
                                .setAutoCancel(true)
                                .build(),
                        )
                }
                val p =
                    PendingIntent.getBroadcast(
                        context,
                        h.id.toInt(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                ReminderScheduler.schedule(context, intent, p)
            } finally {
                // Shared LifeStore remains open for the app.
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val db =
                LifeStore.get(context)
            try {
                ReminderScheduler.sync(
                    context,
                    db.dao().habits().first(),
                    SettingsRepository(context).flow.first().notifications,
                )
            } finally {
                // Shared LifeStore remains open for the app.
                pending.finish()
            }
        }
    }
}
