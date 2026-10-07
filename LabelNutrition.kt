package com.lifetrack.banking

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer

enum class BankKind {
    PURCHASE,
    INCOME,
    TRANSFER,
    REFUND,
    CASH_WITHDRAWAL,
    FEE,
    UNKNOWN,
}

enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class ParsedBankTransaction(
    val type: BankKind,
    val amount: Long,
    val currency: String,
    val rawMerchant: String,
    val normalizedMerchant: String,
    val timestamp: Long,
    val accountHint: String,
    val confidence: Confidence,
)

interface BankNotificationParser {
    fun canParse(packageName: String, title: String?, text: String?): Boolean

    fun parse(
        packageName: String,
        title: String?,
        text: String?,
        timestamp: Long,
    ): ParsedBankTransaction?
}

object MerchantNormalization {
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT)
            .replace(Regex("(?i)^(ооо|ип|oao|пао)\\s+"), "")
            .replace(Regex("[«»\"'.,]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(160)
}

/** Generic formats only. No bank-specific formats/package identities are invented. */
class GenericBankNotificationParser : BankNotificationParser {
    private val action =
        Regex(
            "(?iu)\\b(покупка|оплата|списание|поступление|зачисление|перевод|возврат|снятие|комиссия|purchase|payment|refund|transfer|withdrawal|fee|credited)\\b"
        )
    private val amountPattern =
        Regex(
            "(?iu)([−-]?\\d(?:[\\d\\s\\u00a0\\u202f]*\\d)?(?:[.,]\\d{1,2})?)\\s*(₽|руб\\.?|RUB|USD|EUR|SEK|GBP|CHF|KZT|BYN|UAH|TRY|AED|[$€£])(?=\\s|$|[.,;:])"
        )

    override fun canParse(packageName: String, title: String?, text: String?) =
        action.containsMatchIn("${title.orEmpty()} ${text.orEmpty()}")

    override fun parse(
        packageName: String,
        title: String?,
        text: String?,
        timestamp: Long,
    ): ParsedBankTransaction? {
        val value = "${title.orEmpty()}\n${text.orEmpty()}".take(4096)
        // Never read OTP, sign-in, card application or advertisements as a movement.
        if (
            Regex(
                    "(?iu)(код\\s+(подтверждения|входа)|one.time|verification|otp\\b|кэшбэк\\s+до|до\\s+\\d+%|кредит\\s+одобрен)"
                )
                .containsMatchIn(value)
        )
            return null
        val verb = action.find(value) ?: return null
        val after = value.substring(verb.range.first)
        val match = amountPattern.find(after) ?: return null
        val monetary =
            runCatching {
                    BigDecimal(
                            match.groupValues[1]
                                .replace(Regex("[\\s\\u00a0\\u202f]"), "")
                                .replace('−', '-')
                                .replace(',', '.')
                        )
                        .abs()
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .movePointRight(2)
                        .longValueExact()
                }
                .getOrNull() ?: return null
        if (monetary <= 0 || monetary > 100_000_000_000_000L) return null
        val currency =
            when (match.groupValues[2].uppercase(java.util.Locale.ROOT)) {
                "₽",
                "РУБ",
                "РУБ.",
                "RUB" -> "RUB"
                "$" -> "USD"
                "€" -> "EUR"
                "£" -> "GBP"
                else -> match.groupValues[2].uppercase(java.util.Locale.ROOT)
            }
        val kind =
            when (verb.value.lowercase(java.util.Locale.ROOT)) {
                "покупка",
                "оплата",
                "списание",
                "purchase",
                "payment" -> BankKind.PURCHASE
                "поступление",
                "зачисление",
                "credited" -> BankKind.INCOME
                "возврат",
                "refund" -> BankKind.REFUND
                "перевод",
                "transfer" -> BankKind.TRANSFER
                "снятие",
                "withdrawal" -> BankKind.CASH_WITHDRAWAL
                "комиссия",
                "fee" -> BankKind.FEE
                else -> BankKind.UNKNOWN
            }
        val hint =
            Regex("(?iu)(?:карта|card|\\*{2,}|[•·]{2,})\\s*[*•·]*\\s*(\\d{4})(?!\\d)")
                .find(value)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val remaining =
            after.substring(match.range.last + 1).trim().trimStart('.', ',', ':', ';', '-', ' ')
        val merchant =
            remaining
                .lineSequence()
                .firstOrNull()
                .orEmpty()
                .substringBefore(
                    Regex("(?iu)\\b(баланс|доступно|остаток|balance|available|карта|card)\\b")
                        .find(remaining)
                        ?.value ?: "\u0000"
                )
                .trim()
                .trimEnd('.', ',', ';')
                .take(160)
        val ambiguous =
            amountPattern.findAll(after).count() > 1 ||
                kind in setOf(BankKind.TRANSFER, BankKind.CASH_WITHDRAWAL, BankKind.UNKNOWN) ||
                merchant.isBlank()
        // Generic notification formats remain MEDIUM until verified with a bank's actual format.
        val confidence =
            if (kind == BankKind.UNKNOWN) Confidence.LOW
            else if (ambiguous) Confidence.LOW else Confidence.MEDIUM
        return ParsedBankTransaction(
            kind,
            monetary,
            currency,
            merchant,
            MerchantNormalization.normalize(merchant),
            timestamp,
            hint,
            confidence,
        )
    }
}

class BankParserRegistry(
    private val parsers: List<BankNotificationParser> = listOf(GenericBankNotificationParser())
) {
    fun parse(
        allowed: Set<String>,
        packageName: String,
        title: String?,
        text: String?,
        timestamp: Long,
    ): ParsedBankTransaction? {
        if (packageName !in allowed) return null
        return parsers
            .firstOrNull { it.canParse(packageName, title, text) }
            ?.parse(packageName, title, text, timestamp)
    }
}

object BankFingerprint {
    fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    fun of(source: String, p: ParsedBankTransaction) =
        hash(
            listOf(
                    source,
                    p.type.name,
                    p.amount,
                    p.currency,
                    p.normalizedMerchant,
                    p.timestamp / 120000,
                    p.accountHint,
                )
                .joinToString("|")
        )

    fun likelySame(a: ParsedBankTransaction, b: ParsedBankTransaction, window: Long = 120000) =
        a.type == b.type &&
            a.amount == b.amount &&
            a.currency == b.currency &&
            a.normalizedMerchant == b.normalizedMerchant &&
            a.accountHint == b.accountHint &&
            kotlin.math.abs(a.timestamp - b.timestamp) <= window
}

interface BankDataProvider {
    suspend fun connect()

    suspend fun disconnect()

    suspend fun getAccounts(): List<com.lifetrack.data.AccountEntity>

    suspend fun getTransactions(from: Long, to: Long): List<com.lifetrack.data.TransactionEntity>

    suspend fun sync()
}
