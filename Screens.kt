package com.lifetrack.domain

import com.lifetrack.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

object Portions {
    fun scaled(value: Long, amount: Long, base: Long = 100_000): Long {
        require(value >= 0 && amount > 0 && base > 0)
        return BigDecimal.valueOf(value)
            .multiply(BigDecimal.valueOf(amount))
            .divide(BigDecimal.valueOf(base), 0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun log(food: CatalogFood, amount: Long, unit: String, meal: String, time: Long): FoodLog {
        val baseAmount =
            when (unit) {
                food.baseUnit -> amount
                "порция",
                "шт" ->
                    scaled(
                        requireNotNull(food.servingAmount) {
                            "Укажите массу или объём одной порции"
                        },
                        amount,
                        1000,
                    )
                else -> error("Граммы и миллилитры нельзя переводить без плотности")
            }
        require(baseAmount in 1..10_000_000)
        return FoodLog(
            customName = food.name,
            mealType = meal,
            amount = baseAmount,
            unit = food.baseUnit,
            calories = scaled(requireNotNull(food.calories) { "Укажите калорийность" }, baseAmount),
            protein = scaled(requireNotNull(food.protein) { "Укажите белки" }, baseAmount),
            fat = scaled(requireNotNull(food.fat) { "Укажите жиры" }, baseAmount),
            carbs = scaled(requireNotNull(food.carbs) { "Укажите углеводы" }, baseAmount),
            dateTime = time,
            source = "SEARCH",
            composition = food.brand,
        )
    }

    fun recipe(
        recipe: Recipe,
        ingredients: List<RecipeIngredient>,
        servings: Long,
        meal: String,
        time: Long,
    ): FoodLog {
        require(recipe.servings > 0 && ingredients.isNotEmpty() && servings > 0)
        fun sum(f: (RecipeIngredient) -> Long) =
            ingredients.fold(0L) { a, b -> Math.addExact(a, f(b)) }
        fun portion(v: Long) = scaled(v, servings, recipe.servings * 1000L)
        return FoodLog(
            customName = recipe.name,
            mealType = meal,
            amount = servings,
            unit = "порция",
            calories = portion(sum { it.calories }),
            protein = portion(sum { it.protein }),
            fat = portion(sum { it.fat }),
            carbs = portion(sum { it.carbs }),
            dateTime = time,
            source = "SEARCH",
            composition =
                ingredients.joinToString("\n") { "${it.name} — ${it.amount/1000.0} ${it.unit}" },
        )
    }
}

object BarcodeValue {
    fun expandUpce(raw: String): String {
        require(raw.length == 8 && raw.all { it.isDigit() } && raw.first() in listOf('0', '1'))
        val d = raw.substring(1, 7)
        val lead = raw.substring(0, 1)
        val check = raw.last()
        val body =
            when (d.last()) {
                '0',
                '1',
                '2' -> lead + d.substring(0, 2) + d.last() + "0000" + d.substring(2, 5)
                '3' -> lead + d.substring(0, 3) + "00000" + d.substring(3, 5)
                '4' -> lead + d.substring(0, 4) + "00000" + d[4]
                else -> lead + d.substring(0, 5) + "0000" + d.last()
            }
        return normalized(body + check)
    }

    fun normalized(raw: String): String {
        val v = raw.trim()
        require(v.all { it.isDigit() } && v.length in listOf(8, 12, 13, 14)) {
            "Нужен EAN/UPC/GTIN штрихкод"
        }
        val sum =
            v.dropLast(1)
                .reversed()
                .mapIndexed { i, c -> c.digitToInt() * (if (i % 2 == 0) 3 else 1) }
                .sum()
        require((10 - sum % 10) % 10 == v.last().digitToInt()) { "Проверьте цифры штрихкода" }
        return if (v.length == 12) "0$v" else v
    }
}

object HealthMath {
    fun day(e: HealthEntry) =
        Instant.ofEpochMilli(if (e.kind == "SLEEP_SESSION") e.end else e.start)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()

    /** HC daily aggregate already deduplicates Android origins. Do not add Garmin to it. */
    fun daily(entries: List<HealthEntry>, kind: String, date: LocalDate): Long? {
        val rows = entries.filter { it.kind == kind && day(it) == date }
        if (rows.isEmpty()) return null
        val source =
            listOf("LOCAL", "HC", "GARMIN").firstOrNull { s -> rows.any { it.source == s } }
                ?: rows.first().source
        val selected = rows.filter { it.source == source }
        return when (kind) {
            "WEIGHT",
            "BODY_FAT",
            "RESTING_HR",
            "HRV",
            "SPO2" -> selected.maxBy { it.start }.value
            "HEART_RATE" -> selected.map { it.value }.average().toLong()
            else ->
                selected.distinctBy { it.externalId }.fold(0L) { a, b -> Math.addExact(a, b.value) }
        }
    }

    fun latest(entries: List<HealthEntry>, kind: String) =
        entries.filter { it.kind == kind }.maxByOrNull { it.start }

    fun workouts(entries: List<HealthEntry>): List<HealthEntry> {
        val result = mutableListOf<HealthEntry>()
        entries
            .filter { it.kind == "WORKOUT" }
            .sortedWith(
                compareBy<HealthEntry> { if (it.source == "HC") 0 else 1 }
                    .thenByDescending { it.start }
            )
            .forEach { v ->
                if (
                    result.none { a ->
                        kotlin.math.abs(a.start - v.start) < 120_000 &&
                            kotlin.math.abs(a.end - v.end) < 120_000
                    }
                )
                    result += v
            }
        return result.sortedByDescending { it.start }
    }

    data class Recovery(val score: Int, val explanation: String)

    fun recovery(entries: List<HealthEntry>, date: LocalDate): Recovery? {
        val baseline =
            (1L..14L).mapNotNull { daily(entries, "RESTING_HR", date.minusDays(it))?.toDouble() }
        if (baseline.size < 7) return null
        val sleep = daily(entries, "SLEEP", date)?.toDouble() ?: return null
        val resting = daily(entries, "RESTING_HR", date)?.toDouble() ?: return null
        val sleepPart = (sleep / (8 * 60 * 60 * 1000.0)).coerceIn(0.0, 1.0)
        val hrPart =
            (1 - (resting / baseline.average() - 1).coerceAtLeast(0.0) * 3).coerceIn(0.0, 1.0)
        return Recovery(
            (70 * sleepPart + 30 * hrPart).toInt(),
            "70% — длительность сна относительно 8 часов; 30% — пульс покоя относительно вашей истории (${baseline.size} дней). Индикатор самонаблюдения, не медицинская оценка.",
        )
    }

    fun correlation(pairs: List<Pair<Double, Double>>): Double? {
        if (pairs.size < 14) return null
        val x = pairs.map { it.first }.average()
        val y = pairs.map { it.second }.average()
        val xx = pairs.sumOf { (it.first - x) * (it.first - x) }
        val yy = pairs.sumOf { (it.second - y) * (it.second - y) }
        if (xx == 0.0 || yy == 0.0) return null
        return pairs.sumOf { (it.first - x) * (it.second - y) } / kotlin.math.sqrt(xx * yy)
    }
}
