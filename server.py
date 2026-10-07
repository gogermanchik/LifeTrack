package com.lifetrack.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*

@Composable
fun NutritionAnalyticsContent(s: NutritionSnapshot) {
    var period by remember { mutableStateOf("7 дней") }
    var details by remember { mutableStateOf(false) }
    val end = LocalDate.now()
    val start =
        when (period) {
            "30 дней" -> end.minusDays(29)
            "Месяц" -> end.withDayOfMonth(1)
            else -> end.minusDays(6)
        }
    val days = NutritionCalculator.period(s, start, end)
    val kcal = days.map { it.totals.calories }
    val avg = NutritionCalculator.average(kcal)
    val goal = s.goal
    val macros =
        NutritionTotals(
            protein = NutritionCalculator.average(days.map { it.totals.protein }),
            fat = NutritionCalculator.average(days.map { it.totals.fat }),
            carbs = NutritionCalculator.average(days.map { it.totals.carbs }),
        )
    LifeSegmentedControl(listOf("7 дней", "30 дней", "Месяц"), period) { period = it }
    Panel {
        Text(
            "В среднем за день",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(NutritionNumber.rounded(avg), style = MaterialTheme.typography.displayMedium)
            Text(
                "ккал",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Text(
            "Заполнено ${days.count {it.logs.isNotEmpty()}} из ${days.size} дней",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if ((goal?.dailyCalories ?: 0) > 0) {
            LifeProgressBar(avg, goal!!.dailyCalories)
            Text(
                "${percent(avg,goal.dailyCalories)}% текущей личной цели · ${NutritionNumber.rounded(goal.dailyCalories)} ккал",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BarChart(kcal, "Дневные калории с $start по $end")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                start.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                end.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Section("БЖУ в среднем") { MacroStrip(macros, goal) }
    Section("Вода") {
        Text(
            "${NutritionNumber.format(NutritionCalculator.average(days.map {it.waterMl}))} л / день",
            style = MaterialTheme.typography.headlineSmall,
        )
        LineChart(days.map { it.waterMl }, "Вода по дням, миллилитры")
    }
    Text(
        "Среднее включает дни без записей. Цели задаёте вы; они не являются рекомендацией.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton({ details = !details }, contentPadding = PaddingValues(0.dp)) {
        Text(if (details) "Скрыть дни" else "Записи по дням")
        Icon(
            if (details) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            null,
            Modifier.size(18.dp),
        )
    }
    AnimatedVisibility(details) {
        Column {
            days.forEachIndexed { i, d ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        start.plusDays(i.toLong()).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${NutritionNumber.rounded(d.totals.calories)} ккал",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
}

@Composable
fun NutritionAnalytics(s: NutritionSnapshot) {
    Page("Аналитика питания") { NutritionAnalyticsContent(s) }
}

@Composable
fun AnalyticsHub(s: Snapshot, currency: String, nvm: NutritionViewModel) {
    var section by remember { mutableStateOf("Обзор") }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val nutrition by nvm.data.collectAsStateWithLifecycle()
    Page("Аналитика", "Увидеть движение вперёд") {
        LifeSegmentedControl(listOf("Обзор", "Питание", "Привычки", "Финансы"), section) {
            section = it
        }
        when (section) {
            "Питание" -> NutritionAnalyticsContent(nutrition)
            "Финансы" -> {
                MonthSelector(month) { month = it }
                FinanceAnalyticsContent(s, currency, month)
            }
            "Привычки" -> {
                MonthSelector(month) { month = it }
                HabitAnalyticsContent(s, month)
            }
            else -> {
                val today = LocalDate.now()
                val d = DashboardCalculator.calculate(s, currency, today)
                val n = NutritionCalculator.daily(nutrition, today)
                Panel {
                    Text(
                        "Этот месяц",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        MoneyFormatter.format(d.finance.balance, currency),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        "Общий баланс",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyRows(d.finance, currency)
                }
                Section("Сегодня") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        LifeStat("Привычки выполнены", "${d.done} / ${d.due}", Modifier.weight(1f))
                        LifeStat(
                            "Калории в дневнике",
                            NutritionNumber.rounded(n.totals.calories),
                            Modifier.weight(1f),
                        )
                    }
                    MacroStrip(n.totals)
                    Text(
                        "Вода ${NutritionNumber.format(n.waterMl)} л",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.goals.isNotEmpty())
                    Section("Ваши цели") {
                        s.goals.forEach { g ->
                            Text(g.name, style = MaterialTheme.typography.titleMedium)
                            LifeProgressBar(g.current, g.target)
                            Text(
                                goalValue(g),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        }
    }
}
