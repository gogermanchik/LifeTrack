package com.lifetrack.data

import androidx.room.*
import com.lifetrack.banking.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val opening: Long = 0,
    val currency: String = "RUB",
    val kind: String = "CARD",
    val demo: Boolean = false,
)

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val demo: Boolean = false,
)

@Serializable
@Entity(
    tableName = "transactions",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["toAccountId"],
                onDelete = ForeignKey.RESTRICT,
            ),
            ForeignKey(
                entity = CategoryEntity::class,
                parentColumns = ["id"],
                childColumns = ["categoryId"],
                onDelete = ForeignKey.RESTRICT,
            ),
        ],
    indices = [Index("accountId"), Index("toAccountId"), Index("categoryId"), Index("time")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val type: String,
    val categoryId: Long?,
    val accountId: Long,
    val toAccountId: Long? = null,
    val time: Long = System.currentTimeMillis(),
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val demo: Boolean = false,
)

@Serializable
@Entity(
    tableName = "budgets",
    foreignKeys =
        [
            ForeignKey(
                entity = CategoryEntity::class,
                parentColumns = ["id"],
                childColumns = ["categoryId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("categoryId")],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val month: String,
    val categoryId: Long? = null,
    val amount: Long,
    val currency: String = "RUB",
    val demo: Boolean = false,
)

@Serializable
@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val icon: String = "CHECK",
    val frequency: String = "DAILY",
    val days: String = "1,2,3,4,5,6,7",
    val weeklyTarget: Int = 3,
    val target: Int = 1,
    val reminder: String = "",
    val createdDay: Long,
    val color: String = "GREEN",
    val archived: Boolean = false,
    val demo: Boolean = false,
)

@Serializable
@Entity(
    tableName = "completions",
    primaryKeys = ["habitId", "day"],
    foreignKeys =
        [
            ForeignKey(
                entity = HabitEntity::class,
                parentColumns = ["id"],
                childColumns = ["habitId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("habitId"), Index("day")],
)
data class HabitCompletionEntity(val habitId: Long, val day: Long, val count: Int = 1)

@Serializable
@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val target: Long,
    val current: Long = 0,
    val currency: String = "RUB",
    val deadline: Long? = null,
    val demo: Boolean = false,
)

@Serializable
data class Snapshot(
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
    val budgets: List<BudgetEntity> = emptyList(),
    val habits: List<HabitEntity> = emptyList(),
    val completions: List<HabitCompletionEntity> = emptyList(),
    val goals: List<GoalEntity> = emptyList(),
    val nutrition: NutritionSnapshot = NutritionSnapshot(),
    val product: ProductSnapshot = ProductSnapshot(),
    val banking:BankSnapshot = BankSnapshot(),
)

@Dao
interface LifeDao {
    @Query("SELECT * FROM accounts") suspend fun accountsNow():List<AccountEntity>
    @Query("SELECT * FROM categories") suspend fun categoriesNow():List<CategoryEntity>
    @Query("SELECT * FROM transactions") suspend fun transactionsNow():List<TransactionEntity>
    @Query("SELECT * FROM accounts ORDER BY id") fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM categories ORDER BY id") fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM transactions ORDER BY time DESC")
    fun transactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM budgets ORDER BY month DESC") fun budgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM habits ORDER BY id") fun habits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM completions") fun completions(): Flow<List<HabitCompletionEntity>>

    @Query("SELECT * FROM goals ORDER BY deadline") fun goals(): Flow<List<GoalEntity>>

    @Upsert suspend fun account(v: AccountEntity): Long

    @Upsert suspend fun category(v: CategoryEntity): Long

    @Upsert suspend fun transaction(v: TransactionEntity):Long

    @Upsert suspend fun budget(v: BudgetEntity)

    @Upsert suspend fun habit(v: HabitEntity): Long

    @Upsert suspend fun completion(v: HabitCompletionEntity)

    @Upsert suspend fun goal(v: GoalEntity): Long

    @Delete suspend fun delete(v: TransactionEntity)

    @Delete suspend fun delete(v: HabitEntity)

    @Delete suspend fun delete(v: GoalEntity)

    @Delete suspend fun delete(v: BudgetEntity)

    @Query("DELETE FROM completions WHERE habitId=:id AND day=:day")
    suspend fun uncheck(id: Long, day: Long)

    @Query("DELETE FROM transactions WHERE demo=1") suspend fun demoTransactions()

    @Query("DELETE FROM budgets WHERE demo=1") suspend fun demoBudgets()

    @Query("DELETE FROM habits WHERE demo=1") suspend fun demoHabits()

    @Query("DELETE FROM goals WHERE demo=1") suspend fun demoGoals()

    @Query("DELETE FROM transactions") suspend fun clearTransactions()

    @Query("DELETE FROM budgets") suspend fun clearBudgets()

    @Query("DELETE FROM habits") suspend fun clearHabits()

    @Query("DELETE FROM goals") suspend fun clearGoals()

    @Query("DELETE FROM accounts") suspend fun clearAccounts()

    @Query("DELETE FROM categories") suspend fun clearCategories()
}

@Database(
    entities =
        [
            AccountEntity::class,
            CategoryEntity::class,
            TransactionEntity::class,
            BudgetEntity::class,
            HabitEntity::class,
            HabitCompletionEntity::class,
            GoalEntity::class,
            FoodItem::class,
            FoodLog::class,
            NutritionGoal::class,
            WaterLog::class,
            CatalogFood::class, Recipe::class, RecipeIngredient::class, HealthEntry::class, Subscription::class, HabitRule::class, GoalLink::class, ProductPreference::class,
            BankApp::class, BankCandidate::class, MerchantRule::class, TransactionSource::class, ImportProfile::class,
        ],
    version = 4,
    exportSchema = true,
)
abstract class LifeDatabase : RoomDatabase() {
    abstract fun dao(): LifeDao

    abstract fun nutritionDao(): NutritionDao
    abstract fun productDao(): ProductDao
    abstract fun bankDao(): BankDao
}
