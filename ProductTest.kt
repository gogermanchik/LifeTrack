package com.lifetrack.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LifeDateTimeField(value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val d = runCatching { LocalDateTime.parse(value) }.getOrDefault(LocalDateTime.now())
    LifeSettingRow(
        "Когда",
        d.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru"))),
        onClick = {
            DatePickerDialog(
                    context,
                    { _, y, m, day ->
                        val selected = LocalDate.of(y, m + 1, day)
                        TimePickerDialog(
                                context,
                                { _, h, min -> change(selected.atTime(h, min).toString()) },
                                d.hour,
                                d.minute,
                                true,
                            )
                            .show()
                    },
                    d.year,
                    d.monthValue - 1,
                    d.dayOfMonth,
                )
                .show()
        },
    )
}

@Composable
fun LifeDateField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val d = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    LifeSettingRow(
        label,
        if (value.isBlank()) "Не задан"
        else d.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))),
        onClick = {
            DatePickerDialog(
                    context,
                    { _, y, m, day -> change(LocalDate.of(y, m + 1, day).toString()) },
                    d.year,
                    d.monthValue - 1,
                    d.dayOfMonth,
                )
                .show()
        },
    )
    if (value.isNotBlank())
        TextButton({ change("") }, contentPadding = PaddingValues(0.dp)) { Text("Убрать срок") }
}

@Composable
fun LifeTimeField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    val t = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
    LifeSettingRow(
        label,
        if (value.isBlank()) "Выключено" else value,
        onClick = {
            TimePickerDialog(
                    context,
                    { _, h, m -> change(LocalTime.of(h, m).toString()) },
                    t.hour,
                    t.minute,
                    true,
                )
                .show()
        },
    )
    if (value.isNotBlank())
        TextButton({ change("") }, contentPadding = PaddingValues(0.dp)) { Text("Отключить") }
}

@Composable
fun LifeMonthField(value: String, change: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val initial = runCatching { YearMonth.parse(value) }.getOrDefault(YearMonth.now())
    LifeSettingRow("Месяц", monthLabel(initial), onClick = { show = true })
    if (show)
        LifeBottomSheet({ show = false }) {
            var month by remember { mutableStateOf(initial) }
            Text("Выберите месяц", style = MaterialTheme.typography.headlineSmall)
            MonthSelector(month) { month = it }
            LifePrimaryButton(
                "Выбрать",
                {
                    change(month.toString())
                    show = false
                },
                Modifier.fillMaxWidth(),
            )
        }
}

@Composable
fun LifeWeekdays(value: String, change: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Дни недели",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { i ->
                val selected = value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                val active = i in selected
                Box(
                    Modifier.weight(1f)
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.shapes.extraSmall,
                        )
                        .clickable {
                            val next = if (active) selected - i else selected + i
                            change(next.sorted().joinToString(","))
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[i - 1],
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
