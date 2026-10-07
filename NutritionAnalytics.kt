package com.lifetrack.data

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews
import androidx.work.*
import com.lifetrack.MainActivity
import com.lifetrack.R
import com.lifetrack.domain.*
import java.time.LocalDate
import kotlinx.coroutines.flow.first

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "widget_update",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetWorker>().build(),
            )
    }

    companion object {
        fun render(context: Context, s: Snapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val today = LocalDate.now()
            val day = NutritionCalculator.daily(s.nutrition, today)
            val goal = s.nutrition.goal?.dailyCalories ?: 0
            val habits =
                s.habits.filter { !it.archived && HabitStatisticsCalculator.due(it, today) }
            val done =
                habits.count { h ->
                    s.completions.any {
                        it.habitId == h.id && it.day == today.toEpochDay() && it.count >= h.target
                    }
                }
            val steps = HealthMath.daily(s.product.health, "STEPS", today)
            val v = RemoteViews(context.packageName, R.layout.widget_today)
            v.setTextViewText(
                R.id.widget_main,
                if (goal > 0) "${((goal-day.totals.calories).coerceAtLeast(0))/1000} ккал осталось"
                else "${day.totals.calories/1000} ккал сегодня",
            )
            v.setTextViewText(
                R.id.widget_secondary,
                "Привычки $done / ${habits.size}" + (steps?.let { " · $it шагов" } ?: ""),
            )
            v.setContentDescription(
                R.id.widget_main,
                if (goal > 0)
                    "Осталось ${((goal-day.totals.calories).coerceAtLeast(0))/1000} килокалорий"
                else "${day.totals.calories/1000} килокалорий сегодня",
            )
            fun launch(route: String, id: Int) =
                PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, MainActivity::class.java)
                        .putExtra("lifetrack_action", route)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        ),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            v.setOnClickPendingIntent(R.id.widget_title, launch("today", 700))
            v.setOnClickPendingIntent(R.id.widget_food, launch("nutrition-catalog", 701))
            v.setOnClickPendingIntent(R.id.widget_water, launch("water", 702))
            manager.updateAppWidget(ids, v)
        }
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            TodayWidget.render(
                applicationContext,
                LocalLifeRepository(LifeStore.get(applicationContext)).snapshots.first(),
            )
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
