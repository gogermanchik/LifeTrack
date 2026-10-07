package com.lifetrack.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// Energy is stored in milli-kcal; nutrients and gram portions in milligrams.
@Serializable
@Entity(tableName = "food_items")
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String = "",
    val servingSize: Long = 100000,
    val servingUnit: String = "г",
    val calories: Long,
    val protein: Long,
    val fat: Long,
    val carbs: Long,
    val fiber: Long = 0,
    val sugar: Long = 0,
    val sodium: Long? = null,
    val favorite: Boolean = false,
)

@Serializable
@Entity(
    tableName = "food_logs",
    foreignKeys =
        [
            ForeignKey(
                entity = FoodItem::class,
                parentColumns = ["id"],
                childColumns = ["foodItemId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
    indices = [Index("foodItemId"), Index("dateTime")],
)
data class FoodLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodItemId: Long? = null,
    val customName: String,
    val mealType: String,
    val amount: Long,
    val unit: String = "г",
    val calories: Long,
    val protein: Long,
    val fat: Long,
    val carbs: Long,
    val dateTime: Long = System.currentTimeMillis(),
    val source: String = "MANUAL",
    val composition: String = "",
    val recognitionDemo: Boolean = false,
    val demo: Boolean = false,
)

@Serializable
@Entity(tableName = "nutrition_goals")
data class NutritionGoal(
    @PrimaryKey val id: Int = 1,
    val dailyCalories: Long = 0,
    val proteinGrams: Long = 0,
    val fatGrams: Long = 0,
    val carbsGrams: Long = 0,
    val waterMl: Long = 0,
    val demo: Boolean = false,
)

@Serializable
@Entity(tableName = "water_logs", indices = [Index("dateTime")])
data class WaterLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountMl: Long,
    val dateTime: Long = System.currentTimeMillis(),
    val demo: Boolean = false,
)

@Serializable
data class NutritionSnapshot(
    val items: List<FoodItem> = emptyList(),
    val logs: List<FoodLog> = emptyList(),
    val water: List<WaterLog> = emptyList(),
    val goal: NutritionGoal? = null,
)

@Dao
interface NutritionDao {
    @Query("SELECT COUNT(*) FROM food_items") suspend fun itemCount(): Int

    @Query("SELECT * FROM food_items ORDER BY name") fun items(): Flow<List<FoodItem>>

    @Query("SELECT * FROM food_logs ORDER BY dateTime DESC, id DESC")
    fun logs(): Flow<List<FoodLog>>

    @Query("SELECT * FROM water_logs ORDER BY dateTime DESC, id DESC")
    fun water(): Flow<List<WaterLog>>

    @Query("SELECT * FROM nutrition_goals WHERE id=1") fun goal(): Flow<NutritionGoal?>

    @Upsert suspend fun item(v: FoodItem): Long

    @Upsert suspend fun log(v: FoodLog)

    @Upsert suspend fun water(v: WaterLog)

    @Upsert suspend fun goal(v: NutritionGoal)

    @Delete suspend fun delete(v: FoodLog)

    @Delete suspend fun delete(v: WaterLog)

    @Query("DELETE FROM food_logs") suspend fun clearLogs()

    @Query("DELETE FROM water_logs") suspend fun clearWater()

    @Query("DELETE FROM nutrition_goals") suspend fun clearGoals()

    @Query("DELETE FROM food_items") suspend fun clearItems()

    @Query("DELETE FROM food_logs WHERE demo=1") suspend fun removeDemoLogs()

    @Query("DELETE FROM water_logs WHERE demo=1") suspend fun removeDemoWater()

    @Query("DELETE FROM nutrition_goals WHERE demo=1") suspend fun removeDemoGoals()
}

val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS food_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, brand TEXT NOT NULL, servingSize INTEGER NOT NULL, servingUnit TEXT NOT NULL, calories INTEGER NOT NULL, protein INTEGER NOT NULL, fat INTEGER NOT NULL, carbs INTEGER NOT NULL, fiber INTEGER NOT NULL, sugar INTEGER NOT NULL, sodium INTEGER, favorite INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS food_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, foodItemId INTEGER, customName TEXT NOT NULL, mealType TEXT NOT NULL, amount INTEGER NOT NULL, unit TEXT NOT NULL, calories INTEGER NOT NULL, protein INTEGER NOT NULL, fat INTEGER NOT NULL, carbs INTEGER NOT NULL, dateTime INTEGER NOT NULL, source TEXT NOT NULL, composition TEXT NOT NULL, recognitionDemo INTEGER NOT NULL, demo INTEGER NOT NULL, FOREIGN KEY(foodItemId) REFERENCES food_items(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_food_logs_foodItemId ON food_logs(foodItemId)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_food_logs_dateTime ON food_logs(dateTime)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS nutrition_goals (id INTEGER NOT NULL, dailyCalories INTEGER NOT NULL, proteinGrams INTEGER NOT NULL, fatGrams INTEGER NOT NULL, carbsGrams INTEGER NOT NULL, waterMl INTEGER NOT NULL, demo INTEGER NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS water_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, amountMl INTEGER NOT NULL, dateTime INTEGER NOT NULL, demo INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_water_logs_dateTime ON water_logs(dateTime)"
            )
        }
    }
