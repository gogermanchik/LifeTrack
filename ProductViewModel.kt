package com.lifetrack.domain

import com.lifetrack.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

object NutritionNumber {
    fun parse(text: String, allowZero: Boolean = true): Long =
        text
            .trim()
            .replace(" ", "")
            .replace(",", ".")
            .toBigDecimal()
            .setScale(3, RoundingMode.UNNECESSARY)
            .movePointRight(3)
            .longValueExact()
            .also {
                require(it >= 0 && (allowZero || it > 0)) { "Введите положительное значение" }
                require(it <= 1_000_000_000) { "Значение слишком велико" }
            }

    fun format(value: Long): String =
        BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString().replace('.', ',')

    fun input(value: Long): String =
        BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString()

    fun rounded(value: Long): String =
        BigDecimal.valueOf(value, 3).setScale(0, RoundingMode.HALF_UP).toPlainString()
}

data class NutritionTotals(
    val calories: Long = 0,
    val protein: Long = 0,
    val fat: Long = 0,
    val carbs: Long = 0,
)

data class NutritionDay(
    val totals: NutritionTotals,
    val meals: Map<String, NutritionTotals>,
    val waterMl: Long,
    val logs: List<FoodLog>,
)

object FoodLogCalculator {
    fun scale(value: Long, amount: Long, serving: Long): Long {
        require(value >= 0 && amount > 0 && serving > 0)
        return BigDecimal.valueOf(value)
            .multiply(BigDecimal.valueOf(amount))
            .divide(BigDecimal.valueOf(serving), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun fromItem(
        item: FoodItem,
        amount: Long,
        meal: String,
        time: Long,
        source: String = "SEARCH",
    ): FoodLog =
        FoodLog(
            foodItemId = item.id,
            customName = item.name,
            mealType = meal,
            amount = amount,
            unit = item.servingUnit,
            calories = scale(item.calories, amount, item.servingSize),
            protein = scale(item.protein, amount, item.servingSize),
            fat = scale(item.fat, amount, item.servingSize),
            carbs = scale(item.carbs, amount, item.servingSize),
            dateTime = time,
            source = source,
        )
}

object NutritionCalculator {
    fun totals(logs: List<FoodLog>) =
        NutritionTotals(
            sumExact(logs.map { it.calories }),
            sumExact(logs.map { it.protein }),
            sumExact(logs.map { it.fat }),
            sumExact(logs.map { it.carbs }),
        )

    fun daily(s: NutritionSnapshot, date: LocalDate): NutritionDay {
        val logs = s.logs.filter { day(it.dateTime) == date }
        return NutritionDay(
            totals(logs),
            logs.groupBy { it.mealType }.mapValues { totals(it.value) },
            sumExact(s.water.filter { day(it.dateTime) == date }.map { it.amountMl }),
            logs,
        )
    }

    fun period(s: NutritionSnapshot, start: LocalDate, end: LocalDate): List<NutritionDay> {
        require(!end.isBefore(start))
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { it <= end }
            .map { daily(s, it) }
            .toList()
    }

    fun average(values: List<Long>): Long =
        if (values.isEmpty()) 0
        else
            BigDecimal.valueOf(sumExact(values))
                .divide(BigDecimal.valueOf(values.size.toLong()), 0, RoundingMode.HALF_UP)
                .longValueExact()
}

fun mealName(type: String): String =
    when (type) {
        "BREAKFAST" -> "Завтрак"
        "LUNCH" -> "Обед"
        "DINNER" -> "Ужин"
        else -> "Перекус"
    }

val MEALS = listOf("BREAKFAST", "LUNCH", "DINNER", "SNACK")
