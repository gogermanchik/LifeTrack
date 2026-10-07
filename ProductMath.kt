package com.lifetrack.banking

import android.app.Application
import androidx.lifecycle.*
import com.lifetrack.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

class BankViewModel(app: Application) : AndroidViewModel(app) {
    val db = LifeStore.get(app)
    val repo = BankRepository(db)
    val data =
        repo.snapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BankSnapshot())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    init {
        viewModelScope.launch { repo.dao.apps().collect { BankConsent.set(app, it) } }
    }

    fun run(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                error.value = null
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value =
                    if (e is IllegalArgumentException || e is IllegalStateException)
                        e.message ?: "Проверьте данные операции"
                    else "Не удалось обработать файл или операцию. Проверьте данные и повторите"
                messages.emit(error.value!!)
            }
        }
    }

    fun select(app: BankApp) = run {
        repo.dao.put(app)
        BankConsent.set(getApplication(), repo.dao.appsNow())
    }

    var table = MutableStateFlow<StatementTable?>(null)
    val mapping = MutableStateFlow(ColumnMapping())
    val rows = MutableStateFlow<List<StatementRow>>(emptyList())
    val importAccount = MutableStateFlow<Long?>(null)

    fun load(bytes: ByteArray, xlsx: Boolean) = run {
        busy.value = true
        try {
            table.value =
                withContext(Dispatchers.IO) {
                    if (xlsx) XlsxStatementReader.read(bytes) else CsvStatementReader.read(bytes)
                }
            val t = table.value!!
            val hash = BankFingerprint.hash(t.headers.joinToString("|"))
            val profile = repo.dao.profiles().first().firstOrNull { it.headerHash == hash }
            mapping.value =
                profile?.let { Json.decodeFromString<ColumnMapping>(it.mapping) }
                    ?: StatementMapping.detect(t.headers)
            rows.value = emptyList()
        } finally {
            busy.value = false
        }
    }

    fun preview(currency: String) = run {
        rows.value =
            StatementMapping.preview(table.value ?: error("Выберите файл"), mapping.value, currency)
    }

    fun import(s: Snapshot, profileName: String) = run {
        val account =
            s.accounts.firstOrNull { it.id == importAccount.value } ?: error("Выберите счёт")
        val valid = rows.value.filter { it.parsed != null }
        require(valid.isNotEmpty()) { "Нет операций для импорта" }
        busy.value = true
        try {
            // Import only reviewed file rows. Ambiguous transfers remain pending, not expenses.
            for (r in valid) {
                val p = r.parsed!!
                val identity = BankFingerprint.hash("${account.id}|${r.identity}")
                val source = "statement:${account.id}"
                val existing = repo.dao.identity(identity)
                if (existing != null) continue
                val same =
                    s.banking.sources
                        .filter {
                            it.source == "STATEMENT_IMPORT" &&
                                it.normalizedMerchant == p.normalizedMerchant
                        }
                        .mapNotNull { meta ->
                            s.transactions.firstOrNull {
                                it.id == meta.transactionId &&
                                    it.accountId == account.id &&
                                    it.amount == p.amount &&
                                    kotlin.math.abs(it.time - p.timestamp) < 60000
                            }
                        }
                val candidate = repo.ingest(source, p, identity, "STATEMENT_IMPORT") ?: continue
                if (same.isNotEmpty()) {
                    repo.dao.put(candidate.copy(duplicate = true))
                    continue
                }
                if (p.currency != account.currency || p.confidence != Confidence.HIGH) continue
                val category =
                    MerchantCategorizer.suggest(p, s.categories, repo.dao.rules().first())
                try {
                    repo.confirm(candidate, account.id, category)
                } catch (e: IllegalArgumentException) {
                    /* Ambiguous reconciliation stays in Inbox. */
                }
            }
            val t = table.value!!
            repo.dao.put(
                ImportProfile(
                    profileName.ifBlank { "Моя выписка" }.take(80),
                    BankFingerprint.hash(t.headers.joinToString("|")),
                    Json.encodeToString(ColumnMapping.serializer(), mapping.value),
                )
            )
            messages.emit("Выписка обработана. Спорные строки — в очереди проверки")
            rows.value = emptyList()
            table.value = null
        } finally {
            busy.value = false
        }
    }
}
