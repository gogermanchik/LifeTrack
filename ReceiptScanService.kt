package com.lifetrack.banking

import java.io.*
import java.math.BigDecimal
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class StatementTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val hash: String,
)

@Serializable
data class ColumnMapping(
    val date: Int = -1,
    val amount: Int = -1,
    val currency: Int = -1,
    val merchant: Int = -1,
    val description: Int = -1,
    val type: Int = -1,
    val time: Int = -1,
    val externalId: Int = -1,
)

data class StatementRow(
    val line: Int,
    val parsed: ParsedBankTransaction?,
    val identity: String,
    val error: String? = null,
)

interface StatementReader {
    fun read(bytes: ByteArray): StatementTable
}

object CsvStatementReader : StatementReader {
    override fun read(bytes: ByteArray): StatementTable {
        require(bytes.size <= 5_000_000) { "Выписка слишком велика" }
        val text =
            runCatching {
                    java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString()
                }
                .getOrElse { String(bytes, java.nio.charset.Charset.forName("windows-1251")) }
                .removePrefix("\uFEFF")
        val first = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(';', ',', '\t').maxBy { d -> first.count { it == d } }
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun finishCell() {
            require(cell.length <= 10000 && row.size < 100)
            row += cell.toString()
            cell.setLength(0)
        }
        fun finishRow() {
            finishCell()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
            require(rows.size <= 10001) { "В выписке больше 10000 строк" }
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> {
                    if (quoted && i + 1 < text.length && text[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else quoted = !quoted
                }
                c == delimiter && !quoted -> finishCell()
                c == '\n' && !quoted -> finishRow()
                c == '\r' && !quoted -> {}
                else -> cell.append(c)
            }
            i++
        }
        require(!quoted) { "Незакрытая кавычка в CSV" }
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        require(rows.size >= 2) { "Нет строк операций" }
        return StatementTable(
            rows.first().map { it.trim() },
            rows.drop(1),
            BankFingerprint.hash(text),
        )
    }
}

object XlsxStatementReader : StatementReader {
    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        require(bytes.size <= 5_000_000)
        val map = mutableMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                require(++count <= 1000)
                val keep = e.name == "xl/sharedStrings.xml" || e.name == "xl/worksheets/sheet1.xml"
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var n = zip.read(buffer)
                while (n != -1) {
                    total += n
                    require(total <= 20_000_000) { "Распакованная выписка слишком велика" }
                    if (keep) out.write(buffer, 0, n)
                    n = zip.read(buffer)
                }
                if (keep) map[e.name] = out.toByteArray()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return map
    }

    private fun parser(bytes: ByteArray) =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(bytes), "UTF-8")
        }

    override fun read(bytes: ByteArray): StatementTable {
        val files = entries(bytes)
        val strings = mutableListOf<String>()
        files["xl/sharedStrings.xml"]?.let { xml ->
            val p = parser(xml)
            val current = StringBuilder()
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && p.name == "si") current.setLength(0)
                if (event == XmlPullParser.START_TAG && p.name == "t") current.append(p.nextText())
                if (event == XmlPullParser.END_TAG && p.name == "si") strings += current.toString()
                event = p.next()
                require(strings.size <= 100000)
            }
        }
        val sheet =
            files["xl/worksheets/sheet1.xml"]
                ?: error("Нужен XLSX с первым листом. Старый XLS не поддерживается")
        val p = parser(sheet)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var index = 0
        var type = ""
        var current = ""
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        val ref =
                            p.getAttributeValue(null, "r").orEmpty().takeWhile { it.isLetter() }
                        index =
                            ref.fold(0) { a, c -> a * 26 + c.uppercaseChar().code - 'A'.code + 1 } -
                                1
                        require(index in 0..99)
                        type = p.getAttributeValue(null, "t").orEmpty()
                        current = ""
                    }
                    "v",
                    "t" -> current = p.nextText()
                }
            }
            if (event == XmlPullParser.END_TAG) {
                when (p.name) {
                    "c" -> {
                        while (row.size <= index) row += ""
                        row[index] =
                            if (type == "s") strings.getOrElse(current.toIntOrNull() ?: -1) { "" }
                            else current
                    }
                    "row" -> {
                        if (row.any { it.isNotBlank() }) rows += row.toList()
                        require(rows.size <= 10001)
                    }
                }
            }
            event = p.next()
        }
        require(rows.size >= 2) { "Нет строк операций" }
        return StatementTable(
            rows.first(),
            rows.drop(1),
            BankFingerprint.hash(java.util.Base64.getEncoder().encodeToString(bytes)),
        )
    }
}

object StatementMapping {
    fun detect(headers: List<String>): ColumnMapping {
        fun find(vararg keys: String): Int {
            return headers.indexOfFirst { h -> keys.any { h.trim().equals(it, true) } }
        }
        return ColumnMapping(
            find("Дата", "Date", "Дата операции", "Operation date"),
            find("Сумма", "Amount", "Сумма операции"),
            find("Валюта", "Currency"),
            find("Merchant", "Магазин", "Получатель"),
            find("Описание", "Description", "Назначение"),
            find("Тип", "Type"),
            find("Время", "Time"),
            find("ID", "Идентификатор операции", "Operation ID"),
        )
    }

    fun time(value: String, clock: String): Long {
        val v = value.trim()
        runCatching { Instant.parse(v) }
            .getOrNull()
            ?.let {
                return it.toEpochMilli()
            }
        runCatching { OffsetDateTime.parse(v) }
            .getOrNull()
            ?.let {
                return it.toInstant().toEpochMilli()
            }
        listOf(
                "dd.MM.yyyy HH:mm:ss",
                "dd.MM.yyyy HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
            )
            .forEach { pattern ->
                runCatching { LocalDateTime.parse(v, DateTimeFormatter.ofPattern(pattern)) }
                    .getOrNull()
                    ?.let {
                        return it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
            }
        val numeric = v.toBigDecimalOrNull()
        if (numeric != null && numeric > BigDecimal(20000) && numeric < BigDecimal(100000)) {
            val days = numeric.toLong()
            val fraction = numeric.subtract(BigDecimal(days))
            return LocalDate.of(1899, 12, 30)
                .plusDays(days)
                .atStartOfDay()
                .plusSeconds(fraction.multiply(BigDecimal(86400)).toLong())
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }
        val date =
            listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd/MM/yyyy").firstNotNullOfOrNull {
                runCatching { LocalDate.parse(v, DateTimeFormatter.ofPattern(it)) }.getOrNull()
            } ?: error("Не удалось прочитать дату")
        val time = if (clock.isBlank()) LocalTime.NOON else LocalTime.parse(clock)
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun preview(
        table: StatementTable,
        mapping: ColumnMapping,
        defaultCurrency: String,
    ): List<StatementRow> {
        require(mapping.date >= 0 && mapping.amount >= 0) { "Сопоставьте дату и сумму" }
        return table.rows.mapIndexed { index, row ->
            val identity = BankFingerprint.hash("statement|${table.hash}|$index")
            try {
                fun field(column: Int): String {
                    return row.getOrElse(column) { "" }.trim()
                }
                val timestamp = time(field(mapping.date), field(mapping.time))
                require(timestamp <= System.currentTimeMillis()) { "Дата операции в будущем" }
                val money =
                    field(mapping.amount)
                        .replace(Regex("[\\s\\u00a0\\u202f]"), "")
                        .replace(',', '.')
                        .toBigDecimal()
                val minor =
                    money
                        .abs()
                        .setScale(2, java.math.RoundingMode.UNNECESSARY)
                        .movePointRight(2)
                        .longValueExact()
                require(minor > 0)
                val currency =
                    field(mapping.currency)
                        .ifBlank { defaultCurrency }
                        .uppercase(java.util.Locale.ROOT)
                        .let { if (it in listOf("₽", "РУБ", "РУБ.")) "RUB" else it }
                val merchant =
                    field(mapping.merchant).ifBlank { field(mapping.description) }.take(160)
                val typeText = (field(mapping.type) + " " + field(mapping.description)).lowercase()
                val kind =
                    when {
                        Regex("refund|возврат").containsMatchIn(typeText) -> BankKind.REFUND
                        Regex("transfer|перевод|погашение кредита").containsMatchIn(typeText) ->
                            BankKind.TRANSFER
                        Regex("withdrawal|снятие").containsMatchIn(typeText) ->
                            BankKind.CASH_WITHDRAWAL
                        Regex("fee|комиссия").containsMatchIn(typeText) -> BankKind.FEE
                        Regex("(?iu)expense|debit|расход|списание|покупка|оплата").containsMatchIn(typeText) -> BankKind.PURCHASE
                        Regex("(?iu)income|credit|доход|поступление|зачисление").containsMatchIn(typeText) -> BankKind.INCOME
                        money.signum() < 0 -> BankKind.PURCHASE
                        else -> BankKind.INCOME
                    }
                val confidence =
                    if (
                        kind in setOf(BankKind.TRANSFER, BankKind.CASH_WITHDRAWAL) ||
                            merchant.isBlank()
                    )
                        Confidence.LOW
                    else Confidence.HIGH
                StatementRow(
                    index + 2,
                    ParsedBankTransaction(
                        kind,
                        minor,
                        currency,
                        merchant,
                        MerchantNormalization.normalize(merchant),
                        timestamp,
                        "",
                        confidence,
                    ),
                    field(mapping.externalId)
                        .takeIf { it.isNotBlank() }
                        ?.let { BankFingerprint.hash("statement|$defaultCurrency|$it") } ?: identity,
                )
            } catch (e: Exception) {
                StatementRow(index + 2, null, identity, e.message ?: "Проверьте строку")
            }
        }
    }
}
