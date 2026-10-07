package com.lifetrack.data

import android.content.Context
import com.lifetrack.banking.*
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsStore by preferencesDataStore("settings")

@Serializable
data class Settings(
    val currency: String = "RUB",
    val theme: String = "SYSTEM",
    val monday: Boolean = true,
    val notifications: Boolean = false,
    val onboarded: Boolean = false,
)

@Serializable data class Backup(val version: Int = 1, val data: Snapshot, val settings: Settings)

class SettingsRepository(private val context: Context) {
    val flow =
        context.settingsStore.data.map { p ->
            Settings(
                p[stringPreferencesKey("currency")] ?: "RUB",
                p[stringPreferencesKey("theme")] ?: "SYSTEM",
                p[booleanPreferencesKey("monday")] ?: true,
                p[booleanPreferencesKey("notifications")] ?: false,
                p[booleanPreferencesKey("onboarded")] ?: false,
            )
        }

    suspend fun save(s: Settings) {
        context.settingsStore.edit { p ->
            p[stringPreferencesKey("currency")] = s.currency
            p[stringPreferencesKey("theme")] = s.theme
            p[booleanPreferencesKey("monday")] = s.monday
            p[booleanPreferencesKey("notifications")] = s.notifications
            p[booleanPreferencesKey("onboarded")] = s.onboarded
        }
    }
}

interface LifeRepository {
    val snapshots: Flow<Snapshot>

    suspend fun transaction(v: TransactionEntity)

    suspend fun habit(v: HabitEntity)

    suspend fun toggle(h: HabitEntity, date: LocalDate)

    suspend fun goal(v: GoalEntity)

    suspend fun budget(v: BudgetEntity)
}

class LocalLifeRepository(private val db: LifeDatabase) : LifeRepository {
    private val dao = db.dao()
    private val food = LocalFoodRepository(db)
    private val product = ProductRepository(db)
    private val bank=BankRepository(db)
    private val backupJson = Json { prettyPrint = true }
    private val lifeSnapshots: Flow<Snapshot> =
        combine(
            combine(dao.accounts(), dao.categories(), dao.transactions(), dao.budgets()) {
                a,
                c,
                t,
                b ->
                Snapshot(accounts = a, categories = c, transactions = t, budgets = b)
            },
            dao.habits(),
            dao.completions(),
            dao.goals(),
        ) { s, h, c, g ->
            s.copy(habits = h, completions = c, goals = g)
        }

    override val snapshots: Flow<Snapshot> =
        combine(lifeSnapshots, food.snapshots, product.snapshots,bank.snapshots) { s, n, p,b -> s.copy(nutrition = n, product = p,banking=b) }

    override suspend fun transaction(v: TransactionEntity) {
        require(v.amount > 0 && v.type in listOf("INCOME", "EXPENSE", "TRANSFER","REFUND"))
        val s = snapshots.first()
        val a = s.accounts.first { it.id == v.accountId }
        if (v.type == "TRANSFER") {
            require(v.toAccountId != v.accountId)
            require(s.accounts.first { it.id == v.toAccountId }.currency == a.currency) {
                "Перевод доступен только в одной валюте"
            }
            require(v.categoryId == null)
        } else
            require(s.categories.any { it.id == v.categoryId && it.type == (if(v.type=="REFUND")"EXPENSE" else v.type) }) {
                "Выберите категорию"
            }
        val candidate = s.copy(transactions = s.transactions.filter { it.id != v.id } + v)
        candidate.accounts.forEach { FinanceCalculator.balance(candidate, it) }
        for (currency in candidate.accounts.map { it.currency }.distinct()) FinanceCalculator.stats(
            candidate,
            LocalDate.MIN,
            LocalDate.MAX,
            currency,
        )
        dao.transaction(v)
    }

    override suspend fun habit(v: HabitEntity) {
        require(v.name.isNotBlank() && v.target in 1..10000 && v.weeklyTarget in 1..7)
        require(v.frequency != "DAYS" || v.days.split(",").any { it.toIntOrNull() in 1..7 })
        dao.habit(v)
    }

    override suspend fun toggle(h: HabitEntity, date: LocalDate) {
        val c =
            snapshots.first().completions.firstOrNull {
                it.habitId == h.id && it.day == date.toEpochDay()
            }
        if (c != null) dao.uncheck(h.id, date.toEpochDay())
        else dao.completion(HabitCompletionEntity(h.id, date.toEpochDay(), h.target))
    }

    override suspend fun goal(v: GoalEntity) {
        require(v.name.isNotBlank() && v.target > 0 && v.current >= 0)
        dao.goal(v)
    }

    override suspend fun budget(v: BudgetEntity) {
        require(v.amount > 0)
        YearMonth.parse(v.month)
        val old =
            snapshots.first().budgets.firstOrNull {
                it.month == v.month && it.categoryId == v.categoryId && it.currency == v.currency
            }
        dao.budget(if (v.id == 0L && old != null) v.copy(id = old.id) else v)
    }

    suspend fun account(v: AccountEntity) {
        require(v.name.isNotBlank() && v.currency in listOf("RUB", "USD", "EUR", "SEK", "GBP", "CHF", "KZT", "BYN", "UAH", "TRY", "AED"))
        dao.account(v)
    }

    suspend fun category(v: CategoryEntity) {
        require(v.name.isNotBlank() && v.type in listOf("INCOME", "EXPENSE"))
        dao.category(v)
    }

    suspend fun delete(v: Any): Any {
        return when (v) {
            is TransactionEntity -> {
                dao.delete(v)
                v
            }
            is HabitEntity -> {
                val trash =
                    HabitTrash(v, snapshots.first().completions.filter { it.habitId == v.id })
                dao.delete(v)
                trash
            }
            is GoalEntity -> {
                dao.delete(v)
                v
            }
            is BudgetEntity -> {
                dao.delete(v)
                v
            }
            else -> error("Неизвестная запись")
        }
    }

    suspend fun restore(v: Any) {
        when (v) {
            is TransactionEntity -> transaction(v)
            is HabitTrash ->
                db.withTransaction {
                    dao.habit(v.habit)
                    v.completions.forEach { dao.completion(it) }
                }
            is HabitEntity -> habit(v)
            is GoalEntity -> goal(v)
            is BudgetEntity -> budget(v)
        }
    }

    suspend fun initialize() {
        if (snapshots.first().accounts.isEmpty())
            db.withTransaction {
                dao.account(AccountEntity(name = "Основная карта"))
                dao.account(AccountEntity(name = "Наличные", kind = "CASH"))
                dao.account(AccountEntity(name = "Накопления", kind = "SAVINGS"))
                for (n in
                    listOf(
                        "Еда",
                        "Транспорт",
                        "Развлечения",
                        "Покупки",
                        "Здоровье",
                        "Жильё",
                        "Связь",
                        "Образование",
                        "Подписки",
                        "Другое",
                    )) dao.category(CategoryEntity(name = n, type = "EXPENSE"))
                for (n in
                    listOf("Зарплата", "Фриланс", "Бизнес", "Инвестиции", "Подарки", "Другое")) dao
                    .category(CategoryEntity(name = n, type = "INCOME"))
            }
    }

    suspend fun clear() {
        db.withTransaction {
            bank.clear()
            product.clear()
            food.clear()
            dao.clearTransactions()
            dao.clearBudgets()
            dao.clearHabits()
            dao.clearGoals()
            dao.clearAccounts()
            dao.clearCategories()
        }
        initialize()
    }

    suspend fun removeDemo() {
        db.withTransaction {
            dao.demoTransactions()
            dao.demoBudgets()
            dao.demoHabits()
            dao.demoGoals()
            food.removeDemo()
        }
    }

    suspend fun demo() {
        initialize()
        removeDemo()
        val s = snapshots.first()
        val a = s.accounts.firstOrNull { it.currency == "RUB" } ?: return
        val now = LocalDate.now()
        val m = YearMonth.from(now)
        db.withTransaction {
            dao.transaction(
                TransactionEntity(
                    amount = 20000000,
                    type = "INCOME",
                    categoryId = s.categories.first { it.type == "INCOME" }.id,
                    accountId = a.id,
                    time =
                        m.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    description = "Зарплата",
                    demo = true,
                )
            )
            for ((n, amount) in
                listOf(
                    "Еда" to 2400000L,
                    "Транспорт" to 900000L,
                    "Развлечения" to 800000L,
                    "Подписки" to 300000L,
                    "Жильё" to 4500000L,
                    "Другое" to 1100000L,
                )) dao.transaction(
                TransactionEntity(
                    amount = amount,
                    type = "EXPENSE",
                    categoryId = s.categories.first { it.name == n && it.type == "EXPENSE" }.id,
                    accountId = a.id,
                    time = now.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    description = n,
                    demo = true,
                )
            )
            dao.budget(BudgetEntity(month = m.toString(), amount = 13000000, demo = true))
            for ((i, n) in listOf("Спорт", "Чтение", "Вода", "Английский").withIndex()) {
                val id =
                    dao.habit(
                        HabitEntity(
                            name = n,
                            createdDay = now.minusDays(40).toEpochDay(),
                            demo = true,
                        )
                    )
                for (d in 0..39) if ((d + i) % 5 != 0)
                    dao.completion(
                        HabitCompletionEntity(id, now.minusDays(d.toLong()).toEpochDay())
                    )
            }
            dao.goal(
                GoalEntity(
                    name = "Купить машину",
                    type = "FINANCIAL",
                    target = 150000000,
                    current = 32000000,
                    deadline = now.plusDays(300).toEpochDay(),
                    demo = true,
                )
            )
            dao.goal(
                GoalEntity(
                    name = "Английский B2",
                    type = "LIFE",
                    target = 180,
                    current = 60,
                    deadline = now.plusDays(120).toEpochDay(),
                    demo = true,
                )
            )
        }
        food.demo()
    }

    fun export(s: Snapshot, settings: Settings): String =
        backupJson.encodeToString(
            Backup.serializer(),
            Backup(version = 4, data = s, settings = settings),
        )

    suspend fun import(text: String): Settings {
        require(text.length <= 20_000_000) { "Резервная копия слишком велика" }
        val b = Json.decodeFromString(Backup.serializer(), text)
        require(b.version in 1..4)
        val s = b.data
        LocalFoodRepository.validateSnapshot(s.nutrition)
        ProductRepository.validate(s)
        BankRepository.validate(s)
        require(
            s.categories.map { it.id }.distinct().size == s.categories.size &&
                s.habits.map { it.id }.distinct().size == s.habits.size &&
                s.goals.map { it.id }.distinct().size == s.goals.size &&
                s.transactions.map { it.id }.distinct().size == s.transactions.size &&
                s.budgets.map { it.id }.distinct().size == s.budgets.size
        )
        require(
            s.transactions.all { it.id > 0 && it.time <= System.currentTimeMillis() } &&
                s.goals.all {
                    it.id > 0 &&
                        it.type in listOf("FINANCIAL", "LIFE") &&
                        it.currency in listOf("RUB", "USD", "EUR", "SEK", "GBP", "CHF", "KZT", "BYN", "UAH", "TRY", "AED")
                } &&
                s.budgets.all {
                    it.id > 0 &&
                        it.currency in listOf("RUB", "USD", "EUR", "SEK", "GBP", "CHF", "KZT", "BYN", "UAH", "TRY", "AED") &&
                        (it.categoryId == null ||
                            s.categories.any { c -> c.id == it.categoryId && c.type == "EXPENSE" })
                }
        )
        s.habits.forEach { if (it.reminder.isNotBlank()) LocalTime.parse(it.reminder) }
        require(
            s.accounts.map { it.id }.distinct().size == s.accounts.size &&
                s.accounts.all { it.id > 0 && it.currency in listOf("RUB", "USD", "EUR", "SEK", "GBP", "CHF", "KZT", "BYN", "UAH", "TRY", "AED") }
        )
        require(s.categories.all { it.id > 0 && it.type in listOf("INCOME", "EXPENSE") })
        require(
            s.transactions.all { t ->
                t.amount > 0 &&
                    t.type in listOf("INCOME", "EXPENSE", "TRANSFER","REFUND") &&
                    s.accounts.any { it.id == t.accountId } &&
                    (if (t.type == "TRANSFER")
                        t.toAccountId != t.accountId &&
                            t.categoryId == null &&
                            s.accounts.any {
                                it.id == t.toAccountId &&
                                    it.currency ==
                                        s.accounts.first { a -> a.id == t.accountId }.currency
                            }
                    else s.categories.any { it.id == t.categoryId && it.type == (if(t.type=="REFUND")"EXPENSE" else t.type) })
            }
        )
        require(
            s.habits.all {
                it.id > 0 &&
                    it.name.isNotBlank() &&
                    it.target in 1..10000 &&
                    it.weeklyTarget in 1..7 &&
                    it.frequency in listOf("DAILY", "DAYS", "WEEKLY") &&
                    (it.frequency != "DAYS" ||
                        it.days.split(",").all { d -> d.toIntOrNull() in 1..7 }) &&
                    it.createdDay <= LocalDate.now().toEpochDay()
            }
        )
        require(s.goals.all { it.target > 0 && it.current >= 0 })
        require(s.budgets.all { it.amount > 0 })
        require(s.completions.all { c -> c.count > 0 && s.habits.any { it.id == c.habitId } })
        require(
            b.settings.currency in listOf("RUB", "USD", "EUR", "SEK", "GBP", "CHF", "KZT", "BYN", "UAH", "TRY", "AED") &&
                b.settings.theme in listOf("LIGHT", "DARK", "SYSTEM")
        )
        s.habits.forEach { LocalDate.ofEpochDay(it.createdDay) }
        s.goals.forEach { g -> g.deadline?.let { LocalDate.ofEpochDay(it) } }
        s.completions.forEach { LocalDate.ofEpochDay(it.day) }
        s.accounts.forEach { FinanceCalculator.balance(s, it) }
        s.accounts
            .map { it.currency }
            .distinct()
            .forEach { FinanceCalculator.stats(s, LocalDate.MIN, LocalDate.MAX, it) }
        s.budgets.forEach { YearMonth.parse(it.month) }
        db.withTransaction {
            bank.clear()
            product.clear()
            food.clear()
            dao.clearTransactions()
            dao.clearBudgets()
            dao.clearHabits()
            dao.clearGoals()
            dao.clearAccounts()
            dao.clearCategories()
            s.accounts.forEach { dao.account(it) }
            s.categories.forEach { dao.category(it) }
            s.transactions.forEach { dao.transaction(it) }
            s.budgets.forEach { dao.budget(it) }
            s.habits.forEach { dao.habit(it) }
            s.completions.forEach { dao.completion(it) }
            s.goals.forEach { dao.goal(it) }
            food.restore(s.nutrition)
            product.restore(s.product)
            bank.restore(s.banking)
        }
        return b.settings.copy(onboarded = true)
    }
}

data class HabitTrash(val habit: HabitEntity, val completions: List<HabitCompletionEntity>)
