package com.lifetrack.data

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.lifetrack.MainActivity
import com.lifetrack.R
import com.lifetrack.domain.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class ProductNotificationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
                    PackageManager.PERMISSION_GRANTED
        )
            return Result.success()
        return try {
            val db = LifeStore.get(context)
            val s = LocalLifeRepository(db).snapshots.first()
            val p = s.product.preferences.associate { it.key to it.value }
            val today = LocalDate.now()
            val hour = LocalTime.now().hour
            if (hour !in 9..20) return Result.success()
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("progress", "Ваш ритм", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val sent = context.getSharedPreferences("progress_sent", Context.MODE_PRIVATE)
            fun notify(key: String, title: String, text: String) {
                if (sent.getBoolean(key, false)) return
                nm.notify(
                    key.hashCode(),
                    NotificationCompat.Builder(context, "progress")
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                key.hashCode(),
                                Intent(context, MainActivity::class.java),
                                PendingIntent.FLAG_IMMUTABLE,
                            )
                        )
                        .setAutoCancel(true)
                        .build(),
                )
                sent.edit().putBoolean(key, true).apply()
            }
            if (p["notify_water"] == "true" && hour in 14..17) {
                val n = NutritionCalculator.daily(s.nutrition, today)
                val goal = s.nutrition.goal?.waterMl ?: 0
                if (goal > 0 && n.waterMl < goal / 2)
                    notify(
                        "water:$today",
                        "Пауза для воды",
                        "Если хочется пить, добавьте воду в дневник",
                    )
            }
            if (p["notify_review"] == "true" && today.dayOfWeek == DayOfWeek.SUNDAY)
                notify("review:$today", "Ваша неделя", "Обзор недели готов в LifeTrack")
            if (p["notify_budget"] == "true")
                s.budgets
                    .filter { it.month == YearMonth.now().toString() }
                    .forEach { b ->
                        val spent = FinanceCalculator.budgetSpent(s, b)
                        if (spent >= b.amount * 0.8)
                            notify(
                                "budget:${b.id}:${b.month}",
                                "Бюджет почти выбран",
                                "Уже потрачено ${MoneyFormatter.format(spent,b.currency)}",
                            )
                    }
            if (p["notify_goals"] == "true")
                s.goals.forEach { g ->
                    val r = s.product.goalLinks.firstOrNull { it.goalId == g.id }
                    val percent =
                        if (r?.kind == "WEIGHT" && r.initial != g.target)
                            (((r.initial - g.current).toDouble() / (r.initial - g.target)) * 100)
                                .toInt()
                                .coerceIn(0, 100)
                        else percent(g.current, g.target)
                    listOf(25, 50, 75, 100)
                        .lastOrNull { percent >= it }
                        ?.let { m ->
                            notify(
                                "goal:${g.id}:$m",
                                "Вы движетесь к цели",
                                "${g.name}: пройдено $m%",
                            )
                        }
                }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "progress_notifications",
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<ProductNotificationWorker>(3, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder().setRequiresBatteryNotLow(true).build()
                        )
                        .build(),
                )
        }
    }
}
