package com.lifetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifetrack.banking.*
import java.io.ByteArrayOutputStream
import java.util.zip.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatementXlsxTest {
    @Test
    fun sharedStringsAndExcelDatesReadWithoutExecutingFormulas() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun entry(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            entry(
                "xl/sharedStrings.xml",
                "<sst><si><t>Дата</t></si><si><t>Сумма</t></si><si><t>Магазин</t></si><si><t>Кафе</t></si></sst>",
            )
            entry(
                "xl/worksheets/sheet1.xml",
                "<worksheet><sheetData><row><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>1</v></c><c r='C1' t='s'><v>2</v></c></row><row><c r='A2'><v>46299</v></c><c r='B2'><v>-125.50</v></c><c r='C2' t='s'><v>3</v></c></row></sheetData></worksheet>",
            )
        }
        val t = XlsxStatementReader.read(out.toByteArray())
        assertEquals(listOf("Дата", "Сумма", "Магазин"), t.headers)
        val p =
            StatementMapping.preview(t, StatementMapping.detect(t.headers), "RUB").single().parsed!!
        assertEquals(12550L, p.amount)
        assertEquals("Кафе", p.rawMerchant)
    }
}
