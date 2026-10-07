package com.lifetrack.data

import android.content.Context
import com.lifetrack.banking.MIGRATION_3_4
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/** Nutrients are milli-units per 100 g/ml; null means unknown, never zero. */
@Serializable
@Entity(tableName = "catalog", indices = [Index(value = ["barcode"], unique = true), Index("name")])
data class CatalogFood(
    @PrimaryKey val key: String,
    val barcode: String? = null,
    val name: String,
    val brand: String = "",
    val baseUnit: String = "г",
    val calories: Long? = null,
    val protein: Long? = null,
    val fat: Long? = null,
    val carbs: Long? = null,
    val servingAmount: Long? = null,
    val servingLabel: String = "",
    val imageUrl: String = "",
    val quantity: String = "",
    val source: String = "LOCAL",
    val override: Boolean = false,
    val favorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val servings: Int = 1,
)

@Serializable
@Entity(
    tableName = "recipe_ingredients",
    foreignKeys =
        [
            ForeignKey(
                entity = Recipe::class,
                parentColumns = ["id"],
                childColumns = ["recipeId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("recipeId")],
)
data class RecipeIngredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val name: String,
    val amount: Long,
    val unit: String,
    val calories: Long,
    val protein: Long,
    val fat: Long,
    val carbs: Long,
)

@Serializable
@Entity(
    tableName = "health_records",
    indices =
        [
            Index(value = ["source", "kind", "externalId"], unique = true),
            Index(value = ["kind", "start"]),
            Index("end"),
        ],
)
data class HealthEntry(
    @PrimaryKey val id: String,
    val source: String,
    val origin: String = "",
    val device: String = "",
    val externalId: String,
    val kind: String,
    val start: Long,
    val end: Long,
    val value: Long,
    val unit: String,
    val detail: String = "",
    val syncedAt: Long = System.currentTimeMillis(),
)

@Serializable
@Entity(
    tableName = "subscriptions",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = CategoryEntity::class,
                parentColumns = ["id"],
                childColumns = ["categoryId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("accountId"), Index("categoryId")],
)
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Long,
    val accountId: Long,
    val categoryId: Long,
    val nextDay: Long,
    val intervalMonths: Int = 1,
    val active: Boolean = true,
)

@Serializable
@Entity(
    tableName = "habit_rules",
    foreignKeys =
        [
            ForeignKey(
                entity = HabitEntity::class,
                parentColumns = ["id"],
                childColumns = ["habitId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class HabitRule(
    @PrimaryKey val habitId: Long,
    val type: String = "BOOLEAN",
    val unit: String = "раз",
    val metric: String = "",
    val auto: Boolean = false,
)

@Serializable
@Entity(
    tableName = "goal_links",
    foreignKeys =
        [
            ForeignKey(
                entity = GoalEntity::class,
                parentColumns = ["id"],
                childColumns = ["goalId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class GoalLink(
    @PrimaryKey val goalId: Long,
    val kind: String,
    val unit: String,
    val referenceId: Long? = null,
    val startDay: Long,
    val initial: Long = 0,
)

@Serializable
@Entity(tableName = "product_preferences")
data class ProductPreference(@PrimaryKey val key: String, val value: String)

@Serializable
data class ProductSnapshot(
    val catalog: List<CatalogFood> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val ingredients: List<RecipeIngredient> = emptyList(),
    val health: List<HealthEntry> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val habitRules: List<HabitRule> = emptyList(),
    val goalLinks: List<GoalLink> = emptyList(),
    val preferences: List<ProductPreference> = emptyList(),
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM catalog ORDER BY name") fun catalog(): Flow<List<CatalogFood>>

    @Query("SELECT * FROM recipes ORDER BY name") fun recipes(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipe_ingredients ORDER BY id")
    fun ingredients(): Flow<List<RecipeIngredient>>

    @Query("SELECT * FROM health_records ORDER BY start DESC") fun health(): Flow<List<HealthEntry>>

    @Query("SELECT * FROM subscriptions ORDER BY nextDay")
    fun subscriptions(): Flow<List<Subscription>>

    @Query("SELECT * FROM habit_rules") fun habitRules(): Flow<List<HabitRule>>

    @Query("SELECT * FROM goal_links") fun goalLinks(): Flow<List<GoalLink>>

    @Query("SELECT * FROM product_preferences") fun preferences(): Flow<List<ProductPreference>>

    @Upsert suspend fun put(v: CatalogFood)

    @Upsert suspend fun put(v: Recipe): Long

    @Upsert suspend fun put(v: RecipeIngredient)

    @Upsert suspend fun put(v: HealthEntry)

    @Upsert suspend fun put(v: Subscription)

    @Upsert suspend fun put(v: HabitRule)

    @Upsert suspend fun put(v: GoalLink)

    @Upsert suspend fun put(v: ProductPreference)

    @Delete suspend fun delete(v: Recipe)

    @Delete suspend fun delete(v: HealthEntry)

    @Delete suspend fun delete(v: Subscription)

    @Query("DELETE FROM recipe_ingredients WHERE recipeId=:id")
    suspend fun clearIngredients(id: Long)

    @Query(
        "DELETE FROM health_records WHERE source=:source AND kind=:kind AND start>=:from AND start<:to"
    )
    suspend fun clearRange(source: String, kind: String, from: Long, to: Long)

    @Query("DELETE FROM health_records WHERE source=:source") suspend fun disconnect(source: String)

    @Query("DELETE FROM catalog") suspend fun clearCatalog()

    @Query("DELETE FROM recipes") suspend fun clearRecipes()

    @Query("DELETE FROM health_records") suspend fun clearHealth()

    @Query("DELETE FROM subscriptions") suspend fun clearSubscriptions()

    @Query("DELETE FROM habit_rules") suspend fun clearRules()

    @Query("DELETE FROM goal_links") suspend fun clearLinks()

    @Query("DELETE FROM product_preferences") suspend fun clearPreferences()
}

val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS catalog (`key` TEXT NOT NULL PRIMARY KEY,barcode TEXT,name TEXT NOT NULL,brand TEXT NOT NULL,baseUnit TEXT NOT NULL,calories INTEGER,protein INTEGER,fat INTEGER,carbs INTEGER,servingAmount INTEGER,servingLabel TEXT NOT NULL,imageUrl TEXT NOT NULL,quantity TEXT NOT NULL,source TEXT NOT NULL,`override` INTEGER NOT NULL,favorite INTEGER NOT NULL,updatedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_catalog_barcode ON catalog(barcode)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_name ON catalog(name)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS recipes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,servings INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS recipe_ingredients (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,recipeId INTEGER NOT NULL,name TEXT NOT NULL,amount INTEGER NOT NULL,unit TEXT NOT NULL,calories INTEGER NOT NULL,protein INTEGER NOT NULL,fat INTEGER NOT NULL,carbs INTEGER NOT NULL,FOREIGN KEY(recipeId) REFERENCES recipes(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_recipe_ingredients_recipeId ON recipe_ingredients(recipeId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS health_records (id TEXT NOT NULL PRIMARY KEY,source TEXT NOT NULL,origin TEXT NOT NULL,device TEXT NOT NULL,externalId TEXT NOT NULL,kind TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,value INTEGER NOT NULL,unit TEXT NOT NULL,detail TEXT NOT NULL,syncedAt INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_health_records_source_kind_externalId ON health_records(source,kind,externalId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_health_records_kind_start ON health_records(kind,start)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_health_records_end ON health_records(end)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS subscriptions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,amount INTEGER NOT NULL,accountId INTEGER NOT NULL,categoryId INTEGER NOT NULL,nextDay INTEGER NOT NULL,intervalMonths INTEGER NOT NULL,active INTEGER NOT NULL,FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT,FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_subscriptions_accountId ON subscriptions(accountId)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_subscriptions_categoryId ON subscriptions(categoryId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS habit_rules (habitId INTEGER NOT NULL PRIMARY KEY,type TEXT NOT NULL,unit TEXT NOT NULL,metric TEXT NOT NULL,auto INTEGER NOT NULL,FOREIGN KEY(habitId) REFERENCES habits(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS goal_links (goalId INTEGER NOT NULL PRIMARY KEY,kind TEXT NOT NULL,unit TEXT NOT NULL,referenceId INTEGER,startDay INTEGER NOT NULL,initial INTEGER NOT NULL,FOREIGN KEY(goalId) REFERENCES goals(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS product_preferences (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)"
            )
        }
    }

object LifeStore {
    @Volatile private var instance: LifeDatabase? = null

    fun get(context: Context): LifeDatabase =
        instance
            ?: synchronized(this) {
                instance
                    ?: Room.databaseBuilder(
                            context.applicationContext,
                            LifeDatabase::class.java,
                            "lifetrack.db",
                        )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                        .enableMultiInstanceInvalidation()
                        .build()
                        .also { instance = it }
            }
}

class ProductRepository(val db: LifeDatabase) {
    val dao = db.productDao()
    val snapshots =
        combine(
            combine(dao.catalog(), dao.recipes(), dao.ingredients(), dao.health()) { a, b, c, d ->
                ProductSnapshot(a, b, c, d)
            },
            combine(dao.subscriptions(), dao.habitRules(), dao.goalLinks(), dao.preferences()) {
                a,
                b,
                c,
                d ->
                ProductSnapshot(subscriptions = a, habitRules = b, goalLinks = c, preferences = d)
            },
        ) { a, b ->
            a.copy(
                subscriptions = b.subscriptions,
                habitRules = b.habitRules,
                goalLinks = b.goalLinks,
                preferences = b.preferences,
            )
        }

    suspend fun clear() {
        dao.clearSubscriptions()
        dao.clearRules()
        dao.clearLinks()
        dao.clearRecipes()
        dao.clearCatalog()
        dao.clearHealth()
        dao.clearPreferences()
    }

    suspend fun restore(s: ProductSnapshot) {
        s.catalog.forEach { dao.put(it) }
        s.recipes.forEach { dao.put(it) }
        s.ingredients.forEach { dao.put(it) }
        s.health.forEach { dao.put(it) }
        s.subscriptions.forEach { dao.put(it) }
        s.habitRules.forEach { dao.put(it) }
        s.goalLinks.forEach { dao.put(it) }
        s.preferences.forEach { dao.put(it) }
    }

    companion object {
        fun validate(s: Snapshot) {
            val p = s.product
            require(
                p.catalog.map { it.key }.distinct().size == p.catalog.size &&
                    p.catalog.mapNotNull { it.barcode }.distinct().size ==
                        p.catalog.count { it.barcode != null }
            )
            p.catalog.forEach {
                require(
                    it.name.isNotBlank() &&
                        it.baseUnit in listOf("г", "мл") &&
                        listOf(it.calories, it.protein, it.fat, it.carbs).all { v ->
                            v == null || v in 0..1_000_000_000L
                        }
                )
                require(it.servingAmount == null || it.servingAmount > 0)
            }
            require(p.recipes.map { it.id }.distinct().size == p.recipes.size)
            require(p.recipes.all { it.id > 0 && it.name.isNotBlank() && it.servings in 1..1000 })
            require(
                p.ingredients.map { it.id }.distinct().size == p.ingredients.size &&
                    p.subscriptions.map { it.id }.distinct().size == p.subscriptions.size &&
                    p.habitRules.map { it.habitId }.distinct().size == p.habitRules.size &&
                    p.goalLinks.map { it.goalId }.distinct().size == p.goalLinks.size &&
                    p.preferences.map { it.key }.distinct().size == p.preferences.size
            )
            require(
                p.ingredients.all { i ->
                    i.id > 0 &&
                        p.recipes.any { it.id == i.recipeId } &&
                        i.amount > 0 &&
                        listOf(i.calories, i.protein, i.fat, i.carbs).all { it >= 0 }
                }
            )
            require(
                p.health.map { it.id }.distinct().size == p.health.size &&
                    p.health.map { Triple(it.source, it.kind, it.externalId) }.distinct().size ==
                        p.health.size
            )
            require(
                p.health.all {
                    it.id.isNotBlank() &&
                        it.start <= it.end &&
                        it.value >= 0 &&
                        it.start <= System.currentTimeMillis()
                }
            )
            require(
                p.subscriptions.all {
                    it.id > 0 &&
                        it.name.isNotBlank() &&
                        it.amount > 0 &&
                        it.intervalMonths in 1..12 &&
                        s.accounts.any { a -> a.id == it.accountId } &&
                        s.categories.any { c -> c.id == it.categoryId && c.type == "EXPENSE" }
                }
            )
            require(
                p.habitRules.all { r ->
                    r.type in listOf("BOOLEAN", "COUNT", "DURATION", "QUANTITY") &&
                        r.metric in listOf("", "STEPS", "SLEEP", "HYDRATION") &&
                        s.habits.any { it.id == r.habitId }
                } &&
                    p.goalLinks.all { r ->
                        r.kind in listOf("WORKOUT", "HABIT", "WEIGHT", "SAVINGS", "CUSTOM") &&
                            s.goals.any { it.id == r.goalId } &&
                            (r.kind != "HABIT" || s.habits.any { it.id == r.referenceId }) &&
                            (r.kind != "SAVINGS" || s.accounts.any { it.id == r.referenceId })
                    }
            )
            require(p.preferences.all { it.key.length <= 100 && it.value.length <= 10000 })
        }
    }
}
