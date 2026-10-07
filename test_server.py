package com.lifetrack.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

object LifeSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

@Composable
fun Section(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LifeSectionHeader(title, action, onAction)
        content()
    }
}

@Composable fun NutritionProgress(value: Long, target: Long) = LifeProgressBar(value, target)

@Composable
fun LifeProgressRing(
    value: Long,
    target: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = color.copy(alpha = .12f),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val fraction = if (target <= 0) 0f else percent(value, target).coerceIn(0, 100) / 100f
    val p by animateFloatAsState(fraction, tween(220), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxSize().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
        ) {
            val stroke = 8.dp.toPx()
            val pad = stroke / 2
            val side = minOf(size.width, size.height) - stroke
            val offset =
                androidx.compose.ui.geometry.Offset(
                    (size.width - side) / 2,
                    (size.height - side) / 2,
                )
            drawArc(
                track,
                -90f,
                360f,
                false,
                offset,
                androidx.compose.ui.geometry.Size(side, side),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (p > 0)
                drawArc(
                    color,
                    -90f,
                    360 * p,
                    false,
                    offset,
                    androidx.compose.ui.geometry.Size(side, side),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
        }
        content()
    }
}

fun macroValue(value: Long): String =
    java.math.BigDecimal.valueOf(value, 3)
        .setScale(1, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .replace('.', ',')

@Composable
fun LifeMacroProgress(
    label: String,
    value: Long,
    target: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            macroValue(value) +
                (if (target > 0) " / ${NutritionNumber.format(target)} г" else " г"),
            style = MaterialTheme.typography.titleSmall,
        )
        if (target > 0) LifeProgressBar(value, target, color)
        else
            Box(
                Modifier.fillMaxWidth()
                    .height(4.dp)
                    .background(color.copy(alpha = .18f), CircleShape)
            )
    }
}

@Composable
fun MacroStrip(t: NutritionTotals, g: NutritionGoal? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LifeMacroProgress(
            "Белки",
            t.protein,
            g?.proteinGrams ?: 0,
            lifeColors.protein,
            Modifier.weight(1f),
        )
        LifeMacroProgress("Жиры", t.fat, g?.fatGrams ?: 0, lifeColors.fat, Modifier.weight(1f))
        LifeMacroProgress(
            "Углеводы",
            t.carbs,
            g?.carbsGrams ?: 0,
            lifeColors.carbs,
            Modifier.weight(1f),
        )
    }
}

@Composable
fun NutritionSummary(d: NutritionDay, g: NutritionGoal?, editGoal: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LifeProgressRing(d.totals.calories, g?.dailyCalories ?: 0, Modifier.size(176.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    NutritionNumber.rounded(d.totals.calories),
                    style = MaterialTheme.typography.displayMedium,
                )
                Text(
                    if ((g?.dailyCalories ?: 0) > 0)
                        "из ${NutritionNumber.rounded(g!!.dailyCalories)} ккал"
                    else "ккал за день",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if ((g?.dailyCalories ?: 0) > 0)
                    Text(
                        if (d.totals.calories <= g!!.dailyCalories)
                            "Осталось ${NutritionNumber.rounded(g.dailyCalories-d.totals.calories)}"
                        else
                            "+${NutritionNumber.rounded(d.totals.calories-g.dailyCalories)} к цели",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
            }
        }
        MacroStrip(d.totals, g)
        TextButton(
            editGoal,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                if (g == null) "Задать личные цели" else "Мои цели",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun DateSelector(date: LocalDate, change: (LocalDate) -> Unit) {
    var history by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton({ change(date.minusDays(1)) }, Modifier.size(44.dp)) {
            Icon(Icons.Outlined.ChevronLeft, "Предыдущий день", Modifier.size(20.dp))
        }
        Text(
            if (date == LocalDate.now())
                "Сегодня, " +
                    date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru")))
            else
                date.format(
                    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))
                ),
            Modifier.clickable { history = true }.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(
            { change(date.plusDays(1)) },
            Modifier.size(44.dp),
            enabled = date < LocalDate.now(),
        ) {
            Icon(Icons.Outlined.ChevronRight, "Следующий день", Modifier.size(20.dp))
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(history) {
        if (history) {
            android.app
                .DatePickerDialog(
                    context,
                    { _, y, m, d ->
                        change(LocalDate.of(y, m + 1, d))
                        history = false
                    },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth,
                )
                .apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    setOnDismissListener { history = false }
                }
                .show()
        }
    }
}

@Composable
fun NutritionField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    numeric: Boolean = true,
) = LifeInput(label, value, onChange, numeric)

@Composable
fun LifeMealRow(log: FoodLog, onOpen: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp)
                .background(lifeColors.food.copy(alpha = .10f), MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (log.source == "PHOTO") Icons.Outlined.PhotoCamera
                else Icons.Outlined.Restaurant,
                null,
                Modifier.size(21.dp),
                tint = lifeColors.food,
            )
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(log.customName, style = MaterialTheme.typography.titleSmall)
            Text(
                "${NutritionNumber.rounded(log.calories)} ккал · Б ${NutritionNumber.format(log.protein)} · Ж ${NutritionNumber.format(log.fat)} · У ${NutritionNumber.format(log.carbs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (log.recognitionDemo)
                Text(
                    "Демонстрационный пример",
                    style = MaterialTheme.typography.labelSmall,
                    color = lifeColors.food,
                )
        }
        LifeOverflow(actions)
    }
}
