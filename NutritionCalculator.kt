package com.lifetrack.banking

import androidx.room.withTransaction
import com.lifetrack.data.*
import com.lifetrack.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.*

object MerchantCategorizer {
    fun suggest(
        p: ParsedBankTransaction,
        categories: List<CategoryEntity>,
        rules: List<MerchantRule>,
    ): Long? {
        val type = if (p.type == BankKind.INCOME) "INCOME" else "EXPENSE"
        val available = categories.filter { it.type == type }
        rules
            .firstOrNull {
                it.merchant == p.normalizedMerchant &&
                    it.type == type &&
                    available.any { c -> c.id == it.categoryId }
            }
            ?.let {
                return it.categoryId
            }
        val names =
            when {
                type == "INCOME" -> listOf("Прочее", "Другие доходы", "Зарплата")
                listOf("перекресток", "перекрёсток", "пятерочка", "пятёрочка", "вкусвилл").any {
                    it in p.normalizedMerchant
                } -> listOf("Продукты", "Еда", "Питание")
                listOf("яндекс go", "yandex go", "uber", "такси").any {
                    it in p.normalizedMerchant
                } -> listOf("Транспорт", "Такси")
                listOf("restaurant", "ресторан", "кафе", "cafe", "доставка").any {
                    it in p.normalizedMerchant
                } -> listOf("Рестораны", "Кафе", "Еда")
                p.type == BankKind.FEE -> listOf("Комиссии", "Прочее")
                else -> listOf("Прочее", "Другие расходы")
            }
        return names.firstNotNullOfOrNull { name ->
            available.firstOrNull { it.name.equals(name, true) }?.id
        } ?: available.firstOrNull()?.id
    }
}

class BankRepository(private val db: LifeDatabase) {
    val dao = db.bankDao()
    val snapshots =
        combine(dao.apps(), dao.candidates(), dao.rules(), dao.sources(), dao.profiles()) {
            a,
            b,
            c,
            d,
            e ->
            BankSnapshot(a, b, c, d, e)
        }

    private suspend fun finance(): Snapshot =
        Snapshot(
            accounts = db.dao().accountsNow(),
            categories = db.dao().categoriesNow(),
            transactions = db.dao().transactionsNow(),
        )

    suspend fun ingest(
        packageName: String,
        p: ParsedBankTransaction,
        identity: String,
        source: String = "NOTIFICATION",
    ): BankCandidate? {
        return db.withTransaction {
            if (dao.identity(identity) != null) return@withTransaction null
            if (
                source == "NOTIFICATION" &&
                    dao.appsNow().none { it.packageName == packageName && it.enabled }
            )
                return@withTransaction null
            val duplicate =
                dao.candidatesNow().any {
                    it.state != "REJECTED" &&
                        it.packageName == packageName &&
                        BankFingerprint.likelySame(parsed(it), p)
                }
            val row =
                BankCandidate(
                    UUID.randomUUID().toString(),
                    packageName,
                    p.type.name,
                    p.amount,
                    p.currency,
                    p.rawMerchant,
                    p.normalizedMerchant,
                    p.timestamp,
                    p.accountHint,
                    p.confidence.name,
                    source,
                    identity,
                    BankFingerprint.of(packageName, p),
                    duplicate = duplicate,
                )
            dao.put(row)
            row
        }
    }

    suspend fun confirm(
        candidate: BankCandidate,
        accountId: Long,
        categoryId: Long?,
        kind: BankKind = BankKind.valueOf(candidate.kind),
        merchant: String = candidate.rawMerchant,
        amount: Long = candidate.amount,
        toAccountId: Long? = null,
        learn: Boolean = false,
        allowDuplicate: Boolean = false,
    ): Long {
        return db.withTransaction {
            val row = dao.identity(candidate.identity) ?: error("Операция больше недоступна")
            require(row.state == "PENDING") { "Операция уже обработана" }
            require(!row.duplicate || allowDuplicate) { "Проверьте возможный дубль" }
            val s = finance()
            val account = s.accounts.firstOrNull { it.id == accountId } ?: error("Выберите счёт")
            require(account.currency == row.currency) {
                "Валюта уведомления и счёта должна совпадать"
            }
            require(amount > 0 && row.timestamp <= System.currentTimeMillis())
            val type =
                when (kind) {
                    BankKind.PURCHASE,
                    BankKind.FEE -> "EXPENSE"
                    BankKind.INCOME -> "INCOME"
                    BankKind.REFUND -> "REFUND"
                    BankKind.TRANSFER,
                    BankKind.CASH_WITHDRAWAL -> "TRANSFER"
                    BankKind.UNKNOWN -> error("Выберите тип операции")
                }
            if (type == "TRANSFER")
                require(
                    toAccountId != accountId &&
                        s.accounts.any { it.id == toAccountId && it.currency == account.currency }
                ) {
                    "Выберите второй собственный счёт; снятие можно перевести на наличный счёт"
                }
            else
                require(
                    s.categories.any {
                        it.id == categoryId &&
                            it.type == if (type == "INCOME") "INCOME" else "EXPENSE"
                    }
                ) {
                    "Выберите категорию"
                }
            val normalized = MerchantNormalization.normalize(merchant)
            // Statement updates a uniquely matching provisional notification. User's category is
            // preserved.
            val provisional =
                if (row.source == "STATEMENT_IMPORT")
                    dao.sourcesNow()
                        .filter { it.provisional && it.normalizedMerchant == normalized }
                        .mapNotNull { meta ->
                            s.transactions.firstOrNull {
                                it.id == meta.transactionId &&
                                    it.accountId == accountId &&
                                    it.type == type &&
                                    it.amount == amount &&
                                    kotlin.math.abs(it.time - row.timestamp) <= 3 * 86400000L
                            }
                        }
                else emptyList()
            require(provisional.size <= 1) {
                "Несколько похожих уведомлений. Проверьте их в очереди до импорта"
            }
            val old = provisional.singleOrNull()
            val v =
                TransactionEntity(
                    id = old?.id ?: 0,
                    amount = amount,
                    type = type,
                    accountId = accountId,
                    toAccountId = if (type == "TRANSFER") toAccountId else null,
                    categoryId = if (type == "TRANSFER") null else old?.categoryId ?: categoryId,
                    time = row.timestamp,
                    description = merchant.take(160),
                    createdAt = old?.createdAt ?: System.currentTimeMillis(),
                )
            val updated = s.copy(transactions = s.transactions.filter { it.id != v.id } + v)
            updated.accounts.forEach { FinanceCalculator.balance(updated, it) }
            val id = db.dao().transaction(v).let { if (v.id == 0L) it else v.id }
            val refund =
                if (type == "REFUND")
                    s.transactions
                        .filter {
                            it.accountId == accountId &&
                                it.type == "EXPENSE" &&
                                MerchantNormalization.normalize(it.description) == normalized &&
                                it.amount >= amount &&
                                it.time <= row.timestamp
                        }
                        .maxByOrNull { it.time }
                        ?.id
                else null
            dao.put(
                TransactionSource(
                    id,
                    row.source,
                    row.identity,
                    merchant.take(160),
                    normalized,
                    kind.name,
                    refund,
                    row.source == "NOTIFICATION",
                )
            )
            dao.put(
                row.copy(
                    state = "CONFIRMED",
                    transactionId = id,
                    kind = kind.name,
                    amount = amount,
                    rawMerchant = merchant.take(160),
                    normalizedMerchant = normalized,
                )
            )
            if (learn && categoryId != null && normalized.isNotBlank() && type != "TRANSFER")
                dao.put(
                    MerchantRule(
                        normalized,
                        categoryId,
                        if (type == "INCOME") "INCOME" else "EXPENSE",
                    )
                )
            id
        }
    }

    suspend fun reject(v: BankCandidate) {
        db.withTransaction {
            val current = dao.identity(v.identity)
            if (current?.state == "PENDING") dao.put(current.copy(state = "REJECTED"))
        }
    }

    suspend fun clear() {
        dao.clearSources()
        dao.clearCandidates()
        dao.clearApps()
        dao.clearRules()
        dao.clearProfiles()
    }

    suspend fun restore(s: BankSnapshot) {
        s.apps.forEach { dao.put(it) }
        s.candidates.forEach { dao.put(it) }
        s.rules.forEach { dao.put(it) }
        s.sources.forEach { dao.put(it) }
        s.profiles.forEach { dao.put(it) }
    }

    companion object {
        fun parsed(v: BankCandidate) =
            ParsedBankTransaction(
                BankKind.valueOf(v.kind),
                v.amount,
                v.currency,
                v.rawMerchant,
                v.normalizedMerchant,
                v.timestamp,
                v.accountHint,
                Confidence.valueOf(v.confidence),
            )

        fun validate(s: Snapshot) {
            val b = s.banking
            require(
                b.apps.map { it.packageName }.distinct().size == b.apps.size &&
                    b.candidates.map { it.id }.distinct().size == b.candidates.size &&
                    b.candidates.map { it.identity }.distinct().size == b.candidates.size &&
                    b.rules.map { it.merchant }.distinct().size == b.rules.size &&
                    b.sources.map { it.transactionId }.distinct().size == b.sources.size &&
                    b.sources.map { it.identity }.distinct().size == b.sources.size &&
                    b.profiles.map { it.name }.distinct().size == b.profiles.size
            )
            require(
                b.apps.all {
                    it.packageName.isNotBlank() &&
                        (it.accountId == null || s.accounts.any { a -> a.id == it.accountId })
                }
            )
            require(
                b.candidates.all {
                    it.amount > 0 &&
                        it.timestamp <= System.currentTimeMillis() &&
                        it.kind in BankKind.entries.map { it.name } &&
                        it.confidence in Confidence.entries.map { it.name } &&
                        it.source in listOf("NOTIFICATION", "STATEMENT_IMPORT", "BANK_API") &&
                        it.state in listOf("PENDING", "CONFIRMED", "REJECTED") &&
                        it.rawMerchant.length <= 160 &&
                        it.accountHint.length <= 4 &&
                        (it.transactionId == null ||
                            s.transactions.any { t -> t.id == it.transactionId })
                }
            )
            require(
                b.rules.all { r -> s.categories.any { it.id == r.categoryId && it.type == r.type } }
            )
            require(
                b.sources.all { r ->
                    s.transactions.any { it.id == r.transactionId } &&
                        r.source in
                            listOf("MANUAL", "NOTIFICATION", "STATEMENT_IMPORT", "BANK_API") &&
                        (r.refundOf == null ||
                            s.transactions.any { it.id == r.refundOf && it.type == "EXPENSE" })
                }
            )
            require(b.profiles.all { it.name.isNotBlank() && it.mapping.length <= 5000 })
        }
    }
}
