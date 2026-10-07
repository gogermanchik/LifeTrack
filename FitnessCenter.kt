package com.lifetrack

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.time.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: LocalLifeRepository

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    LifeDatabase::class.java,
                )
                .build()
        repo = LocalLifeRepository(db)
    }

    @After
    fun finish() {
        db.close()
    }

    @Test
    fun demoAndTransferUseRealRoomData() = runBlocking {
        repo.demo()
        val before = repo.snapshots.first()
        val date = LocalDate.now()
        val stats = DashboardCalculator.calculate(before, "RUB", date).finance
        assertEquals(20000000L, stats.income)
        assertEquals(10000000L, stats.expense)
        assertEquals(10000000L, stats.balance)
        val a = before.accounts[0]
        val b = before.accounts[1]
        repo.transaction(
            TransactionEntity(
                amount = 100000,
                type = "TRANSFER",
                categoryId = null,
                accountId = a.id,
                toAccountId = b.id,
            )
        )
        val after = repo.snapshots.first()
        assertEquals(stats, DashboardCalculator.calculate(after, "RUB", date).finance)
        assertEquals(100000L, FinanceCalculator.balance(after, b))
        assertTrue(
            runCatching {
                    repo.transaction(
                        TransactionEntity(
                            amount = 0,
                            type = "INCOME",
                            categoryId = before.categories.first { it.type == "INCOME" }.id,
                            accountId = a.id,
                        )
                    )
                }
                .isFailure
        )
    }

    @Test
    fun deletingAndUndoRestoresHabitHistory() = runBlocking {
        repo.demo()
        val before = repo.snapshots.first()
        val h = before.habits.first()
        val records = before.completions.filter { it.habitId == h.id }
        val trash = repo.delete(h)
        assertFalse(repo.snapshots.first().completions.any { it.habitId == h.id })
        repo.restore(trash)
        val after = repo.snapshots.first()
        assertEquals(h, after.habits.first { it.id == h.id })
        assertEquals(records, after.completions.filter { it.habitId == h.id })
    }

    @Test
    fun backupRoundTripAndInvalidImportPreserveData() = runBlocking {
        repo.demo()
        val before = repo.snapshots.first()
        val settings = Settings(currency = "SEK", theme = "DARK", onboarded = true)
        val json = repo.export(before, settings)
        repo.clear()
        val restored = repo.import(json)
        assertEquals(settings, restored)
        assertEquals(before, repo.snapshots.first())
        assertTrue(
            runCatching { repo.import(kotlinx.serialization.json.Json.encodeToString(Backup.serializer(), kotlinx.serialization.json.Json.decodeFromString(Backup.serializer(), json).copy(version=99))) }
                .isFailure
        )
        assertEquals(before, repo.snapshots.first())
    }

    @Test
    fun completionToggleAndDemoRemovalKeepUserData() = runBlocking {
        repo.demo()
        repo.habit(
            HabitEntity(
                name = "Моя привычка",
                target = 3,
                createdDay = LocalDate.now().toEpochDay(),
            )
        )
        val h = repo.snapshots.first().habits.last()
        repo.toggle(h, LocalDate.now())
        assertEquals(3, repo.snapshots.first().completions.first { it.habitId == h.id }.count)
        repo.toggle(h, LocalDate.now())
        assertFalse(repo.snapshots.first().completions.any { it.habitId == h.id })
        repo.removeDemo()
        assertEquals(listOf(h), repo.snapshots.first().habits)
        assertTrue(repo.snapshots.first().transactions.isEmpty())
        assertTrue(repo.snapshots.first().goals.isEmpty())
    }
}
