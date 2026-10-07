package com.lifetrack.data

import androidx.room.withTransaction
import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.flow.*

interface FoodRepository {
    val snapshots: Flow<NutritionSnapshot>

    suspend fun save(log: FoodLog)

    suspend fun delete(log: FoodLog)

    suspend fun water(log: WaterLog)

    suspend fun goal(goal: NutritionGoal)
}

class LocalFoodRepository(private val db: LifeDatabase) : FoodRepository {
    private val dao = db.nutritionDao()
    override val snapshots =
        combine(dao.items(), dao.logs(), dao.water(), dao.goal()) { i, l, w, g ->
            NutritionSnapshot(i, l, w, g)
        }

    override suspend fun save(log: FoodLog) {
        validate(log)
        val s = snapshots.first()
        require(log.foodItemId == null || s.items.any { it.id == log.foodItemId })
        NutritionCalculator.totals(s.logs.filter { it.id != log.id } + log)
        dao.log(log)
    }

    override suspend fun delete(log: FoodLog) = dao.delete(log)

    override suspend fun water(log: WaterLog) {
        require(log.amountMl in 1..10000)
        require(log.dateTime <= System.currentTimeMillis())
        dao.water(log)
    }

    suspend fun deleteWater(log: WaterLog) = dao.delete(log)

    override suspend fun goal(goal: NutritionGoal) {
        require(
            goal.id == 1 &&
                listOf(
                        goal.dailyCalories,
                        goal.proteinGrams,
                        goal.fatGrams,
                        goal.carbsGrams,
                        goal.waterMl,
                    )
                    .all { it >= 0 }
        )
        require(
            goal.dailyCalories <= 100_000_000 &&
                listOf(goal.proteinGrams, goal.fatGrams, goal.carbsGrams).all { it <= 1_000_000 } &&
                goal.waterMl <= 10000
        )
        dao.goal(goal.copy(demo = false))
    }

    suspend fun saveAll(logs: List<FoodLog>) {
        val s = snapshots.first()
        logs.forEach {
            validate(it)
            require(it.foodItemId == null || s.items.any { i -> i.id == it.foodItemId })
        }
        NutritionCalculator.totals(s.logs + logs)
        db.withTransaction { logs.forEach { dao.log(it) } }
    }

    suspend fun favoriteFromLog(log: FoodLog) {
        val s = snapshots.first()
        val existing =
            s.items.firstOrNull { it.id == log.foodItemId }
                ?: s.items.firstOrNull { it.name == log.customName && it.servingSize == log.amount }
        if (existing != null) dao.item(existing.copy(favorite = true))
        else
            dao.item(
                FoodItem(
                    name = log.customName,
                    servingSize = log.amount,
                    servingUnit = log.unit,
                    calories = log.calories,
                    protein = log.protein,
                    fat = log.fat,
                    carbs = log.carbs,
                    favorite = true,
                )
            )
    }

    suspend fun favorite(item: FoodItem) = dao.item(item.copy(favorite = !item.favorite))

    suspend fun initialize() {
        if (snapshots.first().items.isNotEmpty()) return
        // Small offline catalogue. Values are averages per 100 g; labels/recipes may differ.
        db.withTransaction {
            if (dao.itemCount() > 0) return@withTransaction
            listOf(
                    FoodItem(
                        name = "Куриная грудка, приготовленная",
                        calories = 165000,
                        protein = 31000,
                        fat = 3600,
                        carbs = 0,
                    ),
                    FoodItem(
                        name = "Рис, отварной",
                        calories = 130000,
                        protein = 2700,
                        fat = 300,
                        carbs = 28200,
                    ),
                    FoodItem(
                        name = "Яйцо, варёное",
                        calories = 155000,
                        protein = 12600,
                        fat = 10600,
                        carbs = 1100,
                    ),
                    FoodItem(
                        name = "Овсяная каша на воде",
                        calories = 71000,
                        protein = 2500,
                        fat = 1500,
                        carbs = 12000,
                        fiber = 1700,
                    ),
                    FoodItem(
                        name = "Банан",
                        calories = 89000,
                        protein = 1100,
                        fat = 300,
                        carbs = 22800,
                        fiber = 2600,
                        sugar = 12200,
                    ),
                    FoodItem(
                        name = "Яблоко",
                        calories = 52000,
                        protein = 300,
                        fat = 200,
                        carbs = 13800,
                        fiber = 2400,
                        sugar = 10400,
                    ),
                    FoodItem(
                        name = "Йогурт натуральный",
                        calories = 61000,
                        protein = 3500,
                        fat = 3300,
                        carbs = 4700,
                    ),
                    FoodItem(
                        name = "Овощная смесь",
                        calories = 40000,
                        protein = 2000,
                        fat = 500,
                        carbs = 6000,
                        fiber = 2500,
                    ),
                    FoodItem(
                        name = "Хлеб цельнозерновой",
                        calories = 247000,
                        protein = 13000,
                        fat = 4200,
                        carbs = 41000,
                        fiber = 7000,
                    ),
                    FoodItem(
                        name = "Авокадо",
                        calories = 160000,
                        protein = 2000,
                        fat = 14700,
                        carbs = 8500,
                        fiber = 6700,
                    ),
                    FoodItem(
                        name = "Творог 5%",
                        calories = 121000,
                        protein = 17000,
                        fat = 5000,
                        carbs = 1800,
                    ),
                    FoodItem(
                        name = "Лосось, приготовленный",
                        calories = 206000,
                        protein = 22000,
                        fat = 12400,
                        carbs = 0,
                    ),
                )
                .forEach { dao.item(it) }
        }
    }

    suspend fun demo() {
        initialize()
        removeDemo()
        val s = snapshots.first()
        val today = LocalDate.now()
        db.withTransaction {
            for (n in 0..6) {
                val d = today.minusDays(n.toLong())
                val noon = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val safe = minOf(noon, System.currentTimeMillis())
                listOf(
                        FoodLog(
                            customName = "Омлет",
                            mealType = "BREAKFAST",
                            amount = 200000,
                            calories = 320000,
                            protein = 23000,
                            fat = 23000,
                            carbs = 5000,
                            dateTime = safe,
                            source = "MANUAL",
                            demo = true,
                        ),
                        FoodLog(
                            customName = "Тост с авокадо",
                            mealType = "BREAKFAST",
                            amount = 100000,
                            calories = 220000,
                            protein = 6000,
                            fat = 12000,
                            carbs = 24000,
                            dateTime = safe,
                            source = "MANUAL",
                            demo = true,
                        ),
                        FoodLog(
                            customName = "Курица с рисом",
                            mealType = "LUNCH",
                            amount = 420000,
                            calories = 680000,
                            protein = 61000,
                            fat = 16000,
                            carbs = 73000,
                            dateTime = safe,
                            source = "MANUAL",
                            demo = true,
                        ),
                        FoodLog(
                            customName = "Йогурт и фрукты",
                            mealType = "SNACK",
                            amount = 250000,
                            calories = 420000,
                            protein = 22000,
                            fat = 7000,
                            carbs = 72000,
                            dateTime = safe,
                            source = "MANUAL",
                            demo = true,
                        ),
                    )
                    .forEach { dao.log(it) }
                dao.water(WaterLog(amountMl = 1400, dateTime = safe, demo = true))
            }
            if (s.goal == null)
                dao.goal(
                    NutritionGoal(
                        dailyCalories = 2200000,
                        proteinGrams = 150000,
                        fatGrams = 75000,
                        carbsGrams = 250000,
                        waterMl = 2000,
                        demo = true,
                    )
                )
        }
    }

    suspend fun removeDemo() {
        db.withTransaction {
            dao.removeDemoLogs()
            dao.removeDemoWater()
            dao.removeDemoGoals()
        }
    }

    suspend fun clear() {
        db.withTransaction {
            dao.clearLogs()
            dao.clearWater()
            dao.clearGoals()
            dao.clearItems()
        }
    }

    suspend fun restore(s: NutritionSnapshot) {
        validateSnapshot(s)
        db.withTransaction {
            dao.clearLogs()
            dao.clearWater()
            dao.clearGoals()
            dao.clearItems()
            s.items.forEach { dao.item(it) }
            s.logs.forEach { dao.log(it) }
            s.water.forEach { dao.water(it) }
            s.goal?.let { dao.goal(it) }
        }
    }

    companion object {
        fun validate(l: FoodLog) {
            require(
                l.customName.isNotBlank() &&
                    l.customName.length <= 200 &&
                    l.mealType in MEALS &&
                    l.amount in 1..1_000_000_000 &&
                    l.unit in listOf("г", "мл", "порция") &&
                    l.source in listOf("MANUAL", "SEARCH", "PHOTO", "RECENT", "FAVORITE")
            )
            require(listOf(l.calories, l.protein, l.fat, l.carbs).all { it in 0..1_000_000_000 })
            require(l.dateTime <= System.currentTimeMillis())
            day(l.dateTime)
        }

        fun validateSnapshot(s: NutritionSnapshot) {
            require(
                s.items.map { it.id }.distinct().size == s.items.size &&
                    s.logs.map { it.id }.distinct().size == s.logs.size &&
                    s.water.map { it.id }.distinct().size == s.water.size
            )
            s.items.forEach {
                require(
                    it.id > 0 &&
                        it.servingSize > 0 &&
                        it.name.isNotBlank() &&
                        listOf(it.calories, it.protein, it.fat, it.carbs, it.fiber, it.sugar).all {
                            v ->
                            v in 0..1_000_000_000
                        }
                )
            }
            s.logs.forEach {
                validate(it)
                require(
                    it.id > 0 &&
                        (it.foodItemId == null || s.items.any { i -> i.id == it.foodItemId })
                )
            }
            s.water.forEach {
                require(
                    it.id > 0 &&
                        it.amountMl in 1..10000 &&
                        it.dateTime <= System.currentTimeMillis()
                )
                day(it.dateTime)
            }
            NutritionCalculator.totals(s.logs)
            s.goal?.let {
                require(
                    it.id == 1 &&
                        listOf(
                                it.dailyCalories,
                                it.proteinGrams,
                                it.fatGrams,
                                it.carbsGrams,
                                it.waterMl,
                            )
                            .all { v -> v >= 0 }
                )
            }
        }
    }
}
