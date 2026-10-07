package com.lifetrack.banking

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lifetrack.data.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bank_apps",
    foreignKeys =
        [
            ForeignKey(
                entity = AccountEntity::class,
                parentColumns = ["id"],
                childColumns = ["accountId"],
                onDelete = ForeignKey.RESTRICT,
            )
        ],
    indices = [Index("accountId")],
)
data class BankApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val enabled: Boolean = false,
    val accountId: Long? = null,
)

@Serializable
@Entity(
    tableName = "bank_candidates",
    indices =
        [
            Index(value = ["identity"], unique = true),
            Index("fingerprint"),
            Index(value = ["state", "timestamp"]),
            Index("transactionId"),
        ],
    foreignKeys =
        [
            ForeignKey(
                entity = TransactionEntity::class,
                parentColumns = ["id"],
                childColumns = ["transactionId"],
                onDelete = ForeignKey.SET_NULL,
            )
        ],
)
data class BankCandidate(
    @PrimaryKey val id: String,
    val packageName: String,
    val kind: String,
    val amount: Long,
    val currency: String,
    val rawMerchant: String,
    val normalizedMerchant: String,
    val timestamp: Long,
    val accountHint: String,
    val confidence: String,
    val source: String,
    val identity: String,
    val fingerprint: String,
    val state: String = "PENDING",
    val duplicate: Boolean = false,
    val transactionId: Long? = null,
)

@Serializable
@Entity(
    tableName = "merchant_rules",
    foreignKeys =
        [
            ForeignKey(
                entity = CategoryEntity::class,
                parentColumns = ["id"],
                childColumns = ["categoryId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("categoryId")],
)
data class MerchantRule(
    @PrimaryKey val merchant: String,
    val categoryId: Long,
    val type: String = "EXPENSE",
)

@Serializable
@Entity(
    tableName = "transaction_sources",
    foreignKeys =
        [
            ForeignKey(
                entity = TransactionEntity::class,
                parentColumns = ["id"],
                childColumns = ["transactionId"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = TransactionEntity::class,
                parentColumns = ["id"],
                childColumns = ["refundOf"],
                onDelete = ForeignKey.SET_NULL,
            ),
        ],
    indices = [Index("refundOf"), Index(value = ["identity"], unique = true)],
)
data class TransactionSource(
    @PrimaryKey val transactionId: Long,
    val source: String,
    val identity: String,
    val rawMerchant: String,
    val normalizedMerchant: String,
    val kind: String,
    val refundOf: Long? = null,
    val provisional: Boolean = false,
)

@Serializable
@Entity(tableName = "import_profiles")
data class ImportProfile(@PrimaryKey val name: String, val headerHash: String, val mapping: String)

@Serializable
data class BankSnapshot(
    val apps: List<BankApp> = emptyList(),
    val candidates: List<BankCandidate> = emptyList(),
    val rules: List<MerchantRule> = emptyList(),
    val sources: List<TransactionSource> = emptyList(),
    val profiles: List<ImportProfile> = emptyList(),
)

@Dao
interface BankDao {
    @Query("SELECT * FROM bank_apps") suspend fun appsNow(): List<BankApp>

    @Query("SELECT * FROM bank_candidates") suspend fun candidatesNow(): List<BankCandidate>

    @Query("SELECT * FROM transaction_sources") suspend fun sourcesNow(): List<TransactionSource>

    @Query("SELECT * FROM bank_apps ORDER BY label") fun apps(): Flow<List<BankApp>>

    @Query("SELECT * FROM bank_candidates ORDER BY timestamp DESC")
    fun candidates(): Flow<List<BankCandidate>>

    @Query("SELECT * FROM bank_candidates WHERE identity=:identity LIMIT 1")
    suspend fun identity(identity: String): BankCandidate?

    @Query("SELECT * FROM merchant_rules") fun rules(): Flow<List<MerchantRule>>

    @Query("SELECT * FROM transaction_sources") fun sources(): Flow<List<TransactionSource>>

    @Query("SELECT * FROM import_profiles") fun profiles(): Flow<List<ImportProfile>>

    @Upsert suspend fun put(v: BankApp)

    @Upsert suspend fun put(v: BankCandidate)

    @Upsert suspend fun put(v: MerchantRule)

    @Upsert suspend fun put(v: TransactionSource)

    @Upsert suspend fun put(v: ImportProfile)

    @Delete suspend fun delete(v: MerchantRule)

    @Query("DELETE FROM bank_apps") suspend fun clearApps()

    @Query("DELETE FROM bank_candidates") suspend fun clearCandidates()

    @Query("DELETE FROM merchant_rules") suspend fun clearRules()

    @Query("DELETE FROM transaction_sources") suspend fun clearSources()

    @Query("DELETE FROM import_profiles") suspend fun clearProfiles()
}

val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS bank_apps (packageName TEXT NOT NULL PRIMARY KEY,label TEXT NOT NULL,enabled INTEGER NOT NULL,accountId INTEGER,FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bank_apps_accountId ON bank_apps(accountId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS bank_candidates (id TEXT NOT NULL PRIMARY KEY,packageName TEXT NOT NULL,kind TEXT NOT NULL,amount INTEGER NOT NULL,currency TEXT NOT NULL,rawMerchant TEXT NOT NULL,normalizedMerchant TEXT NOT NULL,timestamp INTEGER NOT NULL,accountHint TEXT NOT NULL,confidence TEXT NOT NULL,source TEXT NOT NULL,identity TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,duplicate INTEGER NOT NULL,transactionId INTEGER,FOREIGN KEY(transactionId) REFERENCES transactions(id) ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_bank_candidates_identity ON bank_candidates(identity)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bank_candidates_fingerprint ON bank_candidates(fingerprint)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bank_candidates_state_timestamp ON bank_candidates(state,timestamp)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_bank_candidates_transactionId ON bank_candidates(transactionId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS merchant_rules (merchant TEXT NOT NULL PRIMARY KEY,categoryId INTEGER NOT NULL,type TEXT NOT NULL,FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_merchant_rules_categoryId ON merchant_rules(categoryId)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS transaction_sources (transactionId INTEGER NOT NULL PRIMARY KEY,source TEXT NOT NULL,identity TEXT NOT NULL,rawMerchant TEXT NOT NULL,normalizedMerchant TEXT NOT NULL,kind TEXT NOT NULL,refundOf INTEGER,provisional INTEGER NOT NULL,FOREIGN KEY(transactionId) REFERENCES transactions(id) ON DELETE CASCADE,FOREIGN KEY(refundOf) REFERENCES transactions(id) ON DELETE SET NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_transaction_sources_refundOf ON transaction_sources(refundOf)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_transaction_sources_identity ON transaction_sources(identity)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS import_profiles (name TEXT NOT NULL PRIMARY KEY,headerHash TEXT NOT NULL,mapping TEXT NOT NULL)"
            )
        }
    }
