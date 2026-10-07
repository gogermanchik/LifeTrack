package com.lifetrack

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lifetrack.banking.*
import com.lifetrack.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BankRepositoryTest {
    @get:Rule
    val migration =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeDatabase::class.java)

    @Test
    fun migrationV1ThroughV4PreservesExistingBalances() {
        val old = migration.createDatabase("bank-migration", 1)
        old.execSQL("INSERT INTO accounts VALUES(1,'Личный',123456,'RUB','CARD',0)")
        old.close()
        val upgraded =
            migration.runMigrationsAndValidate(
                "bank-migration",
                4,
                true,
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
            )
        upgraded.query("SELECT opening FROM accounts").use {
            assertTrue(it.moveToFirst())
            assertEquals(123456L, it.getLong(0))
        }
        upgraded.close()
    }

    private fun db() =
        Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                LifeDatabase::class.java,
            )
            .build()

    private fun parsed(
        kind: BankKind = BankKind.PURCHASE,
        time: Long = System.currentTimeMillis() - 60000,
    ) =
        ParsedBankTransaction(
            kind,
            10000,
            "RUB",
            "Магазин",
            "магазин",
            time,
            "1234",
            Confidence.MEDIUM,
        )

    @Test
    fun selectedPackagesExactDedupAndPossibleDuplicatesRequireReview() = runBlocking {
        val db = db()
        try {
            val r = BankRepository(db)
            assertNull(r.ingest("bank", parsed(), "first"))
            db.dao().account(AccountEntity(1, "Карта"))
            db.dao().category(CategoryEntity(1, "Продукты", "EXPENSE"))
            db.bankDao().put(BankApp("bank", "Банк", true, 1))
            val p = parsed()
            val first = r.ingest("bank", p, "first")!!
            assertNull(r.ingest("bank", p, "first"))
            val second = r.ingest("bank", p, "second")!!
            assertTrue(second.duplicate)
            r.confirm(first, 1, 1, learn = true)
            assertTrue(runCatching { r.confirm(first, 1, 1) }.isFailure)
            assertTrue(runCatching { r.confirm(second, 1, 1) }.isFailure)
            assertEquals(1, db.dao().transactionsNow().size)
            assertEquals(1, db.bankDao().rules().first().size)
            r.reject(second)
            assertEquals("REJECTED", db.bankDao().identity("second")!!.state)
        } finally {
            db.close()
        }
    }

    @Test
    fun statementReconcilesNotificationWithoutOverwritingChosenCategory() = runBlocking {
        val db = db()
        try {
            db.dao().account(AccountEntity(1, "Карта"))
            db.dao().category(CategoryEntity(1, "Моя категория", "EXPENSE"))
            db.dao().category(CategoryEntity(2, "Другая", "EXPENSE"))
            db.bankDao().put(BankApp("bank", "Банк", true, 1))
            val r = BankRepository(db)
            val p = parsed()
            val n = r.ingest("bank", p, "n")!!
            val id = r.confirm(n, 1, 1)
            val stmt =
                r.ingest(
                    "file",
                    p.copy(timestamp = p.timestamp - 3600000),
                    "s",
                    "STATEMENT_IMPORT",
                )!!
            assertEquals(id, r.confirm(stmt, 1, 2))
            assertEquals(1, db.dao().transactionsNow().size)
            assertEquals(1L, db.dao().transactionsNow().single().categoryId)
            assertFalse(db.bankDao().sourcesNow().single().provisional)
            assertEquals("STATEMENT_IMPORT", db.bankDao().sourcesNow().single().source)
        } finally {
            db.close()
        }
    }

    @Test
    fun bankingBackupRoundtripPreservesLearningAndSourceMetadata() = runBlocking {
        val db = db()
        try {
            val repo = LocalLifeRepository(db)
            repo.initialize()
            val s = repo.snapshots.first()
            val account = s.accounts.first()
            val category = s.categories.first { it.type == "EXPENSE" }
            db.bankDao().put(BankApp("bank", "Банк", true, account.id))
            val r = BankRepository(db)
            val v = r.ingest("bank", parsed(), "n")!!
            r.confirm(v, account.id, category.id, learn = true)
            db.bankDao().put(ImportProfile("Выписка", "hash", "{}"))
            val before = repo.snapshots.first()
            repo.import(repo.export(before, Settings()))
            assertEquals(before, repo.snapshots.first())
        } finally {
            db.close()
        }
    }

    @Test
    fun transfersNeedSecondAccountAndRefundLinksPurchase() = runBlocking {
        val db = db()
        try {
            db.dao().account(AccountEntity(1, "Карта"))
            db.dao().account(AccountEntity(2, "Наличные"))
            db.dao().category(CategoryEntity(1, "Еда", "EXPENSE"))
            val r = BankRepository(db)
            val p = parsed()
            val purchase = r.ingest("file", p, "p", "STATEMENT_IMPORT")!!
            val expense = r.confirm(purchase, 1, 1)
            val refund =
                r.ingest(
                    "file",
                    p.copy(type = BankKind.REFUND, timestamp = p.timestamp + 1000),
                    "r",
                    "STATEMENT_IMPORT",
                )!!
            val id = r.confirm(refund, 1, 1)
            assertEquals(
                expense,
                db.bankDao().sourcesNow().first { it.transactionId == id }.refundOf,
            )
            val transfer =
                r.ingest("file", p.copy(type = BankKind.CASH_WITHDRAWAL), "t", "STATEMENT_IMPORT")!!
            assertTrue(runCatching { r.confirm(transfer, 1, null) }.isFailure)
            r.confirm(transfer, 1, null, toAccountId = 2)
            assertEquals("TRANSFER", db.dao().transactionsNow().last().type)
        } finally {
            db.close()
        }
    }
}
