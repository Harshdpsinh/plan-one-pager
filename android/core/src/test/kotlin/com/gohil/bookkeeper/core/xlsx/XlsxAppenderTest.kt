package com.gohil.bookkeeper.core.xlsx

import com.gohil.bookkeeper.core.model.Category
import com.gohil.bookkeeper.core.model.GstType
import com.gohil.bookkeeper.core.model.PaymentMethod
import com.gohil.bookkeeper.core.model.RegisterRow
import com.gohil.bookkeeper.core.model.RegisterType
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XlsxAppenderTest {

    private fun purchaseBook(
        rowCount: Int = 3,
        extraHeaders: List<String> = emptyList(),
    ): ByteArray = TestWorkbooks.build(
        sheets = listOf(
            "May" to TestWorkbooks.sampleRows(2),
            "Jun" to TestWorkbooks.sampleRows(rowCount),
        ),
        extraHeaders = extraHeaders,
    )

    private fun invoiceRow(
        date: LocalDate = LocalDate.of(2025, 6, 12),
        taxable: BigDecimal? = BigDecimal("2000.00"),
        total: BigDecimal? = BigDecimal("2360.00"),
        name: String = "Acme Traders",
    ) = RegisterRow(
        date = date,
        gstNo = "24AABCU9603R1ZX",
        invoiceNo = "INV-9001",
        name = name,
        qty = "1",
        ratePct = BigDecimal("18"),
        hsn = "998311",
        taxable = taxable,
        cgst = if (taxable != null) BigDecimal("180.00") else null,
        sgst = if (taxable != null) BigDecimal("180.00") else null,
        igst = null,
        totalGrand = total,
        paidDate = LocalDate.of(2025, 6, 20),
        paymentMethod = PaymentMethod.UPI,
        category = Category.BUSINESS,
    )

    /** A plain bank debit: no invoice, no tax breakdown, amount straight into TOTAL GRAND. */
    private fun bankRow(amount: String = "450.00") = RegisterRow(
        date = LocalDate.of(2025, 6, 5),
        name = "ATM WDL CHARGES",
        taxable = null,
        cgst = null,
        sgst = null,
        igst = null,
        totalGrand = BigDecimal(amount),
        paymentMethod = PaymentMethod.BANK_TRANSFER,
        category = Category.BUSINESS,
    )

    private fun appendTo(
        book: ByteArray,
        sheet: String = "Jun",
        rows: List<RegisterRow>,
        type: RegisterType = RegisterType.PURCHASE,
        createIfMissing: Boolean = true,
    ): Pair<XlsxAppender.Result, ByteArray> {
        val appender = XlsxAppender.open(book)
        val result = appender.append(
            XlsxAppender.Request(
                sheetName = sheet,
                rows = rows,
                registerType = type,
                createSheetIfMissing = createIfMissing,
            ),
        )
        return result to appender.toByteArray()
    }

    private fun sheetTextOf(book: ByteArray, tab: String): String {
        val pkg = XlsxPackage.read(book)
        val entry = WorkbookIndex.from(pkg).findSheet(tab) ?: error("no tab $tab")
        return pkg.text(entry.path)!!
    }

    private fun rowsOf(book: ByteArray, tab: String): List<SheetXml.Row> =
        SheetXml(sheetTextOf(book, tab)).rows()

    private fun cellTextAt(book: ByteArray, tab: String, ref: String): String? {
        val pkg = XlsxPackage.read(book)
        val entry = WorkbookIndex.from(pkg).findSheet(tab)!!
        val sheet = SheetXml(pkg.text(entry.path)!!)
        val shared = SharedStrings.from(pkg)
        val (colName, rowNum) = CellRef.split(ref)!!
        val cell = sheet.rows().firstOrNull { it.number == rowNum }
            ?.cellAt(CellRef.columnIndex(colName)) ?: return null
        return sheet.cellText(cell, shared)
    }

    // ── core behaviour ───────────────────────────────────────────────────────────

    @Test
    fun `appends rows below existing data and continues the SR NO sequence`() {
        val (result, out) = appendTo(purchaseBook(rowCount = 3), rows = listOf(invoiceRow(), bankRow()))

        assertEquals(2, result.rowsWritten)
        assertEquals(5, result.firstRowNumber, "3 data rows + header means the next row is 5")
        assertEquals(4, result.firstSrNo, "existing rows are SR NO 1..3")

        val rows = rowsOf(out, "Jun")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.map { it.number })
        assertEquals("4", cellTextAt(out, "Jun", "A5"))
        assertEquals("5", cellTextAt(out, "Jun", "A6"))
        assertEquals("Acme Traders", cellTextAt(out, "Jun", "E5"))
        assertEquals("ATM WDL CHARGES", cellTextAt(out, "Jun", "E6"))
    }

    @Test
    fun `leaves every other part of the workbook byte-for-byte identical`() {
        val original = purchaseBook()
        val (_, out) = appendTo(original, rows = listOf(invoiceRow()))

        val before = XlsxPackage.read(original)
        val after = XlsxPackage.read(out)

        // calcChain is expected to be dropped; the Jun sheet and dimension-bearing parts change.
        val expectedUntouched = before.entryNames
            .filterNot { it == "xl/worksheets/sheet2.xml" || it == "xl/calcChain.xml" }
            .filterNot { it == "[Content_Types].xml" || it == "xl/_rels/workbook.xml.rels" }

        for (name in expectedUntouched) {
            assertContentEquals(before[name], after[name], "part '$name' was modified")
        }
    }

    @Test
    fun `never touches the shared string table`() {
        val original = purchaseBook()
        val (_, out) = appendTo(original, rows = listOf(invoiceRow(name = "Brand New Vendor Pvt Ltd")))

        val before = XlsxPackage.read(original).text("xl/sharedStrings.xml")
        val after = XlsxPackage.read(out).text("xl/sharedStrings.xml")
        assertEquals(before, after, "new text must be written inline, not added to sharedStrings")
        assertFalse(after!!.contains("Brand New Vendor"))
        assertEquals("Brand New Vendor Pvt Ltd", cellTextAt(out, "Jun", "E5"))
    }

    @Test
    fun `does not disturb the other month's tab`() {
        val original = purchaseBook()
        val (_, out) = appendTo(original, sheet = "Jun", rows = listOf(invoiceRow()))
        assertEquals(sheetTextOf(original, "May"), sheetTextOf(out, "May"))
    }

    // ── the four regressions from the desktop app ────────────────────────────────

    @Test
    fun `bug 2 - TOTAL GRAND is a SUM formula only when the row has a taxable value`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow(), bankRow("450.00")))
        val sheet = sheetTextOf(out, "Jun")

        // Row 5 is the invoice: it has TAXABLE, so the SUM formula is correct.
        assertContains(sheet, "<f>SUM(I5:L5)</f>")

        // Row 6 is a bank charge with no tax breakdown. The old code wrote SUM here too,
        // which summed four blank cells and produced 0.00 in the register.
        assertFalse(sheet.contains("SUM(I6:L6)"), "bank rows must not get a SUM formula")
        assertEquals("450.00", cellTextAt(out, "Jun", "M6"))
    }

    @Test
    fun `bug 2 - the cached formula value matches the tax components`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        // 2000.00 + 180.00 + 180.00 + (blank IGST) = 2360.00
        assertEquals("2360.00", cellTextAt(out, "Jun", "M5"))
    }

    @Test
    fun `bug 4 - the same code appends to differently named tabs in the two workbooks`() {
        val purchase = TestWorkbooks.build(listOf("Jun" to TestWorkbooks.sampleRows(2)))
        val sales = TestWorkbooks.build(listOf("June" to TestWorkbooks.sampleRows(2)))

        val (pRes, _) = appendTo(purchase, sheet = "Jun", rows = listOf(invoiceRow()))
        val (sRes, _) = appendTo(
            sales,
            sheet = "June",
            rows = listOf(invoiceRow().copy(gstType = GstType.RCM, category = null)),
            type = RegisterType.SALES,
        )

        assertEquals("Jun", pRes.sheetName)
        assertEquals("June", sRes.sheetName)
        assertEquals(1, pRes.rowsWritten)
        assertEquals(1, sRes.rowsWritten)
    }

    // ── added columns ────────────────────────────────────────────────────────────

    @Test
    fun `appends the purchase register's extra columns to the right, once`() {
        val (first, out1) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        assertEquals(listOf("PAID DATE", "PAYMENT METHOD", "CATEGORY"), first.addedColumns)

        // N, O, P follow the original thirteen (A..M) — nothing was inserted between them.
        assertEquals("PAID DATE", cellTextAt(out1, "Jun", "N1"))
        assertEquals("PAYMENT METHOD", cellTextAt(out1, "Jun", "O1"))
        assertEquals("CATEGORY", cellTextAt(out1, "Jun", "P1"))
        assertEquals("UPI", cellTextAt(out1, "Jun", "O5"))
        assertEquals("Business Expense", cellTextAt(out1, "Jun", "P5"))

        // A second run must reuse those columns rather than adding duplicates.
        val (second, out2) = appendTo(out1, rows = listOf(invoiceRow()))
        assertTrue(second.addedColumns.isEmpty(), "columns should only ever be added once")
        assertEquals("Business Expense", cellTextAt(out2, "Jun", "P6"))
        assertEquals(1, SheetXml(sheetTextOf(out2, "Jun")).rows().first { it.number == 1 }
            .cells.count { c -> cellTextAt(out2, "Jun", c.ref) == "CATEGORY" })
    }

    @Test
    fun `places new columns after a column this code does not recognise`() {
        // Regression: the first new column was chosen by counting only the columns the
        // header map recognises. A column the user keeps themselves sits to the right of
        // TOTAL GRAND and is invisible to that map, so PAID DATE was emitted into the cell
        // it already occupies — a duplicate cell reference, and payment data filed under
        // someone else's heading.
        val book = purchaseBook(extraHeaders = listOf("Notes"))
        val (result, out) = appendTo(book, rows = listOf(invoiceRow()))

        assertEquals("Notes", cellTextAt(out, "Jun", "N1"), "the user's column must be left alone")
        assertEquals(listOf("PAID DATE", "PAYMENT METHOD", "CATEGORY"), result.addedColumns)
        assertEquals("PAID DATE", cellTextAt(out, "Jun", "O1"))
        assertEquals("PAYMENT METHOD", cellTextAt(out, "Jun", "P1"))
        assertEquals("CATEGORY", cellTextAt(out, "Jun", "Q1"))
        assertEquals("UPI", cellTextAt(out, "Jun", "P5"))

        // No column may appear twice in the header row.
        val headerRefs = SheetXml(sheetTextOf(out, "Jun")).rows().first { it.number == 1 }
            .cells.map { it.ref }
        assertEquals(headerRefs.size, headerRefs.distinct().size, "duplicate cell refs in header row")
    }

    @Test
    fun `sales register gets GST TYPE instead of CATEGORY`() {
        val book = TestWorkbooks.build(listOf("June" to TestWorkbooks.sampleRows(1)))
        val (result, out) = appendTo(
            book,
            sheet = "June",
            rows = listOf(invoiceRow().copy(category = null, gstType = GstType.RCM)),
            type = RegisterType.SALES,
        )
        assertEquals(listOf("PAID DATE", "PAYMENT METHOD", "GST TYPE"), result.addedColumns)
        assertEquals("RCM", cellTextAt(out, "June", "P3"))
    }

    @Test
    fun `recognises extra columns that already exist under a slightly different name`() {
        val book = purchaseBook(extraHeaders = listOf("Paid Date", "PAYMENT MODE", "Category"))
        val (result, out) = appendTo(book, rows = listOf(invoiceRow()))

        assertTrue(result.addedColumns.isEmpty(), "existing columns must be reused, not duplicated")
        assertEquals("UPI", cellTextAt(out, "Jun", "O5"))
        assertEquals("Business Expense", cellTextAt(out, "Jun", "P5"))
    }

    // ── formatting fidelity ──────────────────────────────────────────────────────

    @Test
    fun `new rows inherit the style of the row above them`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val sheet = SheetXml(sheetTextOf(out, "Jun"))
        val newRow = sheet.rows().first { it.number == 5 }

        assertEquals(2, newRow.cellAt(1)?.styleIndex, "DATE keeps the date format (style 2)")
        assertEquals(6, newRow.cellAt(8)?.styleIndex, "TAXABLE keeps the currency format (style 6)")
        assertEquals(6, newRow.cellAt(12)?.styleIndex, "TOTAL GRAND keeps the currency format")
    }

    @Test
    fun `a newly added PAID DATE column borrows the DATE column's date format`() {
        // Regression: a column this project creates has no row above it to copy formatting
        // from, so it used to fall back to General — and a date serial rendered in the
        // register as the bare number 45828 instead of a date.
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val sheet = SheetXml(sheetTextOf(out, "Jun"))
        val row = sheet.rows().first { it.number == 5 }

        val dateStyle = row.cellAt(CellRef.columnIndex("B"))?.styleIndex
        val paidDateStyle = row.cellAt(CellRef.columnIndex("N"))?.styleIndex
        assertEquals(2, dateStyle, "sanity: DATE uses the date-formatted style")
        assertEquals(dateStyle, paidDateStyle, "PAID DATE must share the DATE format")
    }

    @Test
    fun `newly added text columns borrow the NAME column's format`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val sheet = SheetXml(sheetTextOf(out, "Jun"))
        val row = sheet.rows().first { it.number == 5 }
        val nameStyle = row.cellAt(CellRef.columnIndex("E"))?.styleIndex
        assertEquals(nameStyle, row.cellAt(CellRef.columnIndex("O"))?.styleIndex)
        assertEquals(nameStyle, row.cellAt(CellRef.columnIndex("P"))?.styleIndex)
    }

    @Test
    fun `writes dates as serial numbers when the column already holds serials`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow(date = LocalDate.of(2025, 6, 12))))
        // 2025-06-12 is serial 45820 under the 1900 date system.
        assertEquals("45820", cellTextAt(out, "Jun", "B5"))
        assertEquals(LocalDate.of(2025, 6, 12), CellRef.fromSerial(45820))
    }

    @Test
    fun `writes RATE % the way the sheet already writes it`() {
        // Found in the user's own books: the purchase register stores 18 with a General
        // format, the sales register stores 0.18 with a 0% format. Both display "18".
        // Writing 18 into the sales register displayed 1800%, in a document that goes to a
        // chartered accountant, because an appended cell inherits the row above's format.
        val wholeNumbers = TestWorkbooks.build(sheets = listOf("Jun" to TestWorkbooks.sampleRows(2)))
        val (_, wholeOut) = appendTo(wholeNumbers, rows = listOf(invoiceRow()))
        assertEquals("18", cellTextAt(wholeOut, "Jun", "G4"))

        val fractions = TestWorkbooks.build(
            sheets = listOf("Jun" to TestWorkbooks.sampleRows(2).map { it.copy(ratePct = "0.18") }),
        )
        val (_, fractionOut) = appendTo(fractions, rows = listOf(invoiceRow()))
        assertEquals(
            "0.18",
            cellTextAt(fractionOut, "Jun", "G4"),
            "under a 0% format, 18 renders as 1800% and 0.18 renders as 18%",
        )
    }

    @Test
    fun `an empty RATE % column is written as a whole percentage`() {
        // Nothing to copy from, so it keeps the plainer of the two conventions rather than
        // inventing a fraction the sheet has given no reason to expect.
        val book = TestWorkbooks.build(
            sheets = listOf("Jun" to TestWorkbooks.sampleRows(2).map { it.copy(ratePct = "") }),
        )
        val (_, out) = appendTo(book, rows = listOf(invoiceRow()))
        assertEquals("18", cellTextAt(out, "Jun", "G4"))
    }

    @Test
    fun `updates the sheet dimension to cover the new extent`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val sheet = sheetTextOf(out, "Jun")
        assertContains(sheet, """<dimension ref="A1:P5"/>""")
    }

    @Test
    fun `drops the stale calc chain once formulas are written`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val pkg = XlsxPackage.read(out)
        assertNull(pkg["xl/calcChain.xml"])
        assertFalse(pkg.text("[Content_Types].xml")!!.contains("calcChain"))
        assertFalse(pkg.text("xl/_rels/workbook.xml.rels")!!.contains("calcChain"))
    }

    @Test
    fun `preserves sheet features it knows nothing about`() {
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow()))
        val sheet = sheetTextOf(out, "Jun")
        assertContains(sheet, """<pane ySplit="1"""", message = "frozen header pane must survive")
        assertContains(sheet, """<col min="5" max="5" width="32"""", message = "column widths must survive")
        assertContains(sheet, "<pageMargins", message = "print setup must survive")
    }

    // ── creating a month tab ─────────────────────────────────────────────────────

    @Test
    fun `creates a missing month tab by cloning the previous month's layout`() {
        val book = purchaseBook()
        val (result, out) = appendTo(book, sheet = "Jul", rows = listOf(invoiceRow()))

        assertTrue(result.createdSheet)
        assertEquals(listOf("May", "Jun", "Jul"), XlsxAppender.open(out).sheetNames())

        // The clone carries the headers but none of June's data.
        assertEquals("SR NO", cellTextAt(out, "Jul", "A1"))
        assertEquals("TOTAL GRAND", cellTextAt(out, "Jul", "M1"))
        assertEquals(1, result.firstSrNo, "a fresh tab restarts the SR NO sequence")
        assertEquals(listOf(1, 2), rowsOf(out, "Jul").map { it.number })
        assertEquals("Acme Traders", cellTextAt(out, "Jul", "E2"))

        // And June is left exactly as it was.
        assertEquals(sheetTextOf(book, "Jun"), sheetTextOf(out, "Jun"))
    }

    @Test
    fun `a cloned tab is registered in all three places Excel requires`() {
        val (_, out) = appendTo(purchaseBook(), sheet = "Jul", rows = listOf(invoiceRow()))
        val pkg = XlsxPackage.read(out)

        val newPath = WorkbookIndex.from(pkg).findSheet("Jul")!!.path
        assertNotNull(pkg[newPath], "worksheet part must exist")
        assertContains(pkg.text("[Content_Types].xml")!!, newPath)
        assertContains(pkg.text("xl/workbook.xml")!!, """name="Jul"""")
        assertContains(pkg.text("xl/_rels/workbook.xml.rels")!!, newPath.removePrefix("xl/"))
    }

    @Test
    fun `refuses to invent a tab when asked not to`() {
        val ex = assertFailsWith<XlsxException> {
            appendTo(purchaseBook(), sheet = "Jul", rows = listOf(invoiceRow()), createIfMissing = false)
        }
        assertContains(ex.message!!, "Jul")
        assertContains(ex.message!!, "May, Jun", message = "should list what is available")
    }

    // ── refusals ─────────────────────────────────────────────────────────────────

    @Test
    fun `rejects a file that is not an xlsx`() {
        val ex = assertFailsWith<XlsxException> { XlsxAppender.open("not a zip".toByteArray()) }
        assertContains(ex.message!!, "xlsx")
    }

    @Test
    fun `rejects a workbook whose header row is unrecognisable`() {
        val junk = TestWorkbooks.build(listOf("Jun" to emptyList()))
            .let { XlsxPackage.read(it) }
            .also { pkg ->
                val path = WorkbookIndex.from(pkg).findSheet("Jun")!!.path
                pkg.putText(
                    path,
                    pkg.text(path)!!.replace(Regex("""<row r="1".*?</row>"""), """<row r="1"/>"""),
                )
            }
            .toByteArray()

        val ex = assertFailsWith<XlsxException> {
            XlsxAppender.open(junk).append(
                XlsxAppender.Request("Jun", listOf(invoiceRow()), RegisterType.PURCHASE),
            )
        }
        assertContains(ex.message!!, "header row")
    }

    @Test
    fun `writes nothing at all when given no rows`() {
        val original = purchaseBook()
        val (result, out) = appendTo(original, rows = emptyList())
        assertEquals(0, result.rowsWritten)
        assertEquals(rowsOf(original, "Jun").size, rowsOf(out, "Jun").size)
    }

    @Test
    fun `escapes text that would otherwise break the XML`() {
        val nasty = """Sharma & Sons <Pvt> "Ltd" 'x'"""
        val (_, out) = appendTo(purchaseBook(), rows = listOf(invoiceRow(name = nasty)))
        assertEquals(nasty, cellTextAt(out, "Jun", "E5"))
    }
}
