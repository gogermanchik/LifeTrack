package com.lifetrack.domain

import java.math.BigDecimal

data class LabelNutrition(
    val calories: Long? = null,
    val protein: Long? = null,
    val fat: Long? = null,
    val carbs: Long? = null,
    val basis: Int? = null,
    val unit: String? = null,
)

object LabelNutritionMapper {
    fun parse(text: String): LabelNutrition {
        val lines = text.lineSequence().map { it.trim() }.toList()
        fun nutrient(pattern: String): Long? {
            val line =
                lines.firstOrNull { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    ?: return null
            val number =
                Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:g|г|kcal|ккал)\\b", RegexOption.IGNORE_CASE)
                    .findAll(line)
                    .toList()
                    .singleOrNull()
                    ?.groupValues
                    ?.get(1) ?: return null
            return runCatching {
                    BigDecimal(number.replace(',', '.')).multiply(BigDecimal(1000)).longValueExact()
                }
                .getOrNull()
        }
        val energy =
            lines.firstOrNull {
                Regex("\\b(kcal|ккал)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
        val kcal =
            energy
                ?.let {
                    Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:kcal|ккал)\\b", RegexOption.IGNORE_CASE)
                        .findAll(it)
                        .toList()
                        .singleOrNull()
                        ?.groupValues
                        ?.get(1)
                }
                ?.let {
                    runCatching {
                            BigDecimal(it.replace(',', '.'))
                                .multiply(BigDecimal(1000))
                                .longValueExact()
                        }
                        .getOrNull()
                }
        // Never assume that serving-based values are per 100 g.
        val heading = Regex("(?iu)(?:per|на)\\s*100\\s*(g|г|ml|мл)\\b").find(text)
        val basis = heading?.let { 100 }
        val unit =
            heading?.groupValues?.get(1)?.lowercase()?.let {
                if (it in listOf("ml", "мл")) "мл" else "г"
            }
        return LabelNutrition(
            kcal,
            nutrient("^protein|^белк"),
            nutrient("^total fat|^fat\\b|^жир"),
            nutrient("^carbohydrate|^total carbohydrate|^углев"),
            basis,
            unit,
        )
    }
}
