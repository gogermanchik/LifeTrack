package com.lifetrack.domain

import com.lifetrack.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.*
import java.util.Locale

object MoneyFormatter {
    fun parse(text: String): Long =
        text
            .trim()
            .replace(" ", "")
            .replace(",", ".")
            .toBigDecimal()
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
            .also { require(it > 0) { "Сумма должна быть больше нуля" } }

    fun format(minor: Long, currency: String): String =
        DecimalFormat(
                "#,##0.##",
                DecimalFormatSymbols(Locale.ROOT).apply {
                    groupingSeparator = ' '
                    decimalSeparator = ','
                },
            )
            .format(BigDecimal.valueOf(minor, 2))
            .replace(' ', ' ') +
            " " +
            when (currency) {
                "RUB" -> "₽"
                "USD" -> "$"
                "EUR" -> "€"
                "SEK" -> "kr"
                "GBP" -> "£"
                "CHF" -> "CHF"
                "KZT" -> "₸"
                "BYN" -> "Br"
                "UAH" -> "₴"
                "TRY" -> "₺"
                "AED" -> "AED"
                else -> currency
            }

    fun input(minor: Long) = BigDecimal.valueOf(minor, 2).toPlainString()
}

fun percent(value: Long, total: Long): Int =
    if (total <= 0) 0
    else
        BigDecimal.valueOf(value)
            .multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP)
            .coerceIn(
                BigDecimal.valueOf(Int.MIN_VALUE.toLong()),
                BigDecimal.valueOf(Int.MAX_VALUE.toLong()),
            )
            .toInt()

fun sumExact(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)

fun day(epoch: Long): LocalDate =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()

data class FinanceStats(val income: Long, val expense: Long, val balance: Long) {
    val cashFlow
        get() = Math.subtractExact(income, expense)

    val savingsRate
        get() = percent(cashFlow, income)
}

object FinanceCalculator {
    fun stats(s: Snapshot, start: LocalDate, end: LocalDate, currency: String): FinanceStats {
        val accounts = s.accounts.filter { it.currency == currency }.map { it.id }.toSet()
        val selected =
            s.transactions.filter {
                it.accountId in accounts && day(it.time) >= start && day(it.time) <= end
            }
        return FinanceStats(
            sumExact(selected.filter { it.type == "INCOME" }.map { it.amount }),
            sumExact(
                selected
                    .filter { it.type in listOf("EXPENSE", "REFUND") }
                    .map { if (it.type == "REFUND") -it.amount else it.amount }
            ),
            sumExact(s.accounts.filter { it.id in accounts }.map { balance(s, it) }),
        )
    }

    fun balance(s: Snapshot, a: AccountEntity): Long =
        s.transactions.fold(a.opening) { balance, t ->
            var delta = 0L
            if (t.accountId == a.id)
                delta = if (t.type in listOf("INCOME", "REFUND")) t.amount else -t.amount
            if (t.type == "TRANSFER" && t.toAccountId == a.id)
                delta = Math.addExact(delta, t.amount)
            Math.addExact(balance, delta)
        }

    fun budgetSpent(s: Snapshot, b: BudgetEntity): Long =
        sumExact(
            s.transactions
                .filter {
                    it.type in listOf("EXPENSE", "REFUND") &&
                        YearMonth.from(day(it.time)).toString() == b.month &&
                        (b.categoryId == null || b.categoryId == it.categoryId) &&
                        s.accounts.any { a -> a.id == it.accountId && a.currency == b.currency }
                }
                .map { if (it.type == "REFUND") -it.amount else it.amount }
        )
}

data class HabitStats(
    val current: Int,
    val best: Int,
    val total: Int,
    val rate: Int,
    val seven: Int,
    val thirty: Int,
)

object HabitStatisticsCalculator {
    fun due(h: HabitEntity, d: LocalDate): Boolean =
        d.toEpochDay() >= h.createdDay &&
            (h.frequency != "DAYS" || d.dayOfWeek.value.toString() in h.days.split(","))

    fun stats(
        h: HabitEntity,
        completions: List<HabitCompletionEntity>,
        today: LocalDate,
    ): HabitStats {
        val dates =
            completions
                .filter {
                    it.habitId == h.id &&
                        it.count >= h.target &&
                        it.day <= today.toEpochDay() &&
                        it.day >= h.createdDay
                }
                .map { LocalDate.ofEpochDay(it.day) }
                .toSet()
        val start = LocalDate.ofEpochDay(h.createdDay)
        if (start > today) return HabitStats(0, 0, 0, 0, 0, 0)
        if (h.frequency == "WEEKLY") {
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val weeks =
                generateSequence(start.minusDays((start.dayOfWeek.value - 1).toLong())) {
                        it.plusWeeks(1)
                    }
                    .takeWhile { it <= monday }
                    .toList()
            val hits =
                weeks.map { w -> dates.count { it >= w && it < w.plusWeeks(1) } >= h.weeklyTarget }
            var best = 0
            var run = 0
            hits.forEach {
                run = if (it) run + 1 else 0
                best = maxOf(best, run)
            }
            val finished = if (!hits.last()) hits.dropLast(1) else hits
            val current = finished.asReversed().takeWhile { it }.size
            fun rate(n: Int): Int {
                val window = dates.count { it >= today.minusDays(n - 1L) }
                return minOf(100, percent(window.toLong(), ((n + 6) / 7 * h.weeklyTarget).toLong()))
            }
            return HabitStats(
                current,
                best,
                dates.size,
                percent(hits.count { it }.toLong(), hits.size.toLong()),
                rate(7),
                rate(30),
            )
        }
        val days =
            generateSequence(start) { it.plusDays(1) }
                .takeWhile { it <= today }
                .filter { due(h, it) }
                .toList()
        var best = 0
        var run = 0
        days.forEach {
            run = if (it in dates) run + 1 else 0
            best = maxOf(best, run)
        }
        val ended = if (days.lastOrNull() == today && today !in dates) days.dropLast(1) else days
        fun rate(n: Int): Int {
            val ds = days.filter { it >= today.minusDays(n - 1L) }
            return percent(ds.count { it in dates }.toLong(), ds.size.toLong())
        }
        return HabitStats(
            ended.asReversed().takeWhile { it in dates }.size,
            best,
            dates.size,
            percent(days.count { it in dates }.toLong(), days.size.toLong()),
            rate(7),
            rate(30),
        )
    }
}

data class Dashboard(
    val finance: FinanceStats,
    val previous: FinanceStats,
    val done: Int,
    val due: Int,
    val streak: Int,
)

object DashboardCalculator {
    fun calculate(s: Snapshot, currency: String, today: LocalDate): Dashboard {
        val month = YearMonth.from(today)
        val habits = s.habits.filter { !it.archived && HabitStatisticsCalculator.due(it, today) }
        return Dashboard(
            FinanceCalculator.stats(s, month.atDay(1), month.atEndOfMonth(), currency),
            FinanceCalculator.stats(
                s,
                month.minusMonths(1).atDay(1),
                month.minusMonths(1).atEndOfMonth(),
                currency,
            ),
            habits.count { h ->
                s.completions.any {
                    it.habitId == h.id && it.day == today.toEpochDay() && it.count >= h.target
                }
            },
            habits.size,
            habits.maxOfOrNull { HabitStatisticsCalculator.stats(it, s.completions, today).current }
                ?: 0,
        )
    }
}

object BudgetCalculator {
    fun warning(spent: Long, limit: Long): String =
        when {
            limit <= 0 -> ""
            spent > limit -> "OVER"
            spent == limit -> "FULL"
            BigDecimal.valueOf(spent).multiply(BigDecimal(100)) >=
                BigDecimal.valueOf(limit).multiply(BigDecimal(80)) -> "NEAR"
            else -> ""
        }
}
