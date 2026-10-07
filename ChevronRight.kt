package com.lifetrack

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifetrack.data.*
import com.lifetrack.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductRepositoryTest {
    @get:Rule
    val migration =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeDatabase::class.java)

    @Test
    fun migrationV2PreservesNutritionValuesAndCreatesValidatedV3() {
        val old = migration.createDatabase("product-migration", 2)
        old.execSQL(
            "INSERT INTO food_items VALUES(1,'Мой продукт','Бренд',100000,'г',200000,10000,5000,30000,0,0,NULL,1)"
        )
        old.execSQL(
            "INSERT INTO food_logs VALUES(1,1,'Обед','LUNCH',150000,'г',300000,15000,7500,45000,1000,'SEARCH','Состав',0,0)"
        )
        old.execSQL("INSERT INTO nutrition_goals VALUES(1,2200000,150000,75000,250000,2000,0)")
        old.execSQL("INSERT INTO water_logs VALUES(1,500,1000,0)")
        old.close()
        val v = migration.runMigrationsAndValidate("product-migration", 3, true, MIGRATION_2_3)
        v.query("SELECT calories,composition FROM food_logs").use {
            assertTrue(it.moveToFirst())
            assertEquals(300000L, it.getLong(0))
            assertEquals("Состав", it.getString(1))
        }
        v.query("SELECT favorite FROM food_items").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        v.close()
    }

    @Test
    fun migrationV1ThroughV3PreservesMoney() {
        val old = migration.createDatabase("product-v1", 1)
        old.execSQL("INSERT INTO accounts VALUES(1,'Личный',100000,'RUB','CARD',0)")
        old.close()
        val v =
            migration.runMigrationsAndValidate("product-v1", 3, true, MIGRATION_1_2, MIGRATION_2_3)
        v.query("SELECT opening FROM accounts").use {
            assertTrue(it.moveToFirst())
            assertEquals(100000L, it.getLong(0))
        }
        v.close()
    }

    @Test
    fun localOverrideWinsAndCachesStillWorkOffline() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val provider =
                object : FoodCatalogProvider {
                    override suspend fun getByBarcode(code: String) =
                        CatalogFood("off:$code", barcode = code, name = "Remote", calories = 500000)

                    override suspend fun search(query: String) =
                        listOf(getByBarcode("3017620422003"))
                }
            val catalog = LocalCatalogProvider(db, provider)
            val remote = catalog.getByBarcode("3017620422003")!!
            catalog.saveOverride(remote.copy(name = "Исправлено", calories = 450000))
            catalog.cache(remote.copy(calories = 900000))
            assertEquals(450000L, catalog.getByBarcode("3017620422003")!!.calories)
            assertEquals("Исправлено", catalog.search("Remote").single().name)
            val offline =
                LocalCatalogProvider(
                    db,
                    object : FoodCatalogProvider {
                        override suspend fun getByBarcode(code: String): CatalogFood? =
                            error("offline")

                        override suspend fun search(query: String): List<CatalogFood> =
                            error("offline")
                    },
                )
            assertEquals("Исправлено", offline.getByBarcode("3017620422003")!!.name)
        } finally {
            db.close()
        }
    }

    @Test
    fun productBackupRoundtripAndInvalidImportAreAtomic() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val repo = LocalLifeRepository(db)
            repo.initialize()
            val dao = db.productDao()
            dao.put(
                CatalogFood(
                    "mine",
                    name = "Мой продукт",
                    calories = 100000,
                    protein = 10000,
                    fat = 1000,
                    carbs = 12000,
                    override = true,
                )
            )
            val id = dao.put(Recipe(name = "Рецепт", servings = 2))
            dao.put(
                RecipeIngredient(
                    recipeId = id,
                    name = "Мой продукт",
                    amount = 100000,
                    unit = "г",
                    calories = 100000,
                    protein = 10000,
                    fat = 1000,
                    carbs = 12000,
                )
            )
            dao.put(
                HealthEntry(
                    "weight",
                    "LOCAL",
                    externalId = "weight",
                    kind = "WEIGHT",
                    start = 1000,
                    end = 1000,
                    value = 70000,
                    unit = "кг",
                )
            )
            dao.put(ProductPreference("profile_name", "Гоша"))
            val before = repo.snapshots.first()
            val text = repo.export(before, Settings(theme = "DARK"))
            repo.import(text)
            assertEquals(before, repo.snapshots.first())
            val bad =
                repo.export(
                    before.copy(
                        product =
                            before.product.copy(
                                ingredients =
                                    before.product.ingredients.map { it.copy(recipeId = 999) }
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
    fun deleteRecipeCascadesButDiarySnapshotRemains() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        try {
            val dao = db.productDao()
            val r = Recipe(dao.put(Recipe(name = "Суп")), "Суп")
            val i =
                RecipeIngredient(
                    recipeId = r.id,
                    name = "Овощи",
                    amount = 100000,
                    unit = "г",
                    calories = 50000,
                    protein = 1000,
                    fat = 1000,
                    carbs = 5000,
                )
            dao.put(i)
            val food = LocalFoodRepository(db)
            food.save(Portions.recipe(r, listOf(i), 1000, "LUNCH", 1000))
            dao.delete(r)
            assertTrue(dao.ingredients().first().isEmpty())
            assertEquals(50000L, food.snapshots.first().logs.single().calories)
        } finally {
            db.close()
        }
    }
}
