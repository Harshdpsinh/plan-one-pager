package com.gohil.bookkeeper.core.xlsx

import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import java.math.BigDecimal
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reproduces the shape of the register the app is really pointed at.
 *
 * Excel reported "Replaced Part: /xl/worksheets/sheet5.xml part with XML error" on a real
 * run, which the earlier fixtures could not have caught: they all put the header on row 1
 * and always included a `<dimension>`. A real register has merged banner rows above the
 * header and may omit parts these edits assumed were present.
 */
class RealWorkbookShapeTest {

    private fun row() = RegisterRow(
        date = LocalDate.of(2026, 6, 12),
        invoiceNo = "INV-9001",
        name = "Acme Traders",
        taxable = BigDecimal("2000.00"),
        cgst = BigDecimal("180.00"),
        sgst = BigDecimal("180.00"),
        totalGrand = BigDecimal("2360.00"),
        paidDate = LocalDate.of(2026, 6, 20),
        paymentMethod = PaymentMethod.UPI,
        category = Category.BUSINESS,
    )

    /**
     * Every XML part must parse. This is the check that was missing: openpyxl is lenient
     * about ordering, so it validated output that Excel refused.
     */
    private fun assertEveryPartIsWellFormed(bytes: ByteArray) {
        val pkg = XlsxPackage.read(bytes)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (name in pkg.entryNames) {
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
            val content = pkg[name] ?: continue
            try {
                factory.newDocumentBuilder().parse(content.inputStream())
            } catch (e: Exception) {
                fail("part '$name' is not well-formed XML: ${e.message}")
            }
        }
    }

    /**
     * The worksheet schema fixes the order of the elements before `<sheetData>`. Excel
     * rejects the part outright when they appear out of sequence, even though the XML is
     * perfectly well-formed — which is why a well-formedness check alone is not enough.
     */
    private fun assertWorksheetElementOrder(bytes: ByteArray, tab: String) {
        val pkg = XlsxPackage.read(bytes)
        val path = WorkbookIndex.from(pkg).findSheet(tab)!!.path
        val xml = pkg.text(path)!!

        val order = listOf(
            "sheetPr", "dimension", "sheetViews", "sheetFormatPr", "cols", "sheetData",
            "sheetCalcPr", "sheetProtection", "mergeCells", "pageMargins",
        )
        val positions = order.mapNotNull { tag ->
            val idx = Regex("""<$tag[\s/>]""").find(xml)?.range?.first
            idx?.let { tag to it }
        }
        val sorted = positions.sortedBy { it.second }
        assertEquals(
            positions.map { it.first },
            sorted.map { it.first },
            "worksheet elements are out of schema order in '$tab': ${sorted.map { it.first }}",
        )
    }

    @Test
    fun `appends to a sheet whose header sits below merged banner rows`() {
        val book = RealisticWorkbook.build(dataRows = 2)
        val appender = XlsxAppender.open(book)
        val result = appender.append(
            XlsxAppender.Request("june", listOf(row()), RegisterType.PURCHASE),
        )
        val out = appender.toByteArray()

        assertEquals(1, result.rowsWritten)
        assertEveryPartIsWellFormed(out)
        assertWorksheetElementOrder(out, "june")
    }

    @Test
    fun `produces a valid sheet when the source omits its dimension element`() {
        // Regression: a missing <dimension> was inserted immediately before <sheetData>,
        // which puts it after <sheetViews> and <cols> and violates the worksheet sequence.
        // Excel rejects the whole part for that, reporting only "XML error".
        val book = RealisticWorkbook.build(omitDimension = true, dataRows = 1)
        val appender = XlsxAppender.open(book)
        appender.append(XlsxAppender.Request("Jun", listOf(row()), RegisterType.PURCHASE))
        val out = appender.toByteArray()

        assertEveryPartIsWellFormed(out)
        assertWorksheetElementOrder(out, "Jun")
    }

    @Test
    fun `appends to a completely empty month tab`() {
        // The April tab in a real register is laid out but has no data rows yet.
        val book = RealisticWorkbook.build(dataRows = 0)
        val appender = XlsxAppender.open(book)
        val result = appender.append(
            XlsxAppender.Request("April", listOf(row()), RegisterType.PURCHASE),
        )
        val out = appender.toByteArray()

        assertEquals(1, result.rowsWritten)
        assertEquals(1, result.firstSrNo)
        assertEveryPartIsWellFormed(out)
        assertWorksheetElementOrder(out, "April")
    }

    @Test
    fun `keeps the merged banner rows intact`() {
        val book = RealisticWorkbook.build(dataRows = 1)
        val appender = XlsxAppender.open(book)
        appender.append(XlsxAppender.Request("Jun", listOf(row()), RegisterType.PURCHASE))
        val out = appender.toByteArray()

        val pkg = XlsxPackage.read(out)
        val path = WorkbookIndex.from(pkg).findSheet("Jun")!!.path
        val xml = pkg.text(path)!!
        assertTrue("<mergeCells count=\"3\">" in xml, "merges must survive")
        assertTrue("Purchase Register" in xml, "banner text must survive")
    }

    @Test
    fun `updates the dimension in place when the sheet has one`() {
        val book = RealisticWorkbook.build(omitDimension = false, dataRows = 2)
        val appender = XlsxAppender.open(book)
        appender.append(XlsxAppender.Request("Jun", listOf(row()), RegisterType.PURCHASE))
        val out = appender.toByteArray()

        assertEveryPartIsWellFormed(out)
        assertWorksheetElementOrder(out, "Jun")

        val pkg = XlsxPackage.read(out)
        val path = WorkbookIndex.from(pkg).findSheet("Jun")!!.path
        assertTrue(
            Regex("""<dimension ref="A1:[A-Z]+7"/>""").containsMatchIn(pkg.text(path)!!),
            "dimension should cover the appended row",
        )
    }

    @Test
    fun `creating a new tab from a banner-style template stays valid`() {
        val book = RealisticWorkbook.build(dataRows = 2)
        val appender = XlsxAppender.open(book)
        val result = appender.append(
            XlsxAppender.Request("August", listOf(row()), RegisterType.PURCHASE),
        )
        val out = appender.toByteArray()

        assertTrue(result.createdSheet)
        assertEveryPartIsWellFormed(out)
        assertWorksheetElementOrder(out, "August")
    }
}
