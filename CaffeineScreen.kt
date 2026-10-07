package com.lifetrack.data

import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.flow.first

class AutoProgressUpdater(private val db: LifeDatabase) {
    suspend fun update() {
        val p = ProductRepository(db).snapshots.first()
        val s = LocalLifeRepository(db).snapshots.first()
        val today = LocalDate.now()
        p.habitRules
            .filter { it.auto && it.metric.isNotBlank() }
            .forEach { r ->
                val h =
                    s.habits.firstOrNull { it.id == r.habitId && !it.archived } ?: return@forEach
                val value =
                    when (r.metric) {
                        "HYDRATION" -> {
                            val local = NutritionCalculator.daily(s.nutrition, today).waterMl
                            if (local > 0) local else HealthMath.daily(p.health, "HYDRATION", today)
                        }
                        "WORKOUT" ->
                            HealthMath.workouts(p.health)
                                .count { HealthMath.day(it) == today }
                                .toLong()
                        else -> HealthMath.daily(p.health, r.metric, today)
                    } ?: return@forEach
                val count = if (r.metric == "SLEEP") value / 60_000 else value
                val old =
                    s.completions.firstOrNull { it.habitId == h.id && it.day == today.toEpochDay() }
                if (
                    old == null &&
                        p.preferences.none {
                            it.key == "habit_manual:${h.id}:${today.toEpochDay()}"
                        } &&
                        count >= h.target &&
                        HabitStatisticsCalculator.due(h, today)
                )
                    db.dao().completion(HabitCompletionEntity(h.id, today.toEpochDay(), h.target))
            }
        p.goalLinks.forEach { r ->
            val g = s.goals.firstOrNull { it.id == r.goalId } ?: return@forEach
            val current =
                when (r.kind) {
                    "SAVINGS" ->
                        s.accounts
                            .firstOrNull { it.id == r.referenceId }
                            ?.let { FinanceCalculator.balance(s, it).coerceAtLeast(0) }
                    "WEIGHT" -> HealthMath.latest(p.health, "WEIGHT")?.value
                    "WORKOUT" ->
                        HealthMath.workouts(p.health)
                            .count { HealthMath.day(it).toEpochDay() >= r.startDay }
                            .toLong()
                    "HABIT" ->
                        s.completions
                            .count { it.habitId == r.referenceId && it.day >= r.startDay }
                            .toLong()
                    else -> null
                }
            if (current != null) db.dao().goal(g.copy(current = current))
        }
    }
}
