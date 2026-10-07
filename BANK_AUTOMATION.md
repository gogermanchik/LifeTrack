package com.lifetrack.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")

fun dateLabel(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru))

fun monthLabel(month: YearMonth) =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", ru)).replaceFirstChar { it.titlecase(ru) }

fun habitSeries(h: HabitEntity, s: Snapshot, date: LocalDate): String {
    val st = HabitStatisticsCalculator.stats(h, s.completions, date)
    return if (st.current > 0) "Серия: ${st.current} ${if(h.frequency=="WEEKLY") "нед." else "дн."}"
    else if (h.frequency == "WEEKLY") "${h.weeklyTarget} раз в неделю" else "Один шаг сегодня"
}

@Composable
fun DashboardScreen(
    s: Snapshot,
    currency: String,
    edit: (Any) -> Unit,
    toggle: (HabitEntity) -> Unit,
    navigate: (String) -> Unit,
) {
    val today = LocalDate.now()
    val d = DashboardCalculator.calculate(s, currency, today)
    val n = NutritionCalculator.daily(s.nutrition, today)
    val goal = s.nutrition.goal
    val habits = s.habits.filter { !it.archived && HabitStatisticsCalculator.due(it, today) }
    val spending = FinanceCalculator.stats(s, today, today, currency).expense
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 84.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    (when (LocalTime.now().hour) {
                        in 5..11 -> "Доброе утро"
                        in 12..17 -> "Добрый день"
                        else -> "Добрый вечер"
                    }) +
                        (s.product.preferences
                            .firstOrNull { it.key == "profile_name" }
                            ?.value
                            ?.takeIf { it.isNotBlank() }
                            ?.let { ", $it" } ?: ""),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    dateLabel(today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                { navigate("more") },
                Modifier.size(44.dp)
                    .semantics { contentDescription = "Профиль и настройки" }
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            ) {
                BrandMark(Modifier.size(25.dp))
            }
        }
        val pending = s.banking.candidates.count { it.state == "PENDING" }
        val sleep = HealthMath.daily(s.product.health, "SLEEP", today)
        val brief =
            when {
                pending > 0 -> "$pending банковских операций ждут проверки"
                sleep != null -> "Ваша ночь: ${sleep/3600000} ч ${sleep%3600000/60000} мин сна"
                d.due > 0 && d.done == d.due -> "Все привычки на сегодня выполнены"
                d.due > d.done -> "Осталось привычек на сегодня: ${d.due-d.done}"
                else -> "Начните день с одной записи о себе"
            }
        Text(
            brief,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pending > 0)
            LifeSettingRow(
                "Проверить операции",
                "Уведомления и выписки",
                { navigate("finance-inbox") },
            )
        HealthSummaryCard(s.product) { navigate("health") }
        Surface(
            color = lifeColors.hero,
            shape = MaterialTheme.shapes.extraLarge,
            onClick = { navigate("nutrition") },
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Питание сегодня",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = lifeColors.onHero,
                    )
                    Text(
                        if ((goal?.dailyCalories ?: 0) > 0)
                            "${percent(n.totals.calories,goal!!.dailyCalories)}%"
                        else "Твой ритм",
                        style = MaterialTheme.typography.labelMedium,
                        color = lifeColors.onHero.copy(alpha = .8f),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    LifeProgressRing(
                        n.totals.calories,
                        goal?.dailyCalories ?: 0,
                        Modifier.size(88.dp),
                        color = Color(0xFFC4EBBD),
                        track = Color.White.copy(alpha = .13f),
                    ) {
                        Icon(
                            Icons.Outlined.Restaurant,
                            null,
                            tint = lifeColors.onHero,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            NutritionNumber.rounded(n.totals.calories),
                            style = MaterialTheme.typography.displayMedium,
                            color = lifeColors.onHero,
                        )
                        Text(
                            if ((goal?.dailyCalories ?: 0) > 0)
                                "из ${NutritionNumber.rounded(goal!!.dailyCalories)} ккал"
                            else "ккал в дневнике",
                            style = MaterialTheme.typography.bodySmall,
                            color = lifeColors.onHero.copy(alpha = .75f),
                        )
                        if ((goal?.dailyCalories ?: 0) > 0)
                            Text(
                                if (n.totals.calories <= goal!!.dailyCalories)
                                    "Осталось ${NutritionNumber.rounded(goal.dailyCalories-n.totals.calories)} ккал"
                                else "Цель превышена",
                                style = MaterialTheme.typography.labelMedium,
                                color = lifeColors.onHero,
                            )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Б" to n.totals.protein, "Ж" to n.totals.fat, "У" to n.totals.carbs)
                        .forEach { (label, v) ->
                            Text(
                                "$label  ${macroValue(v)} г",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = lifeColors.onHero.copy(alpha = .85f),
                            )
                        }
                }
                HorizontalDivider(color = Color.White.copy(alpha = .12f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(17.dp),
                        tint = lifeColors.onHero,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "${d.done} из ${d.due} привычек",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = lifeColors.onHero,
                    )
                    Icon(
                        Icons.Outlined.WaterDrop,
                        null,
                        Modifier.size(17.dp),
                        tint = lifeColors.onHero,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${NutritionNumber.format(n.waterMl)} л",
                        style = MaterialTheme.typography.bodySmall,
                        color = lifeColors.onHero,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                onClick = { navigate("finance") },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.AccountBalanceWallet,
                        null,
                        Modifier.size(20.dp),
                        tint = lifeColors.expense,
                    )
                    Text("Расходы сегодня", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.text.BasicText(
                        MoneyFormatter.format(spending, currency),
                        style =
                            MaterialTheme.typography.titleLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                        maxLines = 1,
                        autoSize =
                            androidx.compose.foundation.text.TextAutoSize.StepBased(
                                12.sp,
                                22.sp,
                                1.sp,
                            ),
                    )
                    val budget =
                        s.budgets.firstOrNull {
                            it.month == YearMonth.now().toString() &&
                                it.categoryId == null &&
                                it.currency == currency
                        }
                    if (budget != null) {
                        val spent = FinanceCalculator.budgetSpent(s, budget)
                        LifeProgressBar(spent, budget.amount, lifeColors.expense)
                        Text(
                            "${percent(spent,budget.amount)}% бюджета месяца",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
                        val daily = (budget.amount - spent).coerceAtLeast(0) / daysLeft
                        Text(
                            "На день ≈ ${MoneyFormatter.format(daily,currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val closest = s.goals.sortedBy { it.deadline ?: Long.MAX_VALUE }.firstOrNull()
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                onClick = { navigate("goals") },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Ближайшая цель", style = MaterialTheme.typography.bodySmall)
                    Text(
                        closest?.name ?: "Твой следующий шаг",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (closest != null) {
                        LifeProgressBar(
                            goalProgress(
                                    closest,
                                    s.product.goalLinks.firstOrNull { it.goalId == closest.id },
                                )
                                .toLong(),
                            100L,
                        )
                        Text(
                            "${goalProgress(closest,s.product.goalLinks.firstOrNull{it.goalId==closest.id})}% выполнено",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else
                        Text(
                            "Добавить цель",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                }
            }
        }
        Section("Маленькие победы", "${d.done}/${d.due}", { navigate("habits") }) {
            if (habits.isEmpty())
                LifeEmptyState(
                    "Начните с одной привычки",
                    "Небольшие действия складываются в прогресс.",
                    "Создать привычку",
                    { edit("HABIT") },
                    Icons.Outlined.CheckCircle,
                )
            habits.take(3).forEach { h ->
                LifeHabitRow(h, s, today, { toggle(h) }, { navigate("habit/${h.id}") })
            }
        }
    }
}

@Composable
fun MoneyRows(f: FinanceStats, c: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LifeStat(
            "Доходы",
            MoneyFormatter.format(f.income, c),
            Modifier.weight(1f),
            lifeColors.income,
        )
        LifeStat(
            "Расходы",
            MoneyFormatter.format(f.expense, c),
            Modifier.weight(1f),
            lifeColors.expense,
        )
    }
}

@Composable
fun LifeHabitRow(
    h: HabitEntity,
    s: Snapshot,
    date: LocalDate,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val done =
        s.completions.any {
            it.habitId == h.id && it.day == date.toEpochDay() && it.count >= h.target
        }
    val haptic = LocalHapticFeedback.current
    val bg by
        animateColorAsState(
            if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            tween(180),
            label = "habit",
        )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp)
                .clip(CircleShape)
                .background(bg)
                .then(
                    if (!done)
                        Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        )
                    else Modifier
                )
                .semantics {
                    role = Role.Checkbox
                    stateDescription = if (done) "Выполнено" else "Не выполнено"
                }
                .clickable(enabled = date <= LocalDate.now()) {
                    onToggle()
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (done)
                Icon(
                    Icons.Outlined.Check,
                    "Отметить: ${h.name}",
                    Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            else Box(Modifier.size(44.dp).semantics { contentDescription = "Отметить: ${h.name}" })
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(h.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (done) "Выполнено · ${habitSeries(h,s,date)}" else habitSeries(h, s, date),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actions.isNotEmpty()) LifeOverflow(actions)
    }
}

@Composable
fun HabitRow(h: HabitEntity, s: Snapshot, toggle: (HabitEntity) -> Unit, open: () -> Unit) =
    LifeHabitRow(h, s, LocalDate.now(), { toggle(h) }, open)

@Composable
fun HabitsScreen(
    s: Snapshot,
    edit: (Any) -> Unit,
    toggle: (HabitEntity, LocalDate) -> Unit,
    open: (HabitEntity) -> Unit,
    delete: (Any) -> Unit,
    archive: (HabitEntity) -> Unit,
) {
    var archived by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(LocalDate.now()) }
    val today = LocalDate.now()
    val week = today.minusDays(today.dayOfWeek.value.toLong() - 1)
    val habits =
        s.habits.filter {
            it.archived == archived && (archived || HabitStatisticsCalculator.due(it, selected))
        }
    val done =
        habits.count { h ->
            s.completions.any {
                it.habitId == h.id && it.day == selected.toEpochDay() && it.count >= h.target
            }
        }
    Page("Привычки", action = { edit("HABIT") }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (0..6).forEach { i ->
                val day = week.plusDays(i.toLong())
                val active = day == selected
                Column(
                    Modifier.weight(1f)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                        )
                        .clickable(enabled = day <= today) { selected = day }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[i],
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Panel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (selected == today) "Сегодня выполнено" else "Выполнено за день",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("$done / ${habits.size}", style = MaterialTheme.typography.displaySmall)
                }
                LifeProgressRing(done.toLong(), habits.size.toLong(), Modifier.size(64.dp)) {
                    BrandMark(Modifier.size(22.dp))
                }
            }
            LifeProgressBar(done.toLong(), habits.size.toLong())
        }
        LifeSegmentedControl(listOf("Активные", "Архив"), if (archived) "Архив" else "Активные") {
            archived = it == "Архив"
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (habits.isEmpty())
                LifeEmptyState(
                    if (archived) "Здесь нет архивных привычек" else "Ваш ритм начинается здесь",
                    if (archived) "Скрытые привычки сохраняют свою историю."
                    else "Выберите одно небольшое действие на каждый день.",
                    if (archived) null else "Добавить привычку",
                    { edit("HABIT") },
                    Icons.Outlined.CheckCircle,
                )
            habits.forEach { h ->
                LifeHabitRow(
                    h,
                    s,
                    selected,
                    { toggle(h, selected) },
                    { open(h) },
                    listOf(
                        "Редактировать" to { edit(h) },
                        (if (h.archived) "Вернуть из архива" else "Архивировать") to { archive(h) },
                        "Удалить" to { delete(h) },
                    ),
                )
            }
        }
        Text(
            "Маленькие шаги каждый день дают большие результаты.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
fun HabitDetail(
    h: HabitEntity,
    s: Snapshot,
    monday: Boolean,
    toggle: (LocalDate) -> Unit,
    edit: () -> Unit,
) {
    val today = LocalDate.now()
    val st = HabitStatisticsCalculator.stats(h, s.completions, today)
    Page(h.name, h.description, edit, Icons.Outlined.Edit, "Редактировать") {
        Panel {
            Text(
                "Серия: ${st.current} ${if(h.frequency=="WEEKLY") "нед." else "дн."}",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                "Время для следующего маленького шага",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LifeStat("Текущая серия", "${st.current}", Modifier.weight(1f))
                LifeStat("Лучшая", "${st.best}", Modifier.weight(1f))
                LifeStat("Всего", "${st.total}", Modifier.weight(1f))
            }
        }
        Section("Ваши последние 35 дней") {
            Text(
                "Нажмите на день, чтобы изменить отметку",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val start = today.minusDays(34)
            val offset = if (monday) start.dayOfWeek.value - 1 else start.dayOfWeek.value % 7
            val cells =
                List<LocalDate?>(offset) { null } + (0..34).map { start.plusDays(it.toLong()) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    week.forEach { d ->
                        if (d == null) Spacer(Modifier.weight(1f).height(42.dp))
                        else {
                            val done =
                                s.completions.any {
                                    it.habitId == h.id &&
                                        it.day == d.toEpochDay() &&
                                        it.count >= h.target
                                }
                            Box(
                                Modifier.weight(1f)
                                    .height(42.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(
                                        if (done) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainer
                                    )
                                    .clickable(enabled = d.toEpochDay() >= h.createdDay) {
                                        toggle(d)
                                    }
                                    .semantics {
                                        contentDescription =
                                            "${d}: ${if(done) "выполнено" else "не выполнено"}"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    d.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (done) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Section("Стабильность") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LifeStat("За 7 дней", "${st.seven}%", Modifier.weight(1f))
                LifeStat("За 30 дней", "${st.thirty}%", Modifier.weight(1f))
            }
            BarChart(
                (29 downTo 0).map { n ->
                    s.completions
                        .firstOrNull {
                            it.habitId == h.id && it.day == today.minusDays(n.toLong()).toEpochDay()
                        }
                        ?.count
                        ?.toLong() ?: 0
                },
                "Выполнения привычки за 30 дней",
            )
        }
    }
}

@Composable
fun LifeTransactionRow(
    t: TransactionEntity,
    s: Snapshot,
    currency: String,
    onOpen: () -> Unit,
    delete: () -> Unit,
) {
    val name = s.categories.firstOrNull { it.id == t.categoryId }?.name ?: "Перевод"
    val color =
        if (t.type == "EXPENSE") lifeColors.expense
        else if (t.type in listOf("INCOME", "REFUND")) lifeColors.income
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(42.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (t.type == "TRANSFER") Icons.Outlined.AccountBalanceWallet
                else if (name.contains("Еда", true)) Icons.Outlined.Restaurant
                else Icons.AutoMirrored.Outlined.ArrowForward,
                null,
                Modifier.size(20.dp),
                tint = color,
            )
        }
        Column(
            Modifier.weight(1f).clickable(onClick = onOpen),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                t.description.ifBlank { name },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (if (t.type == "REFUND") "Возврат · " else "") +
                    name +
                    " · " +
                    (s.accounts.firstOrNull { it.id == t.accountId }?.name ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            (if (t.type == "EXPENSE") "−"
            else if (t.type in listOf("INCOME", "REFUND")) "+" else "") +
                MoneyFormatter.format(t.amount, currency),
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
        LifeOverflow(listOf("Редактировать" to onOpen, "Удалить" to delete))
    }
}

@Composable
fun FinanceScreen(
    s: Snapshot,
    currency: String,
    edit: (Any) -> Unit,
    delete: (Any) -> Unit,
    navigate: (String) -> Unit,
) {
    var period by remember { mutableStateOf("Месяц") }
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Все") }
    var category by remember { mutableStateOf("Все") }
    var account by remember { mutableStateOf("Все") }
    var min by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val start =
        when (period) {
            "Неделя" -> today.minusDays(6)
            "Год" -> today.withDayOfYear(1)
            "Всё время" -> LocalDate.MIN
            else -> today.withDayOfMonth(1)
        }
    val f = FinanceCalculator.stats(s, start, today, currency)
    val all =
        s.transactions.filter {
            day(it.time) >= start &&
                day(it.time) <= today &&
                s.accounts.any { a -> a.id == it.accountId && a.currency == currency }
        }
    val groups =
        all.filter { it.type in listOf("EXPENSE", "REFUND") }
            .groupBy { it.categoryId }
            .map { (id, ts) ->
                (s.categories.firstOrNull { it.id == id }?.name ?: "Другое") to
                    sumExact(ts.map { if (it.type == "REFUND") -it.amount else it.amount })
            }
            .sortedByDescending { it.second }
    Page("Финансы") {
        val pending = s.banking.candidates.count { it.state == "PENDING" }
        if (pending > 0)
            LifeSettingRow("Требуют проверки", "$pending операций", { navigate("finance-inbox") })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                monthLabel(YearMonth.now()),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LifeOverflow(
                listOf(
                    "Автоматизация" to { navigate("bank-automation") },
                    "Импорт выписки" to { navigate("statement-import") },
                    "Подписки" to { navigate("subscriptions") },
                    "Бюджеты" to { navigate("budgets") },
                    "Счета" to { navigate("accounts") },
                    "Категории" to { navigate("categories") },
                    "Аналитика" to { navigate("analytics") },
                )
            )
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Общий баланс",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    MoneyFormatter.format(f.balance, currency),
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (f.cashFlow >= 0) "+" else "") +
                        MoneyFormatter.format(f.cashFlow, currency) +
                        " · за период",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (f.cashFlow >= 0) lifeColors.income else lifeColors.expense,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoneyRows(f, currency)
            }
        }
        LifeSegmentedControl(listOf("Неделя", "Месяц", "Год", "Всё время"), period) { period = it }
        Section("Куда уходят деньги", "Бюджеты", { navigate("budgets") }) {
            if (groups.isEmpty())
                LifeEmptyState(
                    "Пока нет расходов",
                    "Добавьте первую операцию — здесь появится структура расходов.",
                    "Добавить расход",
                    { edit("EXPENSE") },
                    Icons.Outlined.AccountBalanceWallet,
                )
            else
                groups.take(4).forEach { (name, amount) ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                MoneyFormatter.format(amount, currency),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        LifeProgressBar(amount, f.expense, lifeColors.expense.copy(alpha = .8f))
                    }
                }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Операции", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton({ search = !search }, Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Search, "Поиск", Modifier.size(20.dp))
                }
                IconButton({ filters = true }, Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreHoriz, "Фильтры", Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(search) { LifeInput("Поиск по описанию", query, { query = it }) }
            val minimum = runCatching { MoneyFormatter.parse(min) }.getOrNull()
            val maximum = runCatching { MoneyFormatter.parse(max) }.getOrNull()
            val list =
                all.filter {
                    it.description.contains(query, true) &&
                        (type == "Все" || it.type == type) &&
                        (category == "Все" ||
                            it.categoryId == category.substringBefore(":").toLongOrNull()) &&
                        (account == "Все" ||
                            it.accountId == account.substringBefore(":").toLongOrNull() ||
                            it.toAccountId == account.substringBefore(":").toLongOrNull()) &&
                        (minimum == null || it.amount >= minimum) &&
                        (maximum == null || it.amount <= maximum)
                }
            if (list.isEmpty())
                LifeEmptyState(
                    "Здесь пока пусто",
                    "Попробуйте другой период или добавьте операцию.",
                )
            list
                .groupBy { day(it.time) }
                .forEach { (date, tx) ->
                    Text(
                        if (date == today) "Сегодня"
                        else date.format(DateTimeFormatter.ofPattern("d MMMM", ru)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    tx.forEach { t ->
                        LifeTransactionRow(t, s, currency, { edit(t) }, { delete(t) })
                    }
                }
        }
        Spacer(Modifier.height(44.dp))
    }
    if (filters)
        LifeBottomSheet({ filters = false }) {
            Text("Фильтры операций", style = MaterialTheme.typography.headlineSmall)
            Choice("Тип", type, listOf("Все", "EXPENSE", "INCOME", "TRANSFER", "REFUND")) {
                type = it
            }
            Choice(
                "Категория",
                category,
                listOf("Все") + s.categories.map { "${it.id}: ${it.name}" },
            ) {
                category = it
            }
            Choice("Счёт", account, listOf("Все") + s.accounts.map { "${it.id}: ${it.name}" }) {
                account = it
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { LifeInput("Сумма от", min, { min = it }, true) }
                Box(Modifier.weight(1f)) { LifeInput("Сумма до", max, { max = it }, true) }
            }
            LifePrimaryButton("Показать операции", { filters = false }, Modifier.fillMaxWidth())
            TextButton({
                type = "Все"
                category = "Все"
                account = "Все"
                min = ""
                max = ""
                query = ""
                filters = false
            }) {
                Text("Сбросить фильтры")
            }
        }
}

fun goalProgress(g: GoalEntity, link: GoalLink?): Int =
    if (link?.kind == "WEIGHT" && link.initial != g.target)
        ((link.initial - g.current).toDouble() / (link.initial - g.target) * 100)
            .toInt()
            .coerceIn(0, 100)
    else percent(g.current, g.target)

fun goalValue(g: GoalEntity) =
    if (g.type == "FINANCIAL")
        "${MoneyFormatter.format(g.current,g.currency)} из ${MoneyFormatter.format(g.target,g.currency)}"
    else "${g.current} из ${g.target}"

@Composable
fun GoalCard(g: GoalEntity, edit: () -> Unit, delete: (() -> Unit)?, link: GoalLink? = null) {
    val progress =
        if (link?.kind == "WEIGHT" && link.initial != g.target)
            ((link.initial - g.current).toDouble() / (link.initial - g.target) * 100)
                .toInt()
                .coerceIn(0, 100)
        else percent(g.current, g.target)
    val value =
        if (link?.kind == "WEIGHT") "${g.current/1000.0} → ${g.target/1000.0} кг"
        else if (link != null && g.type != "FINANCIAL") "${g.current} из ${g.target} ${link.unit}"
        else goalValue(g)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (g.type == "FINANCIAL") "Финансовая цель" else "Личный прогресс",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(g.name, style = MaterialTheme.typography.titleLarge)
            }
            if (delete != null)
                LifeOverflow(listOf("Обновить прогресс" to edit, "Удалить" to delete))
        }
        Text("$progress%", style = MaterialTheme.typography.displaySmall)
        LifeProgressBar(progress.toLong(), 100L)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        g.deadline?.let {
            val left = it - LocalDate.now().toEpochDay()
            Text(
                if (left >= 0) "Ещё $left дней · ${LocalDate.ofEpochDay(it)}"
                else "Срок завершён · ${LocalDate.ofEpochDay(it)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(edit, contentPadding = PaddingValues(0.dp)) { Text("Обновить прогресс") }
    }
}

@Composable
fun GoalsScreen(s: Snapshot, edit: (Any) -> Unit, delete: (Any) -> Unit) {
    Page("Цели", "То, что важно именно вам", { edit("GOAL") }) {
        if (s.goals.isEmpty())
            LifeEmptyState(
                "Дайте планам направление",
                "Добавьте цель и отмечайте движение к ней.",
                "Создать цель",
                { edit("GOAL") },
                Icons.Outlined.CheckCircle,
            )
        s.goals.forEach { g ->
            GoalCard(
                g,
                { edit(g) },
                { delete(g) },
                s.product.goalLinks.firstOrNull { it.goalId == g.id },
            )
        }
    }
}

@Composable
fun BudgetCard(b: BudgetEntity, s: Snapshot, edit: () -> Unit, delete: (() -> Unit)?) {
    val spent = FinanceCalculator.budgetSpent(s, b)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                b.categoryId?.let { id -> s.categories.firstOrNull { it.id == id }?.name }
                    ?: "Общий бюджет",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            if (delete != null) LifeOverflow(listOf("Изменить" to edit, "Удалить" to delete))
        }
        Text(
            MoneyFormatter.format(spent, b.currency),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "из ${MoneyFormatter.format(b.amount,b.currency)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LifeProgressBar(
            spent,
            b.amount,
            if (spent > b.amount) lifeColors.expense else MaterialTheme.colorScheme.primary,
        )
        if (b.month == YearMonth.now().toString() && spent > 0) {
            val today = LocalDate.now()
            val forecast =
                java.math.BigDecimal.valueOf(spent)
                    .multiply(java.math.BigDecimal.valueOf(today.lengthOfMonth().toLong()))
                    .divide(
                        java.math.BigDecimal.valueOf(today.dayOfMonth.toLong()),
                        0,
                        java.math.RoundingMode.HALF_UP,
                    )
                    .longValueExact()
            Text(
                "При текущем темпе: ${MoneyFormatter.format(forecast,b.currency)} за месяц",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (BudgetCalculator.warning(spent, b.amount).isNotBlank())
            Text(
                if (spent > b.amount)
                    "Выше бюджета на ${MoneyFormatter.format(spent-b.amount,b.currency)}"
                else "Бюджет почти использован",
                style = MaterialTheme.typography.bodySmall,
                color = lifeColors.warning,
            )
    }
}

@Composable
fun MonthSelector(month: YearMonth, change: (YearMonth) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton({ change(month.minusMonths(1)) }, Modifier.size(44.dp)) {
            Icon(Icons.Outlined.ChevronLeft, "Предыдущий месяц")
        }
        Text(monthLabel(month), style = MaterialTheme.typography.titleSmall)
        IconButton({ change(month.plusMonths(1)) }, Modifier.size(44.dp)) {
            Icon(Icons.Outlined.ChevronRight, "Следующий месяц")
        }
    }
}

@Composable
fun BudgetsScreen(s: Snapshot, c: String, edit: (Any) -> Unit, delete: (Any) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    Page(
        "Бюджеты",
        action = { edit(BudgetEntity(month = month.toString(), amount = 0, currency = c)) },
    ) {
        MonthSelector(month) { month = it }
        val list = s.budgets.filter { it.month == month.toString() && it.currency == c }
        if (list.isEmpty())
            LifeEmptyState(
                "План на месяц",
                "Установите лимит, чтобы видеть, сколько можно потратить.",
                "Добавить бюджет",
                { edit(BudgetEntity(month = month.toString(), amount = 0, currency = c)) },
                Icons.Outlined.AccountBalanceWallet,
            )
        list.forEach { BudgetCard(it, s, { edit(it) }, { delete(it) }) }
    }
}

@Composable
fun FinanceAnalyticsContent(s: Snapshot, c: String, month: YearMonth) {
    val f = FinanceCalculator.stats(s, month.atDay(1), month.atEndOfMonth(), c)
    val prev =
        FinanceCalculator.stats(
            s,
            month.minusMonths(1).atDay(1),
            month.minusMonths(1).atEndOfMonth(),
            c,
        )
    Panel {
        Text(
            "Расходы за месяц",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(MoneyFormatter.format(f.expense, c), style = MaterialTheme.typography.displaySmall)
        Text(
            "${MoneyFormatter.format(f.expense-prev.expense,c)} к прошлому месяцу",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyRows(f, c)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LifeStat("Денежный поток", MoneyFormatter.format(f.cashFlow, c), Modifier.weight(1f))
            LifeStat("Доля накоплений", "${f.savingsRate}%", Modifier.weight(1f))
        }
    }
    Section("Расходы по категориям") {
        val groups =
            s.transactions
                .filter {
                    YearMonth.from(day(it.time)) == month &&
                        it.type == "EXPENSE" &&
                        s.accounts.any { a -> a.id == it.accountId && a.currency == c }
                }
                .groupBy { it.categoryId }
                .map { (id, tx) ->
                    (s.categories.firstOrNull { it.id == id }?.name ?: "Другое") to
                        sumExact(tx.map { it.amount })
                }
                .sortedByDescending { it.second }
        DonutChart(groups, c)
    }
    Section("Шесть месяцев") {
        BarChart(
            (5 downTo 0).map { n ->
                val m = month.minusMonths(n.toLong())
                FinanceCalculator.stats(s, m.atDay(1), m.atEndOfMonth(), c).expense
            },
            "Расходы за шесть месяцев",
        )
        Text(
            "${monthLabel(month.minusMonths(5))} — ${monthLabel(month)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section("Баланс по дням") {
        val balances =
            (1..month.lengthOfMonth()).map { n ->
                val date = month.atDay(n)
                FinanceCalculator.stats(
                        s.copy(transactions = s.transactions.filter { day(it.time) <= date }),
                        month.atDay(1),
                        date,
                        c,
                    )
                    .balance
            }
        LineChart(balances, "Баланс по дням месяца")
        Text(
            "Средний расход: ${MoneyFormatter.format(java.math.BigDecimal.valueOf(f.expense).divide(java.math.BigDecimal(if(month==YearMonth.now()) LocalDate.now().dayOfMonth else month.lengthOfMonth()),0,java.math.RoundingMode.HALF_UP).longValueExact(),c)} / день",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun HabitAnalyticsContent(s: Snapshot, month: YearMonth) {
    val today = minOf(LocalDate.now(), month.atEndOfMonth())
    val habits = s.habits.filter { !it.archived && it.createdDay <= today.toEpochDay() }
    if (habits.isEmpty())
        LifeEmptyState("Пока нет статистики", "Отмечайте привычки — ваш прогресс появится здесь.")
    habits.forEach { h ->
        val st = HabitStatisticsCalculator.stats(h, s.completions, today)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(h.name, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LifeStat("За 30 дней", "${st.thirty}%", Modifier.weight(1f))
                LifeStat("За 7 дней", "${st.seven}%", Modifier.weight(1f))
                LifeStat("Лучшая серия", "${st.best}", Modifier.weight(1f))
            }
            LifeProgressBar(st.thirty.toLong(), 100)
            Text(
                habitSeries(h, s, today),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val days = if (month == YearMonth.now()) LocalDate.now().dayOfMonth else month.lengthOfMonth()
    var completed = 0L
    var expected = 0L
    for (n in 1..days) {
        val d = month.atDay(n)
        val due = habits.filter { HabitStatisticsCalculator.due(it, d) }
        expected += due.size
        completed +=
            due.count { h ->
                s.completions.any {
                    it.habitId == h.id && it.day == d.toEpochDay() && it.count >= h.target
                }
            }
    }
    Panel {
        Text("${percent(completed,expected)}%", style = MaterialTheme.typography.displaySmall)
        Text(
            "Выполнение за месяц · $completed из $expected дней",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Section("Ритм за 30 дней") {
        LineChart(
            (29 downTo 0).map { n ->
                val d = today.minusDays(n.toLong())
                val due = habits.filter { HabitStatisticsCalculator.due(it, d) }
                percent(
                        due.count { h ->
                                s.completions.any {
                                    it.habitId == h.id &&
                                        it.day == d.toEpochDay() &&
                                        it.count >= h.target
                                }
                            }
                            .toLong(),
                        due.size.toLong(),
                    )
                    .toLong()
            },
            "Выполнение привычек за 30 дней",
        )
    }
}

@Composable
fun AnalyticsScreen(s: Snapshot, c: String, report: Boolean, section: String = "ALL") {
    var month by remember { mutableStateOf(YearMonth.now()) }
    Page(if (report) "Отчёт за месяц" else "Аналитика") {
        MonthSelector(month) { month = it }
        if (section != "HABITS") FinanceAnalyticsContent(s, c, month)
        if (section != "FINANCE") HabitAnalyticsContent(s, month)
    }
}
