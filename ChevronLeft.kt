package com.lifetrack

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifetrack.data.*
import com.lifetrack.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionRepositoryTest {
    @get:Rule
    val migration =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeDatabase::class.java)

    @Test
    fun migrationPreservesAllExistingTables() {
        val old = migration.createDatabase("nutrition-migration", 1)
        old.execSQL("INSERT INTO accounts VALUES(1,'Мой счёт',123456,'RUB','CARD',0)")
        old.execSQL("INSERT INTO categories VALUES(1,'Еда','EXPENSE',0)")
        old.execSQL(
            "INSERT INTO transactions VALUES(1,12345,'EXPENSE',1,1,NULL,1000,'Покупка',1000,0)"
        )
        old.execSQL("INSERT INTO budgets VALUES(1,'2025-01',1,500000,'RUB',0)")
        old.execSQL(
            "INSERT INTO habits VALUES(1,'Ходьба','','CHECK','DAILY','1,2,3,4,5,6,7',3,1,'',20000,'GREEN',0,0)"
        )
        old.execSQL("INSERT INTO completions VALUES(1,20000,1)")
        old.execSQL("INSERT INTO goals VALUES(1,'Отпуск','MONEY',100000,12345,'RUB',NULL,0)")
        old.close()
        val upgraded =
            migration.runMigrationsAndValidate("nutrition-migration", 2, true, MIGRATION_1_2)
        listOf(
                "accounts",
                "categories",
                "transactions",
                "budgets",
                "habits",
                "completions",
                "goals",
            )
            .forEach { table ->
                upgraded.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(table, 1, it.getInt(0))
                }
            }
        upgraded.query("SELECT amount,description FROM transactions").use {
            it.moveToFirst()
            assertEquals(12345L, it.getLong(0))
            assertEquals("Покупка", it.getString(1))
        }
        listOf("food_items", "food_logs", "nutrition_goals", "water_logs").forEach { table ->
            upgraded.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
        upgraded.close()
    }

    @Test
    fun nutritionCrudSnapshotFavoritesAndWater() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).build()
        try {
            val repo = LocalFoodRepository(db)
            repo.initialize()
            val item = repo.snapshots.first().items.first { it.name.startsWith("Куриная") }
            val log = FoodLogCalculator.fromItem(item, 200000, "LUNCH", 1000)
            repo.save(log)
            val saved = repo.snapshots.first().logs.single()
            assertEquals(330000L, saved.calories)
            db.nutritionDao().item(item.copy(calories = 999000))
            assertEquals(330000L, repo.snapshots.first().logs.single().calories)
            repo.favoriteFromLog(saved)
            assertTrue(repo.snapshots.first().items.first { it.id == item.id }.favorite)
            repo.save(saved.copy(amount = 300000, calories = 495000))
            assertEquals(495000L, repo.snapshots.first().logs.single().calories)
            repo.water(WaterLog(amountMl = 250, dateTime = 1000))
            repo.water(WaterLog(amountMl = 500, dateTime = 1000))
            assertEquals(750L, repo.snapshots.first().water.sumOf { it.amountMl })
            repo.goal(NutritionGoal(dailyCalories = 2200000, waterMl = 2000))
            assertEquals(2200000L, repo.snapshots.first().goal!!.dailyCalories)
            repo.delete(saved.copy(amount = 300000, calories = 495000))
            assertTrue(repo.snapshots.first().logs.isEmpty())
            assertTrue(runCatching { repo.save(log.copy(amount = 0)) }.isFailure)
            assertTrue(runCatching { repo.water(WaterLog(amountMl = 0)) }.isFailure)
        } finally {
            db.close()
        }
    }

    @Test
    fun photoResultsNeedExplicitSaveAndBatchIsAtomic() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val repo = LocalFoodRepository(db)
            val result =
                DemoFoodRecognitionService().recognizeFood(RecognitionImage(byteArrayOf(1)))
                    as RecognitionOutcome.LowConfidence
            assertTrue(repo.snapshots.first().logs.isEmpty())
            val logs = result.result.items.map { it.food.copy(dateTime = 1000) }
            assertTrue(runCatching { repo.saveAll(logs + logs.first().copy(amount = 0)) }.isFailure)
            assertTrue(repo.snapshots.first().logs.isEmpty())
            repo.saveAll(logs)
            assertEquals(4, repo.snapshots.first().logs.size)
            assertEquals(620000L, NutritionCalculator.totals(repo.snapshots.first().logs).calories)
        } finally {
            db.close()
        }
    }

    @Test
    fun importsOldBackupAndRejectsBadNutritionWithoutClearing() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val repo = LocalLifeRepository(db)
            // Version 1 backups did not encode their default version and had no nutrition field.
            repo.import(
                """{"data":{"accounts":[{"id":1,"name":"Старый счёт","opening":123456}]},"settings":{"theme":"DARK"}}"""
            )
            assertEquals(123456L, repo.snapshots.first().accounts.single().opening)
            val food = LocalFoodRepository(db)
            food.save(
                FoodLog(
                    customName = "Ужин",
                    mealType = "DINNER",
                    amount = 100000,
                    calories = 250000,
                    protein = 10000,
                    fat = 8000,
                    carbs = 30000,
                    dateTime = 1000,
                )
            )
            val before = repo.snapshots.first()
            val bad =
                repo.export(
                    before.copy(
                        nutrition =
                            before.nutrition.copy(
                                logs = before.nutrition.logs.map { it.copy(calories = -1) }
                            )
                    ),
                    Settings(),
                )
            assertTrue(runCatching { repo.import(bad) }.isFailure)
            assertEquals(before, repo.snapshots.first())
        } finally {
            db.close()
        }
    }

    @Test
    fun photoProcessorRotatesExifAndBoundsBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "orientation-test.jpg")
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                2400,
                1200,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        file.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)
        }
        bitmap.recycle()
        androidx.exifinterface.media.ExifInterface(file).apply {
            setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }
        val output = FoodPhotoProcessor.prepare(context, android.net.Uri.fromFile(file))
        val decoded = android.graphics.BitmapFactory.decodeFile(output.absolutePath)
        try {
            assertTrue(decoded.height > decoded.width)
            assertTrue(maxOf(decoded.width, decoded.height) <= 1024)
            assertTrue(output.length() > 0)
        } finally {
            decoded.recycle()
            file.delete()
            output.delete()
        }
    }

    @Test
    fun concurrentCatalogInitializationDoesNotDuplicateProducts() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val a = LocalFoodRepository(db)
            val b = LocalFoodRepository(db)
            kotlinx.coroutines.coroutineScope {
                val first = async(kotlinx.coroutines.Dispatchers.IO) { a.initialize() }
                val second = async(kotlinx.coroutines.Dispatchers.IO) { b.initialize() }
                first.await()
                second.await()
            }
            assertEquals(12, a.snapshots.first().items.size)
        } finally {
            db.close()
        }
    }
}
